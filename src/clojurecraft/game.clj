(ns clojurecraft.game
  "The world as one value, and the protocol as a pure reducer over events.

     (step world event) → world'

   The world is a flat map of namespaced attributes (:player/pos, :world/chunks, :bot/phase ...),
   open and sparse: an attribute the bot does not know yet is simply absent. Events are maps:

     {:event/kind :start :start/host h :start/port p :start/name n}   ; connection, all optional
     {:event/kind :packet :event/packet pkt}
     {:event/kind :tick :event/now ms :event/rand r}   ; the clock and randomness are inputs
     {:event/kind :go}
     {:event/kind :closed :event/reason s}

   Everything the bot wants done is written to :bot/effects as data ({:effect/kind :send
   :effect/packet p}, {:effect/kind :log :effect/message s}); the loop drains and performs them.
   Packet handling is a multimethod on [phase name], so a new namespace can handle a packet
   without editing this one. The protocol phase changes only when a transition packet is
   emitted, so it has one source of truth: clojurecraft.packet/transitions."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojurecraft.blocks :as blocks]
            [clojurecraft.bytes :as b]
            [clojurecraft.chunk :as chunk]
            [clojurecraft.inventory :as inventory]
            [clojurecraft.memory :as memory]
            [clojurecraft.packet :as p]
            [clojurecraft.physics :as physics]))

(def version
  "The server version the generated tables describe (resources/clojurecraft/version.edn)."
  (edn/read-string (slurp (io/resource "clojurecraft/version.edn"))))

(def protocol-version
  "The protocol number the handshake announces, read from the server's version data."
  (:version/protocol version))

(defn init
  "The world before any event: handshake phase, no position, empty inventory, chunks and facts.
   Attributes the bot cannot know yet (:player/pos, :player/entity-id ...) are absent."
  [{:keys [host port name]}]
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
   :world/facts (memory/empty-facts)
   :stats/unknown {}
   :stats/keep-alives 0
   :stats/teleports 0
   :stats/chunks 0})

;; ---------------------------------------------------------------- effects

(defn effect "Append one effect map to :bot/effects for the loop to perform." [world e] (update world :bot/effects conj e))

(defn emit
  "Queue a packet to send; this is the only place the protocol phase advances."
  [world pkt]
  (-> world
      (effect {:effect/kind :send :effect/packet pkt})
      (update :bot/phase p/next-state (:packet/name pkt))))

(defn say "Queue a log line; the loop prints it to stderr." [world message] (effect world {:effect/kind :log :effect/message message}))

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

(defn eye "The player's eye position; :player/pos must be known." [world] (physics/eye (:player/pos world)))

(defn chunk-loaded?
  "Is the chunk column holding block or feet position [x y z] in :world/chunks?"
  [world [x _ z]]
  (contains? (:world/chunks world)
             [(bit-shift-right (long (Math/floor x)) 4) (bit-shift-right (long (Math/floor z)) 4)]))

;; ---------------------------------------------------------------- packets

(defn on-ground-flag "The movement packets' flags byte: 1 when on the ground, else 0." [world] (if (:player/on-ground? world) 1 0))

(defn send-position
  "Emit move-player-pos-rot with the current pos and look, and remember what was sent."
  [world]
  (let [[x y z] (:player/pos world) [yaw pitch] (:player/look world)]
    (-> world
        (emit {:packet/name :move-player-pos-rot :x x :y y :z z :yaw yaw :pitch pitch :flags (on-ground-flag world)})
        (assoc :net/sent-pos (:player/pos world) :net/sent-look (:player/look world) :net/sent-tick (:time/tick world)))))

(defn relative
  "One teleport coordinate: old + new when the flag bit says relative, else new."
  [flags bit old new] (if (pos? (bit-and flags bit)) (+ old new) new))

(defn apply-teleport
  "The server moved the player: set pos and look (relative per flags), stop, accept the
   teleport and echo the position. The first teleport also sends player-loaded."
  [world {:keys [teleport-id x y z yaw pitch flags]}]
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

(defn chunk-key
  "[cx cz] from a packed chunk position (x in the low 32 bits, z in the high 32)."
  [v] [(long (unchecked-int v)) (long (unchecked-int (bit-shift-right v 32)))])

(defn in-chunk? "Is block [x y z] inside chunk column key [cx cz]?" [key [bx _ bz]] (= key [(bit-shift-right bx 4) (bit-shift-right bz 4)]))

(defn load-chunk
  "Put a chunk column into :world/chunks, drop overlay blocks it supersedes, and remember what
   it holds. The column is the one the reader thread attached, or decoded here from the wire
   bytes (a replay, the sim); one that fails to decode is logged and skipped, never thrown."
  [world {:keys [x z] :as pkt}]
  (let [key [x z]
        column (:chunk/column (chunk/attach pkt))]
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

(defn section-update
  "Apply a section-blocks-update: unpack the section coords and each packed (state, local
   x y z) and set every block."
  [world {:keys [section blocks]}]
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

(defn move-entity
  "Shift a tracked entity by a delta in 1/4096 blocks; untracked entities are ignored."
  [world eid dx dy dz]
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

(defmethod on-packet [:play :respawn] [w _] (inventory/fresh-menus w))

(defmethod on-packet [:play :login] [w p]
  (-> w
      inventory/fresh-menus
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

(defmethod on-packet [:play :container-set-content] [w {:keys [window-id state-id items carried]}]
  (let [w (cond-> w carried (assoc :window/cursor carried) (nil? carried) (dissoc :window/cursor))]
    (cond
      (zero? window-id)
      (reduce (fn [w [i item]] (inventory/set-window-0-slot w i item)) (assoc w :window/state-id state-id) (map-indexed vector items))
      (inventory/open? w window-id)
      (reduce (fn [w [i item]] (inventory/set-open-window-slot w i item))
              (assoc-in w [:window/open :window/state-id] state-id) (map-indexed vector items))
      :else w)))
(defmethod on-packet [:play :container-set-slot] [w {:keys [window-id state-id slot item]}]
  (cond
    (zero? window-id) (-> w (assoc :window/state-id state-id) (inventory/set-window-0-slot slot item))
    (inventory/open? w window-id) (-> w (assoc-in [:window/open :window/state-id] state-id) (inventory/set-open-window-slot slot item))
    :else w))
(defmethod on-packet [:play :set-held-slot] [w {:keys [slot]}] (if (<= 0 slot 8) (assoc w :player/held-slot slot) w))
(defmethod on-packet [:play :set-cursor-item] [w {:keys [item]}]
  (if item (assoc w :window/cursor item) (dissoc w :window/cursor)))
(defmethod on-packet [:play :open-screen] [w {:keys [window-id menu-type]}]
  (assoc w :window/open {:window/id window-id :window/menu-type menu-type :window/slots {}}))
(defmethod on-packet [:play :container-close] [w _] (dissoc w :window/open))
(defmethod on-packet [:play :set-player-inventory] [w {:keys [slot item]}] (inventory/set-slot w slot item))
(defmethod on-packet [:play :add-entity] [w {:keys [entity-id type x y z]}]
  (if (= type blocks/item-entity-type)
    (assoc-in w [:world/entities entity-id] {:entity/type type :entity/pos [x y z] :entity/seen-at (:time/now w)})
    w))
(defmethod on-packet [:play :move-entity-pos] [w {:keys [entity-id dx dy dz]}] (move-entity w entity-id dx dy dz))
(defmethod on-packet [:play :move-entity-pos-rot] [w {:keys [entity-id dx dy dz]}] (move-entity w entity-id dx dy dz))
(defmethod on-packet [:play :entity-position-sync] [w {:keys [entity-id x y z]}]
  (if (get-in w [:world/entities entity-id]) (assoc-in w [:world/entities entity-id :entity/pos] [x y z]) w))
(defmethod on-packet [:play :remove-entities] [w {:keys [ids]}] (update w :world/entities #(apply dissoc % ids)))
(defmethod on-packet [:play :take-item-entity] [w {:keys [collected collector count] :as p}]
  (cond-> (update w :stats/pickups (fnil conj []) p)
    (= collector (:player/entity-id w))
    (memory/remember-answer {:answer/kind :pickup :answer/entity collected :answer/count count})))
(defmethod on-packet [:play :block-changed-ack] [w p]
  (-> w
      (assoc :stats/last-ack (:sequence p))
      (memory/remember-answer {:answer/kind :ack :answer/sequence (:sequence p)})))
(defmethod on-packet [:play :start-configuration] [w _] (emit w {:packet/name :configuration-acknowledged}))
(defmethod on-packet [:play :disconnect] [w p]
  (assoc w :bot/disconnected (String. ^bytes (:reason p) "ISO-8859-1")))

;; ---------------------------------------------------------------- ticks

(defn physics-ready?
  "May physics run this tick: in play, positioned, loaded, and standing in a loaded chunk."
  [world]
  (and (= :play (:bot/phase world))
       (:player/pos world)
       (:player/loaded? world)
       (chunk-loaded? world (:player/pos world))))

(defn movement-packets
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

(defn on-tick
  "Advance the clock; when physics-ready?, move the player under :player/controls and emit
   the movement packet vanilla would."
  [world {:event/keys [now]}]
  (let [world (-> world (assoc :time/now now) (update :time/tick inc))]
    (if (physics-ready? world)
      (let [controls (:player/controls world)]
        (-> (physics/step (solid-fn world) world controls)
            (cond-> (:control/look controls) (assoc :player/look (:control/look controls)))
            movement-packets))
      world)))

;; ---------------------------------------------------------------- step

(defmulti on-event
  "Dispatch on :event/kind; kinds with no method leave the world unchanged."
  (fn [_world event] (:event/kind event)))

(defmethod on-event :default [world _] world)

(defn connection
  "The :start event may carry the connection it starts (:start/host :start/port :start/name);
   then it, not init's arguments, decides them. That makes the connection an input in the
   recording, so a replay rebuilds the same handshake (and the same offline UUID)."
  [world {:start/keys [host port name]}]
  (cond-> world
    host (assoc :conn/host host)
    port (assoc :conn/port port)
    name (assoc :bot/name name :bot/uuid (b/offline-uuid name))))

(defmethod on-event :start [world event]
  (let [world (connection world event)]
    (-> world
        (emit {:packet/name :intention :protocol-version protocol-version
               :host (:conn/host world) :port (:conn/port world) :next-state 2})
        (emit {:packet/name :hello :username (:bot/name world) :uuid (:bot/uuid world)}))))

(defmethod on-event :packet [world {:event/keys [packet]}] (on-packet world packet))
(defmethod on-event :tick [world event] (on-tick world event))
(defmethod on-event :closed [world {:event/keys [reason]}] (assoc world :bot/closed reason))

(defn step
  "The protocol reducer: (step world event) → world'. Pure; the clock and randomness come in
   on the event."
  [world event] (on-event world event))

(defn compose
  "Several reducers with the step signature into one, applied left to right."
  [& steps]
  (fn [world event] (reduce (fn [w f] (f w event)) world steps)))

(defn summary
  "The game part of the RESULT line: phase, position, counts and every :stats/* attribute."
  [world]
  {:phase (:bot/phase world)
   :pos (:player/pos world)
   :on-ground? (:player/on-ground? world)
   :loaded? (:player/loaded? world)
   :chunks (count (:world/chunks world))
   :sightings (count (memory/latest world))
   :logs (inventory/logs-held world)
   :inventory (count (:player/inventory world))
   :entities (count (:world/entities world))
   :stats (into {} (filter (fn [[k _]] (= "stats" (namespace k))) world))
   :disconnected (:bot/disconnected world)
   :closed (:bot/closed world)})
