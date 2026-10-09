(ns clojurecraft.game
  "The world as one value, and the protocol as a pure reducer over events.

     (step world event) → world'

   The world is a flat map of namespaced attributes (:player/pos, :world/chunks, :bot/phase ...),
   open and sparse: an attribute the bot does not know yet is simply absent. Events are maps:

     {:event/kind :start}
     {:event/kind :packet :event/packet pkt}
     {:event/kind :tick :event/now ms :event/rand r}   ; the clock and randomness are inputs
     {:event/kind :go}
     {:event/kind :closed :event/reason s}

   Everything the bot wants done is written to :bot/effects as data ({:effect/kind :send
   :effect/packet p}, {:effect/kind :log :effect/message s}); the loop drains and performs them.
   Packet handling is a multimethod on [phase name], so a new namespace can handle a packet
   without editing this one. The protocol phase changes only when a transition packet is
   emitted, so it has one source of truth: clojurecraft.packet/transitions."
  (:require [clojurecraft.blocks :as blocks]
            [clojurecraft.bytes :as b]
            [clojurecraft.chunk :as chunk]
            [clojurecraft.memory :as memory]
            [clojurecraft.packet :as p]
            [clojurecraft.physics :as physics]))

(def protocol-version 775)

(defn init [{:keys [host port name]}]
  {:bot/phase :handshake
   :bot/name name
   :bot/uuid (b/offline-uuid name)
   :bot/effects []
   :bot/sequence 0
   :conn/host host
   :conn/port port
   :time/now 0
   :time/tick 0
   :player/vel [0.0 0.0 0.0]
   :player/look [0.0 0.0]
   :player/on-ground? false
   :player/horizontal-collision? false
   :player/jump-ticks 0
   :player/loaded? false
   :player/controls {}
   :player/inventory {}
   :window/grid {}
   :player/held-slot 0
   :world/chunks {}
   :world/blocks {}
   :world/entities {}
   :world/sightings {}
   :stats/unknown {}
   :stats/keep-alives 0
   :stats/teleports 0
   :stats/chunks 0})

;; ---------------------------------------------------------------- effects

(defn effect [world e] (update world :bot/effects conj e))

(defn emit
  "Queue a packet to send; this is the only place the protocol phase advances."
  [world pkt]
  (-> world
      (effect {:effect/kind :send :effect/packet pkt})
      (update :bot/phase p/next-state (:packet/name pkt))))

(defn say [world message] (effect world {:effect/kind :log :effect/message message}))

;; ---------------------------------------------------------------- queries

(defn block-at
  "State id at [x y z]: local overlay first, then the chunk; nil when unloaded."
  [world pos]
  (or (get (:world/blocks world) pos) (chunk/block-at (:world/chunks world) pos)))

(defn solid-fn
  "Solidity oracle for physics. Unloaded counts as solid so the bot never falls out of the world."
  [world]
  (fn [x y z]
    (let [id (block-at world [x y z])]
      (if (nil? id) true (blocks/solid? id)))))

(defn eye [world] (physics/eye (:player/pos world)))

(defn item-count
  "How many of an item (by id) the player holds; the crafting grid is not the inventory."
  [world id]
  (reduce + 0 (for [[_ {:keys [item count]}] (:player/inventory world) :when (= item id)] count)))

(defn logs-held [world]
  (reduce + 0 (for [[_ {:keys [item count]}] (:player/inventory world) :when (blocks/log-item? item)] count)))

(defn chunk-loaded? [world [x _ z]]
  (contains? (:world/chunks world)
             [(bit-shift-right (long (Math/floor x)) 4) (bit-shift-right (long (Math/floor z)) 4)]))

(defn container->player-slot
  "Window-0 slot → player-inventory slot (the key space of :player/inventory), or nil."
  [^long s]
  (cond (<= 36 s 44) (- s 36)
        (<= 9 s 35) s
        (<= 5 s 8) (- 44 s)                ; window 5-8 are head..feet; player 36-39 are feet..head
        (= s 45) 40
        :else nil))

(defn- set-slot [world slot item]
  (if item (assoc-in world [:player/inventory slot] item) (update world :player/inventory dissoc slot)))

;; ---------------------------------------------------------------- packets

(defn- on-ground-flag [world] (if (:player/on-ground? world) 1 0))

(defn- send-position [world]
  (let [[x y z] (:player/pos world) [yaw pitch] (:player/look world)]
    (-> world
        (emit {:packet/name :move-player-pos-rot :x x :y y :z z :yaw yaw :pitch pitch :flags (on-ground-flag world)})
        (assoc :net/sent-pos (:player/pos world) :net/sent-look (:player/look world) :net/sent-tick (:time/tick world)))))

(defn- relative [flags bit old new] (if (pos? (bit-and flags bit)) (+ old new) new))

(defn- apply-teleport [world {:keys [teleport-id x y z yaw pitch flags]}]
  (let [[ox oy oz] (or (:player/pos world) [0.0 0.0 0.0])
        [oyaw opitch] (:player/look world)
        loaded? (:player/loaded? world)]
    (-> world
        (assoc :player/pos [(relative flags 1 ox x) (relative flags 2 oy y) (relative flags 4 oz z)]
               :player/look [(relative flags 8 oyaw yaw) (relative flags 16 opitch pitch)]
               :player/vel [0.0 0.0 0.0]
               :player/on-ground? false
               :player/teleport-id teleport-id
               :player/synced-at (:time/now world))
        (update :stats/teleports inc)
        (emit {:packet/name :accept-teleportation :teleport-id teleport-id})
        send-position
        (cond-> (not loaded?) (-> (emit {:packet/name :player-loaded})
                                  (assoc :player/loaded? true))))))

(defn- chunk-key [v] [(long (unchecked-int v)) (long (unchecked-int (bit-shift-right v 32)))])

(defn- in-chunk? [key [bx _ bz]] (= key [(bit-shift-right bx 4) (bit-shift-right bz 4)]))

(defn- load-chunk [world {:keys [x z data]}]
  (let [key [x z]
        column (try (chunk/decode data) (catch Exception e {:chunk/error (str e)}))]
    (if (:chunk/error column)
      (say world (str "bad chunk " key ": " (:chunk/error column)))
      (-> world
          (assoc-in [:world/chunks key] column)
          (update :world/blocks (fn [m] (into {} (remove (fn [[pos _]] (in-chunk? key pos)) m))))
          (update :stats/chunks inc)
          (memory/remember-column key column)))))

(defn set-block
  "A block is known to be id now: overlay the chunk and keep the sighting."
  [world pos id]
  (-> world (assoc-in [:world/blocks pos] id) (memory/observe pos id)))

(defn- section-update [world {:keys [section blocks]}]
  (let [sx (bit-shift-right section 42)
        sz (bit-shift-right (bit-shift-left section 22) 42)
        sy (bit-shift-right (bit-shift-left section 44) 44)]
    (reduce (fn [world v]
              (let [id (unsigned-bit-shift-right v 12)
                    lx (bit-and (bit-shift-right v 8) 15)
                    lz (bit-and (bit-shift-right v 4) 15)
                    ly (bit-and v 15)]
                (set-block world [(+ (* 16 sx) lx) (+ (* 16 sy) ly) (+ (* 16 sz) lz)] id)))
            world blocks)))

(defn- move-entity [world eid dx dy dz]
  (if (get-in world [:world/entities eid])
    (update-in world [:world/entities eid :entity/pos]
               (fn [[x y z]] [(+ x (/ dx 4096.0)) (+ y (/ dy 4096.0)) (+ z (/ dz 4096.0))]))
    world))

(defmulti on-packet
  "Dispatch on [phase packet-name]; the three synthetic packets dispatch on their name alone."
  (fn [world pkt]
    (let [n (:packet/name pkt)]
      (if (contains? #{:unknown :decode-error :closed} n) n [(:bot/phase world) n]))))

(defmethod on-packet :default [world _] world)

(defmethod on-packet :unknown [world pkt]
  (update-in world [:stats/unknown [(:bot/phase world) (:packet/id pkt)]] (fnil inc 0)))

(defmethod on-packet :decode-error [world pkt] (say world (str "decode error " pkt)))
(defmethod on-packet :closed [world pkt] (assoc world :bot/closed (:packet/reason pkt)))

(defmethod on-packet [:login :login-finished] [w _] (emit w {:packet/name :login-acknowledged}))
(defmethod on-packet [:login :login-disconnect] [w p] (assoc w :bot/disconnected (:reason p)))

(defmethod on-packet [:configuration :select-known-packs] [w _] (emit w {:packet/name :select-known-packs :packs []}))
(defmethod on-packet [:configuration :keep-alive] [w p]
  (-> w (emit {:packet/name :keep-alive :id (:id p)}) (update :stats/keep-alives inc)))
(defmethod on-packet [:configuration :ping] [w p] (emit w {:packet/name :pong :id (:id p)}))
(defmethod on-packet [:configuration :finish-configuration] [w _] (emit w {:packet/name :finish-configuration}))
(defmethod on-packet [:configuration :disconnect] [w p]
  (assoc w :bot/disconnected (String. ^bytes (:reason p) "ISO-8859-1")))

(defn- fresh-menus
  "A login or respawn gives the player a fresh menu: nothing open, an empty cursor, and a grid
   the server empties back into the inventory (it then sends the window's contents)."
  [w]
  (-> w (dissoc :window/open :window/cursor) (assoc :window/grid {})))

(defmethod on-packet [:play :respawn] [w _] (fresh-menus w))

(defmethod on-packet [:play :login] [w p]
  (-> w
      fresh-menus
      (assoc :player/entity-id (:entity-id p))
      (emit {:packet/name :client-information :locale "en_US" :view-distance 6 :chat-mode 0
             :chat-colors true :skin-parts 0x7f :main-hand 1 :text-filtering false
             :server-listing true :particle-status 0})))
(defmethod on-packet [:play :keep-alive] [w p]
  (-> w (emit {:packet/name :keep-alive :id (:id p)}) (update :stats/keep-alives inc)))
(defmethod on-packet [:play :ping] [w p] (emit w {:packet/name :pong :id (:id p)}))
(defmethod on-packet [:play :player-position] [w p] (apply-teleport w p))
(defmethod on-packet [:play :chunk-batch-finished] [w _]
  (emit w {:packet/name :chunk-batch-received :chunks-per-tick 20.0}))
(defmethod on-packet [:play :level-chunk-with-light] [w p] (load-chunk w p))
(defmethod on-packet [:play :forget-level-chunk] [w p] (update w :world/chunks dissoc (chunk-key (:pos p))))
(defmethod on-packet [:play :block-update] [w p] (set-block w (:pos p) (:state p)))
(defmethod on-packet [:play :section-blocks-update] [w p] (section-update w p))
(defmethod on-packet [:play :set-health] [w p] (assoc w :player/health (:health p)))
(def menus
  "Container layouts by menu type id (registry minecraft:menu). :menu/grid are the crafting
   slots (0 is the result); :menu/inventory is the first of the 36 player-inventory slots,
   27 main then 9 hotbar, as every vanilla container appends them."
  {12 {:menu/name :crafting :menu/size 3 :menu/grid (range 0 10) :menu/inventory 10}})

(defn window->player-slot
  "A slot of an open container → player-inventory slot, or nil when it is the container's own."
  [menu-type ^long s]
  (when-let [start (:menu/inventory (menus menu-type))]
    (let [i (- s start)]
      (cond (<= 0 i 26) (+ i 9)
            (<= 27 i 35) (- i 27)
            :else nil))))

(defn- set-open-window-slot
  "An open container: its own slots live in :window/open's :window/slots; slots that are the
   player's inventory update :player/inventory, so items are never counted in two places."
  [w s item]
  (let [menu-type (get-in w [:window/open :window/menu-type])]
    (if-let [p (window->player-slot menu-type s)]
      (set-slot w p item)
      (if item
        (assoc-in w [:window/open :window/slots s] item)
        (update-in w [:window/open :window/slots] dissoc s)))))

(defn- open? [w window-id] (= window-id (get-in w [:window/open :window/id])))

(defn- set-window-0-slot
  "Window 0 is the player's own screen: slots 0-4 are the crafting grid (0 is the result) and
   are kept verbatim in :window/grid, so nothing in the grid is ever invisible; the rest map to
   :player/inventory."
  [w i item]
  (cond
    (<= 0 i 4) (if item (assoc-in w [:window/grid i] item) (update w :window/grid dissoc i))
    (container->player-slot i) (set-slot w (container->player-slot i) item)
    :else w))

(defmethod on-packet [:play :container-set-content] [w {:keys [window-id state-id items carried]}]
  (let [w (cond-> w carried (assoc :window/cursor carried) (nil? carried) (dissoc :window/cursor))]
    (cond
      (zero? window-id)
      (reduce (fn [w [i item]] (set-window-0-slot w i item)) (assoc w :window/state-id state-id) (map-indexed vector items))
      (open? w window-id)
      (reduce (fn [w [i item]] (set-open-window-slot w i item))
              (assoc-in w [:window/open :window/state-id] state-id) (map-indexed vector items))
      :else w)))
(defmethod on-packet [:play :container-set-slot] [w {:keys [window-id state-id slot item]}]
  (cond
    (zero? window-id) (-> w (assoc :window/state-id state-id) (set-window-0-slot slot item))
    (open? w window-id) (-> w (assoc-in [:window/open :window/state-id] state-id) (set-open-window-slot slot item))
    :else w))
(defmethod on-packet [:play :set-held-slot] [w {:keys [slot]}] (if (<= 0 slot 8) (assoc w :player/held-slot slot) w))
(defmethod on-packet [:play :set-cursor-item] [w {:keys [item]}]
  (if item (assoc w :window/cursor item) (dissoc w :window/cursor)))
(defmethod on-packet [:play :open-screen] [w {:keys [window-id menu-type]}]
  (assoc w :window/open {:window/id window-id :window/menu-type menu-type :window/slots {}}))
(defmethod on-packet [:play :container-close] [w _] (dissoc w :window/open))
(defmethod on-packet [:play :set-player-inventory] [w {:keys [slot item]}] (set-slot w slot item))
(defmethod on-packet [:play :add-entity] [w {:keys [entity-id type x y z]}]
  (if (= type blocks/item-entity-type)
    (assoc-in w [:world/entities entity-id] {:entity/type type :entity/pos [x y z] :entity/seen-at (:time/now w)})
    w))
(defmethod on-packet [:play :move-entity-pos] [w {:keys [entity-id dx dy dz]}] (move-entity w entity-id dx dy dz))
(defmethod on-packet [:play :move-entity-pos-rot] [w {:keys [entity-id dx dy dz]}] (move-entity w entity-id dx dy dz))
(defmethod on-packet [:play :entity-position-sync] [w {:keys [entity-id x y z]}]
  (if (get-in w [:world/entities entity-id]) (assoc-in w [:world/entities entity-id :entity/pos] [x y z]) w))
(defmethod on-packet [:play :remove-entities] [w {:keys [ids]}] (update w :world/entities #(apply dissoc % ids)))
(defmethod on-packet [:play :take-item-entity] [w p] (update w :stats/pickups (fnil conj []) p))
(defmethod on-packet [:play :block-changed-ack] [w p] (assoc w :stats/last-ack (:sequence p)))
(defmethod on-packet [:play :start-configuration] [w _] (emit w {:packet/name :configuration-acknowledged}))
(defmethod on-packet [:play :disconnect] [w p]
  (assoc w :bot/disconnected (String. ^bytes (:reason p) "ISO-8859-1")))

;; ---------------------------------------------------------------- ticks

(defn- physics-ready? [world]
  (and (= :play (:bot/phase world))
       (:player/pos world)
       (:player/loaded? world)
       (chunk-loaded? world (:player/pos world))))

(defn- movement-packets
  "pos-rot when something changed, status-only once a second otherwise (vanilla's rule)."
  [world]
  (cond
    (or (not= (:player/pos world) (:net/sent-pos world))
        (not= (:player/look world) (:net/sent-look world)))
    (send-position world)

    (>= (- (:time/tick world) (or (:net/sent-tick world) 0)) 20)
    (-> world
        (emit {:packet/name :move-player-status-only :flags (on-ground-flag world)})
        (assoc :net/sent-tick (:time/tick world)))

    :else world))

(defn- on-tick [world {:event/keys [now]}]
  (let [world (-> world (assoc :time/now now) (update :time/tick inc))]
    (if (physics-ready? world)
      (let [controls (:player/controls world)]
        (-> (physics/step (solid-fn world) world controls)
            (cond-> (:control/look controls) (assoc :player/look (:control/look controls)))
            movement-packets))
      world)))

;; ---------------------------------------------------------------- step

(defmulti on-event (fn [_world event] (:event/kind event)))

(defmethod on-event :default [world _] world)

(defmethod on-event :start [world _]
  (-> world
      (emit {:packet/name :intention :protocol-version protocol-version
             :host (:conn/host world) :port (:conn/port world) :next-state 2})
      (emit {:packet/name :hello :username (:bot/name world) :uuid (:bot/uuid world)})))

(defmethod on-event :packet [world {:event/keys [packet]}] (on-packet world packet))
(defmethod on-event :tick [world event] (on-tick world event))
(defmethod on-event :closed [world {:event/keys [reason]}] (assoc world :bot/closed reason))

(defn step [world event] (on-event world event))

(defn compose
  "Several reducers with the step signature into one, applied left to right."
  [& steps]
  (fn [world event] (reduce (fn [w f] (f w event)) world steps)))

(defn summary [world]
  {:phase (:bot/phase world)
   :pos (:player/pos world)
   :on-ground? (:player/on-ground? world)
   :loaded? (:player/loaded? world)
   :chunks (count (:world/chunks world))
   :sightings (count (:world/sightings world))
   :logs (logs-held world)
   :inventory (count (:player/inventory world))
   :entities (count (:world/entities world))
   :stats (into {} (filter (fn [[k _]] (= "stats" (namespace k))) world))
   :disconnected (:bot/disconnected world)
   :closed (:bot/closed world)})
