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
            [clojurecraft.bytes :as b]
            [clojurecraft.chunk :as chunk]
            [clojurecraft.dimension :as dimension]
            [clojurecraft.entity :as entity]
            [clojurecraft.inventory :as inventory]
            [clojurecraft.memory :as memory]
            [clojurecraft.packet :as p]
            [clojurecraft.physics :as physics]
            [clojurecraft.terrain :as terrain]))

(set! *warn-on-reflection* true)

(def version
  "The server version the generated tables describe (resources/clojurecraft/version.edn)."
  (edn/read-string (slurp (io/resource "clojurecraft/version.edn"))))

(def protocol-version
  "The protocol number the handshake announces, read from the server's version data."
  (:version/protocol version))

(def max-air 300)                     ; a full breath, in ticks (vanilla's 15 s)
(def air-lost-per-tick 1)             ; while the eye is under water
(def air-gained-per-tick 4)           ; while it is not

(defn init
  "The world before any event: handshake phase, no position, empty inventory, chunks and facts.
   Attributes the bot cannot know yet (:player/pos, :player/entity-id ...) are absent, and so
   are the connection and the name when they are not given: the :start event carries them."
  [{:keys [host port name]}]
  (cond->
    {:bot/phase :handshake
     :bot/effects []
     :bot/sequence 0
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
     :stats/chunks 0}
    name (assoc :bot/name name :bot/uuid (b/offline-uuid name))
    host (assoc :conn/host host)
    port (assoc :conn/port port)))

;; ---------------------------------------------------------------- effects

(defn effect
  "Append one effect map to :bot/effects for the loop to perform."
  [world e]
  (update world :bot/effects conj e))

(defn emit
  "Queue a packet to send; this is the only place the protocol phase advances."
  [world pkt]
  (-> world
      (effect {:effect/kind :send :effect/packet pkt})
      (update :bot/phase p/next-state (:packet/name pkt))))

(defn say
  "Queue a log line; the loop prints it to stderr."
  [world message]
  (effect world {:effect/kind :log :effect/message message}))

;; ---------------------------------------------------------------- queries

(defn eye
  "The player's eye position; :player/pos must be known."
  [world]
  (physics/eye (:player/pos world)))

;; ---------------------------------------------------------------- packets

(defn on-ground-flag
  "The movement packets' flags byte: 1 when on the ground, else 0."
  [world]
  (if (:player/on-ground? world) 1 0))

(defn send-position
  "Emit move-player-pos-rot with the current pos and look, and remember what was sent."
  [world]
  (let [[x y z] (:player/pos world)
        [yaw pitch] (:player/look world)]
    (-> world
        (emit {:packet/name :move-player-pos-rot
               :x x
               :y y
               :z z
               :yaw yaw
               :pitch pitch
               :flags (on-ground-flag world)})
        (assoc :net/sent-pos (:player/pos world)
               :net/sent-look (:player/look world)
               :net/sent-tick (:time/tick world)))))

(defn relative
  "One teleport coordinate: old + new when the flag bit says relative, else new."
  [flags bit old new]
  (if (pos? (bit-and flags bit)) (+ old new) new))

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
        (cond-> (not loaded?)
                (-> (emit {:packet/name :player-loaded})
                    (assoc :player/loaded? true))))))

(defn load-chunk
  "A level-chunk packet: the column the reader thread attached, or decoded here from the wire
   bytes (a replay, the sim), goes into the terrain; one that fails to decode is logged and
   skipped, never thrown."
  [world {:keys [x z] :as pkt}]
  (let [key [x z]
        column (:chunk/column (chunk/attach pkt))]
    (if (:chunk/error column)
      (say world (str "bad chunk " key ": " (:chunk/error column)))
      (-> world
          (terrain/put-column key column)
          (update :stats/chunks inc)))))

(defn move-entity
  "Shift a tracked entity by a delta in 1/4096 blocks; untracked entities are ignored."
  [world eid dx dy dz]
  (if (get-in world [:world/entities eid])
    (update-in world
               [:world/entities eid :entity/pos]
               (fn [[x y z]] [(+ x (/ dx 4096.0)) (+ y (/ dy 4096.0)) (+ z (/ dz 4096.0))]))
    world))

(defmulti on-packet
  "Dispatch on [phase packet-name]; the three synthetic packets dispatch on their name alone."
  {:arglists '([world pkt])}
  (fn [world pkt]
    (let [n (:packet/name pkt)]
      (if (contains? #{:unknown :decode-error :closed} n) n [(:bot/phase world) n]))))

(defmethod on-packet :default [world _] world)

(defmethod on-packet :unknown
  [world pkt]
  (update-in world [:stats/unknown [(:bot/phase world) (:packet/id pkt)]] (fnil inc 0)))

(defmethod on-packet :decode-error [world pkt] (say world (str "decode error " pkt)))
(defmethod on-packet :closed [world pkt] (assoc world :bot/closed (:packet/reason pkt)))

(defmethod on-packet [:login :login-finished] [w _] (emit w {:packet/name :login-acknowledged}))
(defmethod on-packet [:login :login-disconnect] [w p] (assoc w :bot/disconnected (:reason p)))

(defmethod on-packet [:configuration :select-known-packs]
  [w _]
  (emit w {:packet/name :select-known-packs :packs []}))
(defmethod on-packet [:configuration :keep-alive]
  [w p]
  (-> w
      (emit {:packet/name :keep-alive :id (:id p)})
      (update :stats/keep-alives inc)))
(defmethod on-packet [:configuration :ping] [w p] (emit w {:packet/name :pong :id (:id p)}))
(defmethod on-packet [:configuration :finish-configuration]
  [w _]
  (emit w {:packet/name :finish-configuration}))
(defmethod on-packet [:configuration :disconnect]
  [w p]
  (assoc w :bot/disconnected (String. ^bytes (:reason p) "ISO-8859-1")))

(defn change-dimension
  "The world after a respawn into the dimension named dim (\"minecraft:the_nether\"): the
   columns, the block overlay and the entities belonged to the old one, so they go, and the bot
   is loaded again only once the server has placed it (the next teleport sends player-loaded). A
   respawn in the dimension the bot is in (a death) keeps them (dimension/of: the overworld
   before a login named one)."
  [w dim]
  (cond-> (assoc w :player/dimension dim)
    (not= dim (dimension/of w)) (assoc :world/chunks {} :world/blocks {} :player/loaded? false)))

(defmethod on-packet [:play :respawn]
  [w p]
  ;; a new life, or a new dimension: fresh menus, no entities, at rest, lungs full; the teleport
  ;; that follows places it
  (-> w
      inventory/fresh-menus
      (dissoc :player/dead?)
      (change-dimension (:dimension p))
      (assoc :world/entities {}
             :player/vel [0.0 0.0 0.0]
             :player/fall-distance 0.0
             :player/air max-air)))

(defmethod on-packet [:play :login]
  [w p]
  ;; the dimension it joins in: a recording made before login carried it has none
  (-> w
      inventory/fresh-menus
      (assoc :player/entity-id (:entity-id p))
      (cond-> (:dimension p) (assoc :player/dimension (:dimension p)))
      (emit {:packet/name :client-information
             :locale "en_US"
             :view-distance 6
             :chat-mode (p/value :chat-mode :enabled)
             :chat-colors true
             :skin-parts p/all-skin-parts
             :main-hand (p/value :main-hand :right)
             :text-filtering false
             :server-listing true
             :particle-status (p/value :particle-status :all)})))
(defmethod on-packet [:play :keep-alive]
  [w p]
  (-> w
      (emit {:packet/name :keep-alive :id (:id p)})
      (update :stats/keep-alives inc)))
(defmethod on-packet [:play :ping] [w p] (emit w {:packet/name :pong :id (:id p)}))

(def server-tick-ms 50)               ; a server tick at the target rate, 20 a second

(defn clocked
  "The world after a set-time packet: the server sends one every 20 of its ticks, so the time
   since the last one, over 20, is how long its ticks take now (:server/tick-ms, never under
   server-tick-ms, halfway to each new reading). A loaded server's ticks are longer, and what it
   counts in ticks (a dig's progress) takes longer by the clock: race 38665691517 (issues #88,
   #144) ran its server at 130 ms a tick, and digs finished by the bot's clock were left
   standing."
  [w]
  (let [now (:time/now w)
        last-at (:server/time-at w)
        reading (when (and last-at (> now last-at)) (max server-tick-ms (/ (- now last-at) 20.0)))
        old (:server/tick-ms w)]
    (cond-> (assoc w :server/time-at now)
      reading (assoc :server/tick-ms (if old (/ (+ old reading) 2.0) reading)))))

(defmethod on-packet [:play :set-time] [w _] (clocked w))
(defmethod on-packet [:play :player-position] [w p] (apply-teleport w p))
(defmethod on-packet [:play :chunk-batch-finished]
  [w _]
  (emit w {:packet/name :chunk-batch-received :chunks-per-tick 20.0}))
(defmethod on-packet [:play :level-chunk-with-light] [w p] (load-chunk w p))
(defmethod on-packet [:play :forget-level-chunk]
  [w p]
  (update w :world/chunks dissoc (terrain/chunk-key (:pos p))))
(defmethod on-packet [:play :block-update] [w p] (terrain/set-block w (:pos p) (:state p)))
(defmethod on-packet [:play :section-blocks-update] [w p] (terrain/section-update w p))
(defn die
  "Health reached zero: the bot is dead until the server respawns it. Count the death, drop the
   running intent and the last one (a new life plans afresh), stop moving, and ask to respawn."
  [w]
  (-> w
      (assoc :player/dead? true :player/controls {})
      (memory/remember-answer {:answer/kind :death})
      (update :stats/deaths (fnil inc 0))
      (dissoc :plan/intent :plan/last)
      (emit {:packet/name :client-command :action (p/value :client-command :perform-respawn)})))

(defmethod on-packet [:play :set-health]
  [w p]
  ;; the food and saturation ride on the same packet (issue #259: the :eat row reads the food); a
  ;; packet decoded short, a prefix of the spec, has health alone.
  ;; When health drops, record the tick so fight-or-flee triggers at once.
  (let [hurt? (and (:player/health w) (< (double (:health p)) (double (:player/health w))))]
    (cond-> (assoc w :player/health (:health p))
      hurt? (assoc :player/hurt-at (:time/tick w 0))
      (:food p) (assoc :player/food (:food p))
      (:saturation p) (assoc :player/saturation (:saturation p))
      (and (<= (:health p) 0) (not (:player/dead? w))) die)))

(defmethod on-packet [:play :damage-event]
  [w {:keys [entity-id source-cause source-direct]}]
  (if (= entity-id (or (:player/entity-id w) (:player/eid w)))
    (let [attacker (if (pos? (long (or source-direct 0)))
                     (dec (long source-direct))
                     (when (pos? (long (or source-cause 0))) (dec (long source-cause))))]
      (cond-> (assoc w :player/hurt-at (:time/tick w 0))
        attacker (assoc :player/attacker attacker)))
    w))

(defmethod on-packet [:play :container-set-content]
  [w {:keys [window-id state-id items carried]}]
  (let [w (cond-> w
            carried (assoc :window/cursor carried)
            (nil? carried) (dissoc :window/cursor))]
    (cond (zero? window-id)
          (reduce (fn [w [i item]] (inventory/set-window-0-slot w i item))
                  (assoc w :window/state-id state-id)
                  (map-indexed vector items))
          (inventory/open? w window-id)
          (reduce (fn [w [i item]] (inventory/set-open-window-slot w i item))
                  (assoc-in w [:window/open :window/state-id] state-id)
                  (map-indexed vector items))
          :else w)))
(defmethod on-packet [:play :container-set-slot]
  [w {:keys [window-id state-id slot item]}]
  (cond (zero? window-id)
        (-> w
            (assoc :window/state-id state-id)
            (inventory/set-window-0-slot slot item))
        (inventory/open? w window-id)
        (-> w
            (assoc-in [:window/open :window/state-id] state-id)
            (inventory/set-open-window-slot slot item))
        :else w))
(defmethod on-packet [:play :set-held-slot]
  [w {:keys [slot]}]
  (if (inventory/hotbar? slot) (assoc w :player/held-slot slot) w))
(defmethod on-packet [:play :set-cursor-item]
  [w {:keys [item]}]
  (if item (assoc w :window/cursor item) (dissoc w :window/cursor)))
(defmethod on-packet [:play :open-screen]
  [w {:keys [window-id menu-type]}]
  (assoc w :window/open {:window/id window-id :window/menu-type menu-type :window/slots {}}))
(defmethod on-packet [:play :container-close] [w _] (dissoc w :window/open))
(defmethod on-packet [:play :container-set-data]
  [w {:keys [window-id property value]}]
  (if (inventory/open? w window-id) (assoc-in w [:window/open :window/data property] value) w))
(defmethod on-packet [:play :set-player-inventory]
  [w {:keys [slot item]}]
  (inventory/set-slot w slot item))
(defn track
  "w with f applied to the tracked entity eid (and args); an entity the bot does not track (its
   own player, one added before the bot saw it) is left alone."
  [w eid f & args]
  (if (get-in w [:world/entities eid]) (apply update-in w [:world/entities eid] f args) w))

(defmethod on-packet [:play :add-entity]
  ;; every entity, not only items (issue #258): mobs, players, projectiles, an eye of ender; its
  ;; velocity and yaw when the packet carries them (a recording from before the spec grew does not)
  [w {:keys [entity-id type x y z vel yaw]}]
  (assoc-in w
   [:world/entities entity-id]
   (cond-> {:entity/type type :entity/pos [x y z] :entity/seen-at (:time/now w)}
     vel (assoc :entity/vel vel)
     yaw (assoc :entity/yaw (entity/angle yaw)))))
(defn turn
  "w with tracked entity eid's yaw set to deg, when the packet said one (a recording from before
   the spec grew does not)."
  [w eid deg]
  (cond-> w deg (track eid assoc :entity/yaw (double deg))))
(defmethod on-packet [:play :set-entity-motion]
  [w {:keys [entity-id vel]}]
  (track w entity-id assoc :entity/vel vel))
(defmethod on-packet [:play :teleport-entity]
  ;; position and velocity, each axis relative when its Relative bit says so (X 1 Y 2 Z 4, the
  ;; deltas 32 64 128), as the player's own teleport reads them
  [w {:keys [entity-id x y z dx dy dz yaw flags]}]
  (track w
         entity-id
         (fn [e]
           (let [[ox oy oz] (:entity/pos e)
                 [ovx ovy ovz] (:entity/vel e [0.0 0.0 0.0])]
             (cond-> (assoc e
                            :entity/pos
                            [(relative flags 1 ox x) (relative flags 2 oy y)
                             (relative flags 4 oz z)]
                            :entity/vel
                            [(relative flags 32 ovx dx) (relative flags 64 ovy dy)
                             (relative flags 128 ovz dz)])
               yaw (assoc :entity/yaw (double (relative flags 8 (:entity/yaw e 0.0) yaw))))))))
(defmethod on-packet [:play :entity-event]
  ;; status 3 is a living entity's death: a fact before the body is removed; the dragon's is the
  ;; run's win (issue #267)
  [w {:keys [entity-id status]}]
  (let [death? (= entity/death-status status)]
    (cond-> w
      death? (track entity-id assoc :entity/died-at (:time/now w))
      (and death? (entity/dragon? (get-in w [:world/entities entity-id])))
      (assoc :world/dragon-dead? true))))
(defmethod on-packet [:play :set-entity-data]
  ;; the skimmed metadata: shared flags, and a living entity's health (entity/metadata-facts)
  [w {:keys [entity-id metadata]}]
  (track w entity-id (fn [e] (merge e (entity/metadata-facts (:entity/type e) metadata)))))
(defmethod on-packet [:play :move-entity-pos]
  [w {:keys [entity-id dx dy dz]}]
  (move-entity w entity-id dx dy dz))
(defmethod on-packet [:play :move-entity-pos-rot]
  [w {:keys [entity-id dx dy dz yaw]}]
  (-> (move-entity w entity-id dx dy dz)
      (turn entity-id
            (some-> yaw
                    entity/angle))))
(defmethod on-packet [:play :move-entity-rot]
  [w {:keys [entity-id yaw]}]
  (turn w
        entity-id
        (some-> yaw
                entity/angle)))
(defmethod on-packet [:play :entity-position-sync]
  [w {:keys [entity-id x y z yaw]}]
  (if (get-in w [:world/entities entity-id])
    (-> (assoc-in w [:world/entities entity-id :entity/pos] [x y z])
        (turn entity-id yaw))
    w))
(defmethod on-packet [:play :remove-entities]
  ;; the dragon removed near the bot with no boss bar shown is dead (entity/dragon-gone?)
  [w {:keys [ids]}]
  (cond-> (update w :world/entities #(apply dissoc % ids))
    (some #(entity/dragon-gone? w %) ids) (assoc :world/dragon-dead? true)))
(defmethod on-packet [:play :boss-event]
  ;; the bars shown, by id (issue #267): the dragon fight's goes when its dragon dies;
  ;; action 2 updates health (0.0 to 1.0)
  [w {:keys [uuid action health]}]
  (cond (nil? uuid) w
        (= action (p/value :boss-event :add)) (update w :world/boss-bars (fnil conj #{}) uuid)
        (= action (p/value :boss-event :remove))
        (-> w
            (update :world/boss-bars (fnil disj #{}) uuid)
            (update :world/boss-health (fnil dissoc {}) uuid))
        (= action (p/value :boss-event :update-health))
        (cond-> w health (assoc-in [:world/boss-health uuid] health))
        :else w))
(defmethod on-packet [:play :game-event]
  ;; win_game: the bot went through the exit portal, so the dragon is dead; the vanilla client
  ;; shows the credits and then asks to respawn (client-command 0, typecraft bot/game.ts), and so
  ;; does the bot (issue #267). Every other event is unread
  [w {:keys [event]}]
  (cond-> w
    (= event (p/value :game-event :win-game))
    (-> (assoc :world/dragon-dead? true)
        (emit {:packet/name :client-command :action (p/value :client-command :perform-respawn)}))))
(defmethod on-packet [:play :take-item-entity]
  [w {:keys [collected collector count] :as p}]
  (cond-> (update w :stats/pickups (fnil conj []) p)
    (= collector (:player/entity-id w))
    (memory/remember-answer {:answer/kind :pickup :answer/entity collected :answer/count count})))
(defmethod on-packet [:play :block-changed-ack]
  [w p]
  (-> w
      (assoc :stats/last-ack (:sequence p))
      (memory/remember-answer {:answer/kind :ack :answer/sequence (:sequence p)})))
(defmethod on-packet [:play :start-configuration]
  [w _]
  (emit w {:packet/name :configuration-acknowledged}))
(defmethod on-packet [:play :disconnect]
  [w p]
  (assoc w :bot/disconnected (String. ^bytes (:reason p) "ISO-8859-1")))

;; ---------------------------------------------------------------- ticks

(defn physics-ready?
  "May physics run this tick: in play, positioned, loaded, and standing in a loaded chunk."
  [world]
  (and (= :play (:bot/phase world))
       (:player/pos world)
       (:player/loaded? world)
       (terrain/chunk-loaded? world (:player/pos world))))

(defn movement-packets
  "pos-rot when something changed, status-only once a second otherwise (vanilla's rule)."
  [world]
  (cond (or (not= (:player/pos world) (:net/sent-pos world))
            (not= (:player/look world) (:net/sent-look world)))
        (send-position world)
        (>= (- (:time/tick world) (or (:net/sent-tick world) 0)) 20)
        (-> world
            (emit {:packet/name :move-player-status-only :flags (on-ground-flag world)})
            (assoc :net/sent-tick (:time/tick world)))
        :else world))

(defn breathe
  "The air estimate after one tick: down one while the eye is in water, up four otherwise,
   within 0-300. An estimate, since the server does not send the own player's air except as
   entity metadata; the escape goal keys off time under, never off this reaching zero."
  [world]
  (let [air (:player/air world max-air)]
    (assoc world
           :player/air
           (if (:player/eye-in-water? world)
             (max 0 (- air air-lost-per-tick))
             (min max-air (+ air air-gained-per-tick))))))

(defn on-tick
  "Advance the clock; when physics-ready?, move the player under :player/controls, breathe,
   and emit the movement packet vanilla would. A dead player does not move."
  [world {:event/keys [now]}]
  (let [world (-> world
                  (assoc :time/now now)
                  (update :time/tick inc)
                  (terrain/forget-stale now))]
    (if (and (physics-ready? world) (not (:player/dead? world)))
      (let [controls (:player/controls world)]
        (-> (physics/step (terrain/solid-fn world) (terrain/fluid-fn world) world controls)
            breathe
            (cond-> (:control/look controls) (assoc :player/look (:control/look controls)))
            movement-packets))
      world)))

;; ---------------------------------------------------------------- step

(defmulti on-event
  "Dispatch on :event/kind; kinds with no method leave the world unchanged."
  {:arglists '([world event])}
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

(defmethod on-event :start
  [world event]
  (let [world (connection world event)]
    (-> world
        (emit {:packet/name :intention
               :protocol-version protocol-version
               :host (:conn/host world)
               :port (:conn/port world)
               :next-state (p/value :next-state :login)})
        (emit {:packet/name :hello :username (:bot/name world) :uuid (:bot/uuid world)}))))

(defmethod on-event :packet [world {:event/keys [packet]}] (on-packet world packet))
(defmethod on-event :tick [world event] (on-tick world event))
(defmethod on-event :closed [world {:event/keys [reason]}] (assoc world :bot/closed reason))

(defn step
  "The protocol reducer: (step world event) → world'. Pure; the clock and randomness come in
   on the event."
  [world event]
  (on-event world event))

(defn compose
  "Several reducers with the step signature into one, applied left to right."
  [& steps]
  (fn [world event] (reduce (fn [w f] (f w event)) world steps)))

(defn summary
  "The game part of the RESULT line: phase, position, counts and every :stats/* attribute.
   :entities counts the item entities, as it did when they were the only ones tracked: every
   entity is since issue #258, and a recorded run's RESULT must still replay to the one it
   printed."
  [world]
  {:phase (:bot/phase world)
   :pos (:player/pos world)
   :on-ground? (:player/on-ground? world)
   :loaded? (:player/loaded? world)
   :chunks (count (:world/chunks world))
   :sightings (count (memory/latest world))
   :logs (inventory/logs-held world)
   :inventory (count (:player/inventory world))
   :entities (count (filter entity/item? (vals (:world/entities world))))
   :stats (into {} (filter (fn [[k _]] (= "stats" (namespace k))) world))
   :disconnected (:bot/disconnected world)
   :closed (:bot/closed world)})
