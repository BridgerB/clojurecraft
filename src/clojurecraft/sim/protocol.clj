(ns clojurecraft.sim.protocol
  "The model's conversation with the client, and the player in it: login and the chunks it streams, movement, digging and the drops, using an item (use-held), and the player's life (hurt, heal, hunger, drowning, pickup)."
  (:refer-clojure :exclude [send])
  (:require [clojure.math :as math]
            [clojurecraft.blocks :as blocks]
            [clojurecraft.dimension :as dimension]
            [clojurecraft.entity :as entity]
            [clojurecraft.food :as food]
            [clojurecraft.hunger :as hunger]
            [clojurecraft.inventory :as inventory]
            [clojurecraft.mob :as mob]
            [clojurecraft.packet :as packet]
            [clojurecraft.physics :as physics]
            [clojurecraft.recipe :as recipe]
            [clojurecraft.sim.window :as sim-window :refer [full give insert sync-window take-one]]
            [clojurecraft.sim.world :as sim-world :refer
             [add-exhaustion air-regained block-at break-ms bucket-fluids column-key column-of
              disturb drop-rise drown-damage drown-every eye-under? filled-bucket food-value
              held-item held-slot loot max-air max-health on-packet pickup-delay player-eid-of send
              set-cell tell-health wear]]
            [clojurecraft.text :as text]))

(set! *warn-on-reflection* true)

(defn in-view
  "The world's columns the client holds with the player where it stands: every one (the whole
   world, sent once at login and never forgotten: a captured run's world is tens of megabytes,
   one value every run shares), or, with :sim/view-distance r, those within r chunks of the
   player's column (a square, as a server counts it)."
  [sim]
  (let [[cx cz] (column-key (:sim/player-pos sim (:sim/spawn sim)))
        r (:sim/view-distance sim)
        columns (:sim/columns sim)]
    (cond (nil? r) (set (keys columns))
          :else
          (set (for [x (range (- cx r) (inc (+ cx r)))
                     z (range (- cz r) (inc (+ cz r)))
                     :when (contains? columns [x z])]
                 [x z])))))

(defn packed
  "A chunk column [cx cz] as forget-level-chunk packs it: x in the low 32 bits, z in the high
   (terrain/chunk-key reads it back)."
  [[cx cz]]
  (bit-or (bit-and (long cx) 0xFFFFFFFF) (bit-shift-left (long cz) 32)))

(defn stream-from
  "stream's work for a player in column here: send what came into view, forget what left it. Fresh
   columns carry their changed blocks after the captured bytes, before the batch ends."
  [sim here]
  (let [now (in-view sim)
        sent (:sim/view sim)
        gone (sort (remove now sent))
        fresh (sort-by (fn [[x z]] [(+ (abs (- x (first here))) (abs (- z (second here)))) x z])
                       (remove sent now))
        sim
        (reduce (fn [sim k] (send sim {:packet/name :forget-level-chunk :pos (packed k)})) sim gone)
        sim (reduce (fn [sim [x z :as k]]
                      (send sim
                            {:packet/name :level-chunk-with-light
                             :x x
                             :z z
                             :heightmaps []
                             :data (get-in sim [:sim/columns k])
                             ;; decoded already, as the socket's reader thread attaches it: the
                             ;; one column every run of this world shares, so no run decodes it
                             :chunk/column (column-of sim k)}))
                    sim
                    fresh)
        fresh? (set fresh)
        changed (->> (into (:sim/broken sim) (keys (:sim/placed sim)))
                     (filter #(contains? fresh? (column-key %)))
                     sort)
        sim (reduce (fn [sim pos]
                      (send sim {:packet/name :block-update :pos pos :state (block-at sim pos)}))
                    sim
                    changed)]
    (cond-> (assoc sim :sim/view now :sim/view-at here)
      (seq fresh) (send {:packet/name :chunk-batch-finished :batch-size (count fresh)}))))

(defn stream
  "The model as a server keeps a player's chunks: every column that came into view since the last
   look is sent, in one batch, and every one that left it is forgotten, as the player walks, is
   teleported or respawns. Nearest first, so the column under the feet comes before the rest.
   Nothing to do while the player stays in the column it was streamed from (:sim/view-at)."
  [sim]
  (let [here (column-key (:sim/player-pos sim (:sim/spawn sim)))]
    (cond (= here (:sim/view-at sim)) sim
          :else (stream-from sim here))))

(defmethod on-packet :default [sim _] sim)

(defmethod on-packet [:handshake :intention] [sim _] (assoc sim :sim/phase :login))

(defmethod on-packet [:login :hello]
  [sim {:keys [username uuid]}]
  (-> sim
      (assoc :sim/username username)
      (send {:packet/name :login-finished :uuid uuid :username username})))

(defmethod on-packet [:login :login-acknowledged]
  [sim _]
  (-> sim
      (assoc :sim/phase :configuration)
      (send {:packet/name :select-known-packs})))

(defmethod on-packet [:configuration :select-known-packs]
  [sim _]
  (send sim {:packet/name :finish-configuration}))

(defn login
  "The login packet for a player joining in the dimension named dim: the settings a vanilla
   server sends, then the spawn info (the dimension type's registry id and the dimension's name)."
  [dim]
  {:packet/name :login
   :entity-id 1
   :hardcore? false
   :levels [dimension/overworld dimension/nether dimension/end]
   :max-players 20
   :view-distance 6
   :simulation-distance 6
   :reduced-debug? false
   :death-screen? true
   :limited-crafting? false
   :dimension-type (:dimension/id (dimension/by-name dim))
   :dimension dim
   :seed 0
   :game-type (packet/value :game-type :survival)
   :previous-game-type (packet/value :game-type :none)
   :debug? false
   :flat? false})

(defn on-ground-flag?
  "Does a movement packet's flags byte say the player stands on the ground (bit 1)?"
  [flags]
  (pos? (bit-and (long (or flags 0)) 1)))

(defn moved
  "The model after the client reports the player at pos with flags: its position and on-ground
   flag, its chunks streamed, and, with hunger, what the move cost: a jump when it left the
   ground going up (the server's own test: ServerGamePacketListenerImpl.handleMovePlayer calls
   jumpFromGround for it), and a swim for every block moved with the eye under water."
  [sim [_ y _ :as pos] flags]
  (let [[_ py _ :as prev] (:sim/player-pos sim)
        ground? (on-ground-flag? flags)
        jumped? (and prev (:sim/on-ground? sim) (not ground?) (> y py))
        swum (if (and prev (eye-under? sim)) (physics/distance prev pos) 0.0)]
    (-> sim
        (assoc :sim/player-pos pos :sim/on-ground? ground?)
        (cond-> jumped? (add-exhaustion (hunger/exhaustion :jump))
                (pos? swum) (add-exhaustion (* hunger/swim-per-block swum)))
        stream)))

(defmethod on-packet [:play :move-player-pos-rot]
  ;; the look too, which an enderman reads (mob/stared?)
  [sim {:keys [x y z yaw pitch flags]}]
  (moved (assoc sim :sim/player-look [(double yaw) (double pitch)]) [x y z] flags))

(defmethod on-packet [:play :move-player-pos] [sim {:keys [x y z flags]}] (moved sim [x y z] flags))

(defmethod on-packet [:play :move-player-status-only]
  [sim {:keys [flags]}]
  (assoc sim :sim/on-ground? (on-ground-flag? flags)))

(defmethod on-packet [:play :move-player-rot]
  [sim {:keys [yaw pitch flags]}]
  (assoc sim
         :sim/on-ground? (on-ground-flag? flags)
         :sim/player-look [(double yaw) (double pitch)]))

(defmethod on-packet [:play :keep-alive]
  [sim {:keys [id]}]
  (update sim :sim/keep-alives-pending disj id))

(def melting-ice
  "The blocks the game's IceBlock turns to water when a player breaks them (playerDestroy)."
  #{:ice :frosted_ice})

(defn melts?
  "Does breaking block id at pos leave a water source, as the game's IceBlock.playerDestroy does for
   ice broken without silk touch (the bot never holds it) over a block that blocks motion or a
   fluid, outside the Nether, where the water evaporates? Before it, ice broke to air here."
  [sim [x y z] id]
  (let [below (block-at sim [x (dec y) z])]
    (boolean (and (melting-ice (blocks/name-of id))
                  (not (sim-world/ultrawarm? sim))
                  (or (blocks/fluid below)
                      (some-> below
                              blocks/solid?))))))

(defn item-data
  "The set-entity-data that tells the client item entity eid what it holds (stack {:item id :count
   n}), the packet a vanilla server sends with the add-entity of a drop: without it the client does
   not know a cobblestone from a crafting table."
  [eid stack]
  {:packet/name :set-entity-data
   :entity-id eid
   :metadata {entity/item-index {:item (:item stack) :count (:count stack 1)}}})

(defn spawn-item
  "The model with stack {:item id :count n} lying at point [x y z] as an item entity: its own
   entity id, handed over once its pickup delay has passed (pick-up), the client told
   (add-entity, then what it holds: item-data)."
  [sim [x y z :as at] stack]
  (let [eid (:sim/next-eid sim)]
    (-> sim
        (assoc :sim/next-eid (inc eid))
        (assoc-in [:sim/items eid] {:pos at :spawned (:sim/now sim) :item stack})
        (send {:packet/name :add-entity
               :entity-id eid
               :uuid nil
               :type blocks/item-entity-type
               :x x
               :y y
               :z z})
        (send (item-data eid stack)))))

(defn break-block
  "Block id at pos is broken by the player holding held in slot: gone (placed or not), its
   block update, its drop as an item entity, the tool worn, the dig's exhaustion (with hunger),
   the neighbours disturbed; ice over water or a solid becomes a water source (melts?)."
  [sim pos id held slot]
  (let [[x y z] pos
        item-pos [(+ x 0.5) (+ y drop-rise) (+ z 0.5)]
        [sim item] (loot sim id held)
        drop (when item {:item item :count 1})
        before (full (:sim/inv sim) (inventory/layouts :inventory))
        sim (-> sim
                (update :sim/placed dissoc pos) ; a placed block, broken, is gone too
                (update :sim/broken conj pos)
                (send {:packet/name :block-update :pos pos :state blocks/air})
                (update :sim/inv wear slot)
                (add-exhaustion (hunger/exhaustion :mine))
                (disturb pos)
                (cond-> (melts? sim pos id) (set-cell pos blocks/water-base)))
        sim (sync-window sim :inventory before (:sim/cursor sim) false)]
    (cond-> sim drop (spawn-item item-pos drop))))

(defn finish-dig
  "The client says it finished breaking pos: broken, with its drop, when it started breaking
   that block at least the block's break time ago (break-ms), the block update before the ack
   (observed live on 26.1.2); else the real server keeps breaking it by its own clock
   (ServerPlayerGameMode's delayed destroy: :sim/delayed, finished by tick-delayed) and only
   acks now. A FINISH for another block than the one being broken is acked and nothing else.
   A FINISH whose ordinal is in :sim/drop-breaks gets the ack alone, the block left standing
   and nothing said: what a lagging server does with an early FINISH queued behind another one."
  [sim {:keys [pos sequence]}]
  (let [{:keys [at] dpos :pos} (:sim/dig sim)
        id (block-at sim pos)
        held (held-item sim)
        slot (held-slot sim)
        n (:sim/breaks sim 0) ; a snapshot's model from before the counter counts from zero
        sim (update sim :sim/breaks (fnil inc 0))
        ack {:packet/name :block-changed-ack :sequence sequence}]
    (cond (contains? (:sim/drop-breaks sim) n)
          (-> sim
              (dissoc :sim/dig)
              (send ack))
          (and (= dpos pos) id (pos? id) (>= (- (:sim/now sim) at) (break-ms sim id held)))
          (-> sim
              (dissoc :sim/dig)
              (break-block pos id held slot)
              (send ack))
          (and (= dpos pos) id (pos? id))
          (-> sim
              (dissoc :sim/dig)
              (assoc :sim/delayed {:pos pos :at at :held held :slot slot})
              (send ack))
          :else
          (-> sim
              (dissoc :sim/dig)
              (send ack)))))

(defn tick-delayed
  "Finish a delayed destroy (finish-dig) once the block's whole break time has passed by the
   server's clock: the block update, seconds after the ack on a slow server (:slow-breaks)."
  [sim]
  (let [{:keys [pos at held slot]} (:sim/delayed sim)
        id (when pos (block-at sim pos))]
    (cond (nil? pos) sim
          (or (nil? id) (zero? id)) (dissoc sim :sim/delayed)
          (>= (- (:sim/now sim) at) (sim-world/full-ms sim id held))
          (-> sim
              (dissoc :sim/delayed)
              (break-block pos id held slot))
          :else sim)))

(def release-use-item 5)              ; player-action status RELEASE_USE_ITEM: the use key let go (ServerboundPlayerActionPacket.Action)

(def shield-delay-ms 250)             ; ms from a shield's use to its first block (the shield's blocks_attacks block_delay_seconds)
(def using-flags 3)                   ; the living flags byte of a player using its offhand's item: 0x01 using, 0x02 the offhand

(defn tell-using
  "The model having told the client its own living flags (set-entity-data, entity/living-flags-index):
   flags, using-flags while the shield is up, 0 when it is let go."
  [sim flags]
  (send sim
        {:packet/name :set-entity-data
         :entity-id (player-eid-of sim)
         :metadata {entity/living-flags-index flags}}))

(defn offhand-item
  "The item id in the offhand (window-0's [:offhand] slot), or nil."
  [sim]
  (get-in sim [:sim/inv (inventory/slot [:offhand]) :item]))

(defn shield-blocks?
  "Does the player's raised shield block a blow from point src: a shield in the offhand, up
   (:sim/blocking-at) for shield-delay-ms or more, and src in front of the player on the plane (the
   look's yaw, :sim/player-look, within 90 degrees of the way to src: blocks_attacks'
   horizontal_blocking_angle, LivingEntity.resolveBlockedDamage)? A blow from the player's own
   column is not blocked."
  [sim src]
  (let [at (:sim/blocking-at sim)
        [px _ pz] (:sim/player-pos sim)
        [sx _ sz] src
        [yaw _] (:sim/player-look sim)]
    (boolean (when (and at src px yaw (= (recipe/item-id :shield) (offhand-item sim)))
               (let [dx (- (double sx) (double px))
                     dz (- (double sz) (double pz))
                     r (math/to-radians (double yaw))]
                 (and (>= (- (:sim/now sim) at) shield-delay-ms)
                      (pos? (+ (* dx dx) (* dz dz)))
                      (pos? (+ (* dx (- (math/sin r))) (* dz (math/cos r))))))))))

(defn blocked
  "The model after the raised shield took a blow from a mob of kind, how it came (:melee, :blast,
   :arrow, :fireball): counted in :sim/blocked and by [kind how] in :sim/blocked-by, the player not
   hurt."
  [sim kind how]
  (-> sim
      (update :sim/blocked (fnil inc 0))
      (update-in [:sim/blocked-by [kind how]] (fnil inc 0))))

(defmethod on-packet [:play :player-action]
  ;; a release of the use key stops eating and lowers a shield (ServerPlayerGameMode's
  ;; releaseUsingItem)
  [sim {:keys [status pos] :as p}]
  (cond (= status (packet/value :dig :start-destroy))
        (assoc sim :sim/dig {:pos pos :at (:sim/now sim)})
        (= status (packet/value :dig :stop-destroy)) (finish-dig sim p)
        (and (= status release-use-item) (:sim/blocking-at sim))
        (-> sim
            (dissoc :sim/eating :sim/blocking-at)
            (tell-using 0))
        (= status release-use-item) (dissoc sim :sim/eating)
        :else sim))

(def faces
  "A face as the packets number it → the step to the cell beyond it (face-offsets)."
  (into {} (for [[n step] physics/face-offsets] [(packet/value :face n) step])))

(defn overlaps-player?
  "Would a block at [x y z] intersect the player's 0.6×1.8 box at its last reported position?
   nil before the player has moved."
  [sim [x y z]]
  (when-let [[px py pz] (:sim/player-pos sim)]
    (and (< (- px 0.3) (inc x))
         (> (+ px 0.3) x)
         (< py (inc y))
         (> (+ py 1.8) y)
         (< (- pz 0.3) (inc z))
         (> (+ pz 0.3) z))))

(defmethod on-packet [:play :set-carried-item]
  ;; another slot in hand stops any eating, as the game stops using an item it no longer holds
  [sim {:keys [slot]}]
  (-> sim
      (assoc :sim/held slot)
      (dissoc :sim/eating)))

(def respawn
  "The respawn the model sends for a new life: the overworld, survival, nothing before it."
  {:packet/name :respawn
   :dimension-type 0
   :dimension "minecraft:overworld"
   :seed 0
   :game-type (packet/value :game-type :survival)
   :previous-game-type (packet/value :game-type :none)
   :debug? false
   :flat? false})

(defn health-data
  "The set-entity-data that tells the client mob eid's health."
  [eid health]
  {:packet/name :set-entity-data :entity-id eid :metadata {entity/health-index (double health)}})

(defn tell-gear
  "The model having told the client what a mob of kind holds and wears (set-equipment, mob/equipment
   with its slot indexes and item ids), as a server does when it adds the mob; unchanged for a kind
   that shows nothing."
  [sim eid kind]
  (let [gear (mob/equipment kind)]
    (cond-> sim
      (seq gear)
      (send {:packet/name :set-equipment
             :entity-id eid
             :equipment (mapv (fn [[slot item]] [(entity/equipment-slot-ids slot)
                                                 {:item (recipe/item-id item) :count 1}])
                              (sort-by (comp entity/equipment-slot-ids key) gear))}))))

(defn retell-entities
  "The model with every mob and every item lying about told to the client again (add-entity, a mob's
   health after it), as a vanilla server does for a respawned player, whose client starts a new life
   knowing no entity."
  [sim]
  (let [told (reduce (fn [sim [eid {:keys [type pos health]}]]
                       (let [[x y z] pos]
                         (-> sim
                             (send {:packet/name :add-entity
                                    :entity-id eid
                                    :uuid nil
                                    :type (mob/type-id type)
                                    :x x
                                    :y y
                                    :z z})
                             (send (health-data eid health))
                             (tell-gear eid type))))
                     sim
                     (:sim/mobs sim))]
    (reduce (fn [sim [eid {[x y z] :pos stack :item}]]
              (cond-> (send sim
                            {:packet/name :add-entity
                             :entity-id eid
                             :uuid nil
                             :type blocks/item-entity-type
                             :x x
                             :y y
                             :z z})
                stack (send (item-data eid stack))))
            told
            (:sim/items sim))))

(def overworld-keys
  "The model's attributes that are the overworld's while the player is in another dimension."
  [:sim/columns :sim/decoded :sim/placed :sim/broken :sim/view-distance])

(defn leave-overworld
  "The model with the overworld's columns and the blocks placed and broken in it set aside
   (:sim/overworld) for a way back (return-overworld), when the player is in it; unchanged in any
   other dimension, where they are already set aside. A portal or an end portal replaces them with
   the other dimension's."
  [sim]
  (cond-> sim
    (= dimension/overworld (:sim/dimension sim dimension/overworld))
    (assoc :sim/overworld (select-keys sim overworld-keys))))

(defn return-overworld
  "The model with the overworld back as leave-overworld set it aside: its columns, its placed and
   broken blocks, its view distance, and none of the other dimension's items, mobs, fluids or
   falling blocks, which the client dropped. Unchanged with no overworld set aside."
  [sim]
  (if-let [overworld (:sim/overworld sim)]
    (-> (apply dissoc sim :sim/overworld overworld-keys)
        (merge overworld)
        (assoc :sim/items {} :sim/mobs {} :sim/fluids {} :sim/falls {} :sim/falling {})
        (dissoc :sim/spawners :sim/spawners-at))
    sim))

(defmethod on-packet [:play :client-command]
  [sim {:keys [action]}]
  ;; a dead player asking to respawn gets a new life at spawn, full health and breath, a fresh bar
  ;; with hunger, eating nothing, no old container, then the teleport that places it.
  (if (and (= action (packet/value :client-command :perform-respawn)) (<= (:sim/health sim) 0.0))
    (let [[x y z] (:sim/respawn sim)
          here (:sim/dimension sim dimension/overworld)
          stays? (= here (:sim/respawn-in sim))]
      (-> sim
          (cond-> (and (not stays?) (not= dimension/overworld here)) return-overworld
                  (or stays? (not= dimension/overworld here)) (assoc :sim/view #{}))
          (assoc :sim/health max-health
                 :sim/air max-air
                 :sim/player-pos [x y z]
                 :sim/dimension (if stays? here dimension/overworld))
          (dissoc :sim/eating :sim/blocking-at :sim/window :sim/view-at :sim/fire)
          (cond-> (:sim/difficulty sim) (sim-world/with-food-value hunger/fresh))
          (update :sim/respawns inc)
          (send (if stays?
                  (assoc respawn
                         :dimension-type (:dimension/id (dimension/by-name here))
                         :dimension here)
                  respawn))
          retell-entities
          (send {:packet/name :player-position
                 :teleport-id 2
                 :x x
                 :y y
                 :z z
                 :dx 0.0
                 :dy 0.0
                 :dz 0.0
                 :yaw 0.0
                 :pitch 0.0
                 :flags 0})
          stream))
    sim))

(defn swap-held
  "The model with the one held item in the held slot exchanged for item (a bucket filled or
   emptied), the client told what window 0 now holds."
  [sim item]
  (let [slot (held-slot sim)
        held (get-in sim [:sim/inv slot])
        before (full (:sim/inv sim) (inventory/layouts :inventory))
        stack {:item item :count 1}
        inv (if (= 1 (:count held))
              (assoc (:sim/inv sim) slot stack)
              (first (insert (take-one (:sim/inv sim) slot)
                             (inventory/store-slots (inventory/layouts :inventory))
                             stack)))]
    (-> sim
        (assoc :sim/inv inv)
        (sync-window :inventory before (:sim/cursor sim) false))))

(defn eats?
  "Does a use-item with item (an id) held start eating it: food is modelled (:sim/food), the item is
   food (food/nutrition), and the bar is not full or the food is one eaten anyway?"
  [sim item]
  (let [f (:sim/food sim)
        n (recipe/item-name item)]
    (boolean (and f (food/nutrition n) (or (< f food/max-food) (food/always-edible n))))))

(defn held-use
  "What a use-item does with the held item, as use-held dispatches on it: :food (food/nutrition),
   :bucket (an empty or a filled one), :eye (an eye of ender), or :default (nothing the model
   knows)."
  [sim]
  (let [item (held-item sim)]
    (cond (food/nutrition (recipe/item-name item)) :food
          (contains? bucket-fluids item) :bucket
          (= item (recipe/item-id :ender_eye)) :eye
          :else :default)))

(defmulti use-held
  "The model after a use-item with the held item along look [yaw pitch], by what the item is
   (held-use); on-packet then acks it. Anything the model does not know does nothing."
  {:arglists '([sim look])}
  (fn [sim _look] (held-use sim)))

(defmethod use-held :default [sim _] sim)

(defmethod use-held :food
  ;; food held starts eating when the bar may take it (eats?); tick-eating finishes it
  [sim _]
  (let [item (held-item sim)]
    (cond-> sim
      (eats? sim item) (assoc :sim/eating {:slot (held-slot sim) :item item :at (:sim/now sim)}))))

(defmethod use-held :bucket
  ;; a held bucket used along the look: an empty one aimed at a water or lava source fills and the
  ;; source becomes air; a filled one puts its fluid in the cell before the first solid block,
  ;; empties, and water meeting lava turns it to stone
  [sim look]
  (let [fluid (get bucket-fluids (held-item sim))
        src (when (and (:sim/player-pos sim) (nil? fluid)) (sim-world/scooped sim look))
        into (when (and (:sim/player-pos sim) fluid) (sim-world/poured sim look))]
    (cond src
          (let [state (block-at sim src)]
            (-> sim
                (assoc-in [:sim/placed src] blocks/air)
                (disturb src)
                (send {:packet/name :block-update :pos src :state blocks/air})
                (swap-held (filled-bucket state))))
          into
          (-> sim
              (set-cell into fluid)
              (swap-held (get blocks/items :bucket)))
          :else sim)))

(defn use-offhand
  "The model after a use-item with the offhand: a shield there goes up (:sim/blocking-at now,
   the client told its living flags, tell-using); anything else does nothing here."
  [sim]
  (cond (= (recipe/item-id :shield) (offhand-item sim))
        (-> sim
            (assoc :sim/blocking-at (:sim/now sim))
            (tell-using using-flags))
        :else sim))

(defmethod on-packet [:play :use-item]
  ;; the item in the hand named used (use-offhand, or use-held for the main hand), then the ack
  [sim {:keys [hand sequence y-rot x-rot]}]
  (send (if (= hand (packet/value :hand :off-hand)) (use-offhand sim) (use-held sim [y-rot x-rot]))
        {:packet/name :block-changed-ack :sequence sequence}))

(def hit-log-keep 64)                 ; hits the model remembers dealing the player (:sim/hit-log), the newest

(defn hit-logged
  "The hit log (a vector, or nil) with hit {:t :life :cause :by :dealt :health} appended, the
   newest hit-log-keep kept: the model's own cause for each blow, beside the bot's ledger."
  [log hit]
  (let [v (conj (or log []) hit)]
    (cond (> (count v) hit-log-keep) (subvec v (- (count v) hit-log-keep))
          :else v)))

(def hurt-window-ms 500)              ; after a hurt, only a harder one lands, for its excess (LivingEntity's invulnerableTime above 10 of its 20 ticks)

(defn record-death
  "The model with a death by cause (a mob's kind, :on_fire, :lava, :starve, :drown, :scheduled)
   noted in :sim/death-causes and told to the client as player-combat-kill, the player's id and the
   death message component the server sends for it (text/cause-component; how is the blow's own
   kind, :fireball, :arrow or :wither, or nil)."
  [sim cause how]
  (-> sim
      (update :sim/death-causes (fnil conj []) cause)
      (send {:packet/name :player-combat-kill
             :player-id (player-eid-of sim)
             :message (text/cause-component (:sim/username sim "Player") cause how)})))

(defn hurt
  "The model after the player takes amount of damage, exhausting? (a mob's hit, an arrow, a blast:
   hunger/exhaustion :hurt; starving: not): within hurt-window-ms of the last hurt only a harder
   one lands, for what it exceeds the last by (LivingEntity.hurtServer); health never under
   zero. A dead player is not hurt; the client learns its health from sync-health or the
   caller's tell-health. cause (a mob's kind, :starve, :drown) is kept in :sim/death-causes, and
   told as player-combat-kill (how: the blow's kind, :fireball, :arrow, :wither, or nil), when
   this blow is the one that reaches zero. Every blow that lands is also appended to
   :sim/hit-log (hit-logged) with its cause, how, attacker (the mob eid that dealt it, or nil),
   what it dealt and the health left. A blow that exhausts (a mob's hit, a shot, a blast) is
   counted in :sim/struck and by [cause how] (how :melee for a blow with none) in :sim/struck-by,
   beside the ones a shield took (:sim/blocked, :sim/blocked-by)."
  ([sim amount exhausting?] (hurt sim amount exhausting? :unknown nil nil))
  ([sim amount exhausting? cause] (hurt sim amount exhausting? cause nil nil))
  ([sim amount exhausting? cause how] (hurt sim amount exhausting? cause how nil))
  ([sim amount exhausting? cause how attacker]
   (let [{:keys [at last]} (:sim/hurt sim)
         window? (and at (< (- (:sim/now sim) at) hurt-window-ms))
         dealt (if window? (- amount last) amount)]
     (cond (or (<= (:sim/health sim) 0.0) (and window? (<= amount last))) sim
           :else
           (let [health (max 0.0 (- (:sim/health sim) dealt))]
             (-> sim
                 (assoc :sim/health health
                        :sim/hurt {:at (if window? at (:sim/now sim)) :last amount})
                 (update :sim/hit-log
                         hit-logged
                         {:t (:sim/now sim)
                          :life (:sim/respawns sim 0)
                          :cause cause
                          :how how
                          :by attacker
                          :dealt dealt
                          :health health})
                 (cond-> (<= health 0.0) (record-death cause how)
                         exhausting? (add-exhaustion (hunger/exhaustion :hurt))
                         exhausting? (update :sim/struck (fnil inc 0))
                         exhausting?
                         (update-in [:sim/struck-by [cause (or how :melee)]] (fnil inc 0)))))))))

(defn heal
  "The model after a living player heals amount, up to max-health."
  [sim amount]
  (cond-> sim (pos? (:sim/health sim)) (update :sim/health #(min max-health (+ % amount)))))

(def peaceful-heal-every 20)          ; game ticks between peaceful's free hearts (Player.aiStep)

(def peaceful-feed-every 10)          ; game ticks between peaceful's free food points

(defn peaceful-regen
  "On peaceful, a heart back every peaceful-heal-every ticks and a food point every
   peaceful-feed-every while the bar is not full (Player.aiStep); elsewhere nothing."
  [sim]
  (let [t (quot (:sim/now sim) physics/tick-ms)
        peaceful? (= :peaceful (:sim/difficulty sim))]
    (cond-> sim
      (and peaceful? (zero? (mod t peaceful-heal-every))) (heal 1.0)
      (and peaceful? (zero? (mod t peaceful-feed-every)) (< (:sim/food sim) food/max-food))
      (update :sim/food inc))))

(defn feed
  "One game tick of hunger at the model's difficulty (hunger/tick), its heal or starvation hurt
   applied, then peaceful's free regeneration. Nothing without hunger (no :sim/difficulty) or for
   a dead player, who is left for client-command to respawn."
  [sim]
  (let [health (:sim/health sim)]
    (cond (or (nil? (:sim/difficulty sim)) (<= health 0.0)) sim
          :else
          (let [{food' :food healed :heal starved :hurt}
                (hunger/tick (food-value sim) health max-health (:sim/difficulty sim))]
            (-> sim
                (sim-world/with-food-value food')
                (cond-> (pos? healed) (heal healed)
                        (pos? starved) (hurt starved false :starve))
                peaceful-regen)))))

(def use-finished 9)                  ; entity-event status: the player finished using an item (EntityEvent.USE_ITEM_COMPLETE)

(defn ate
  "The model after the player ate the food named n: with hunger the bar and saturation by
   hunger/eat, else the bar up by the food's nutrition, never over full."
  [sim n]
  (cond (:sim/difficulty sim) (sim-world/with-food-value sim (hunger/eat (food-value sim) n))
        :else (update sim :sim/food #(min food/max-food (+ % (food/nutrition n))))))

(defn tick-eating
  "Finish eating once the food's eating time (food/eating-ticks) has passed since its use-item: one
   of it gone from the slot, the food eaten (ate), told as the slot's update, entity-event status
   use-finished to the player, and set-health; counted in :sim/eaten. A slot that no longer holds
   the food ends the eating with nothing eaten. A stew's bowl is not given back."
  [sim]
  (let [{:keys [slot item at]} (:sim/eating sim)
        n (recipe/item-name item)]
    (cond (or (nil? at) (< (- (:sim/now sim) at) (* (food/eating-ticks n) physics/tick-ms))) sim
          (not= item (get-in sim [:sim/inv slot :item])) (dissoc sim :sim/eating)
          :else
          (let [before (full (:sim/inv sim) (inventory/layouts :inventory))
                sim (-> sim
                        (dissoc :sim/eating)
                        (update :sim/eaten (fnil inc 0))
                        (update :sim/inv take-one slot)
                        (ate n))]
            (-> sim
                (sync-window :inventory before (:sim/cursor sim) false)
                (send
                 {:packet/name :entity-event :entity-id (player-eid-of sim) :status use-finished})
                tell-health)))))

(defn kill
  "At :sim/kill-at, once: the player dies, told to the client as set-health; the respawn then is
   client-command's, as after any death."
  [sim]
  (let [at (:sim/kill-at sim)]
    (cond (or (nil? at) (< (:sim/now sim) at)) sim
          :else
          (-> sim
              (cond-> (pos? (:sim/health sim)) (record-death :scheduled nil))
              (assoc :sim/health 0.0)
              (dissoc :sim/kill-at)
              tell-health))))

(defn drown
  "One tick of breath: air falls while the eye is under and refills otherwise; at zero, a hurt
   of drown-damage every drown-every ticks, each told to the client as set-health. A dead
   player (health at zero) is left for client-command to respawn."
  [sim]
  (let [under? (eye-under? sim)
        air (if under? (max 0 (dec (:sim/air sim))) (min max-air (+ (:sim/air sim) air-regained)))
        hurt? (and under?
                   (zero? air)
                   (zero? (mod (quot (:sim/now sim) physics/tick-ms) drown-every))
                   (pos? (:sim/health sim)))
        health (if hurt? (max 0.0 (- (:sim/health sim) drown-damage)) (:sim/health sim))]
    (cond-> (assoc sim :sim/air air :sim/health health)
      (and hurt? (<= health 0.0)) (record-death :drown nil)
      hurt? tell-health)))

(defn keep-alive
  "A keep-alive for the client when one is due, remembered as pending until answered."
  [sim]
  (let [now (:sim/now sim)]
    (cond (< now (:sim/next-keep-alive sim)) sim
          :else
          (-> sim
              (send {:packet/name :keep-alive :id now})
              (update :sim/keep-alives-pending conj now)
              (assoc :sim/next-keep-alive (+ now (:sim/keep-alive-every sim)))))))

(defn pick-up
  "Hand over every item whose pickup delay has passed and that the player stands within reach
   of."
  [sim]
  (let [now (:sim/now sim)]
    (reduce (fn [sim [eid {:keys [pos spawned item]}]]
              (if (and (:sim/player-pos sim)
                       (>= (- now spawned) pickup-delay)
                       (sim-world/within-pickup? (:sim/player-pos sim) pos))
                (let [stack (or item {:item (:oak_log blocks/items) :count 1})]
                  (-> sim
                      (update :sim/items dissoc eid)
                      (send {:packet/name :take-item-entity
                             :collected eid
                             :collector (player-eid-of sim)
                             :count (:count stack)})
                      (send {:packet/name :remove-entities :ids [eid]})
                      (give stack)))
                sim))
            sim
            (:sim/items sim))))
