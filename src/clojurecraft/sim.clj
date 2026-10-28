(ns clojurecraft.sim
  "A pure model of the vanilla server: enough of it to take a bot from handshake to a held log
   with no Java process. (step sim event) → sim', where event is a packet the client sent
   ({:sim/kind :packet :sim/packet p}) or a tick ({:sim/kind :tick :sim/now ms}); packets for
   the client accumulate in :sim/out. Rules modelled: login/configuration handshake, one
   teleport to spawn, one chunk column, keep-alives, block breaking with the vanilla timing
   (an early FINISH is ignored), an item drop at the block, pickup when the player comes
   within the inflated pickup box after the 10-tick delay, window 0 (the player's inventory
   with its 2x2 crafting grid) with vanilla click rules and state ids, block placement, and the
   containers a right-click opens: a crafting table, and a furnace that cooks by the game's
   smelting and fuel tables every tick, open or not. Gravel and sand fall: a block of a falling
   type whose neighbour below changed falls two ticks later if the cell under it is free, and
   lands where the game's gravity brings it.

   Fault knobs are inputs, not hidden state: :sim/drop-clicks is a set of click ordinals
   (0-based) the server silently loses, :sim/drop-breaks a set of FINISH ordinals it answers
   with the ack alone, the block left standing (a lagging server queues an early FINISH
   behind another and drops it), and the loot table's chances (gravel's flint) roll from
   :sim/rng, a pure generator whose start is the :seed input, so a run's luck replays. :sim/violations records anything a real server would
   punish or a careful client must never do: a click on an empty result slot, a close with a
   loaded cursor.

   Two ways to drive it: run folds bot and model together, deterministic and fast (generated
   worlds); connect puts the model on a thread behind a channel pair shaped like conn/open!'s,
   so main's own loop (its clock, its effects, its tap) runs against it with no Java process."
  (:refer-clojure :exclude [send])
  (:require [clojure.core.async :as async]
            [clojure.math :as math]
            [clojurecraft.blocks :as blocks]
            [clojurecraft.chunk :as chunk]
            [clojurecraft.dig :as dig]
            [clojurecraft.dimension :as dimension]
            [clojurecraft.end :as end]
            [clojurecraft.entity :as entity]
            [clojurecraft.fluid :as fluid]
            [clojurecraft.food :as food]
            [clojurecraft.hunger :as hunger]
            [clojurecraft.inventory :as inventory]
            [clojurecraft.memo :as memo]
            [clojurecraft.mob :as mob]
            [clojurecraft.packet :as packet]
            [clojurecraft.physics :as physics]
            [clojurecraft.recipe :as recipe]))

(set! *warn-on-reflection* true)

(def dig-ms 3000)                       ; a log by hand (hardness 2), the fallback for an unknown block
(def break-threshold 0.7)               ; the server breaks once this share of the block's time has passed

(def decode-once
  "A column's bytes → the column: chunk/decode remembered by the bytes' identity (memo/by), for a
   world given as bytes alone (a hand-built fixture column)."
  (memo/by [:identical] 64 chunk/decode))

(defn column-key
  "The chunk column [cx cz] block or position [x y z] stands in."
  [[x _ z]]
  [(bit-shift-right (long (math/floor x)) 4) (bit-shift-right (long (math/floor z)) 4)])

(defn column-of
  "The decoded column k of the world: the one the world came with (:sim/decoded, decoded once
   when the world was read and shared by every run of it), else its bytes decoded."
  [sim k]
  (or (get (:sim/decoded sim) k)
      (some-> (get (:sim/columns sim) k)
              decode-once)))

(defn block-at
  "The server's view of a block: placed blocks, broken positions (air), else the world's column
   (:sim/columns); nil where the world has no column."
  [sim pos]
  #_{:splint/disable [style/prefer-condp]} ; two maps asked about one pos: a condp would hide that
  (cond (contains? (:sim/placed sim) pos) (get-in sim [:sim/placed pos])
        (contains? (:sim/broken sim) pos) 0
        :else
        (let [k (column-key pos)]
          (when-let [column (column-of sim k)] (chunk/block-at {k column} pos)))))

(declare disturb
         full
         sync-window
         stream-from
         set-cell
         ultrawarm?
         eye-under?
         with-mobs
         frame-takes-eye
         perch
         bite
         told)

(def player-eid 1)                    ; the player's entity id, as the model's login gives it
(def mob-salt 0x2545F4914F6CDD1D)     ; the mobs' rolls start from :seed mixed with this, apart from the loot's (mob-roll)
(def wild-salt 0x6A09E667F3BCC909)    ; the Nether's own spawns roll from :seed mixed with this, apart from the mobs' (wild-roll): a Nether run with no warped forest rolls its blazes as before

(defn player-eid-of
  "The player's entity id: the real server's for a model joined to a real bot mid-play
   (:sim/player-eid, joined: the bot counts a pickup only when the collector is its own id), else
   player-eid, the one the model's own login gives."
  [sim]
  (:sim/player-eid sim player-eid))

(defn food-value
  "The player's food as hunger's rules take it ({:food/level :food/saturation :food/exhaustion
   :food/timer}): the bar (:sim/food) and the hunger beside it, a fresh life's where the model
   keeps none."
  [sim]
  {:food/level (:sim/food sim food/max-food)
   :food/saturation (:sim/saturation sim hunger/start-saturation)
   :food/exhaustion (:sim/exhaustion sim 0.0)
   :food/timer (:sim/food-timer sim 0)})

(defn with-food-value
  "The model holding food (a food-value) as its bar, saturation, exhaustion and clock."
  [sim f]
  (assoc sim
         :sim/food (:food/level f)
         :sim/saturation (:food/saturation f)
         :sim/exhaustion (:food/exhaustion f)
         :sim/food-timer (:food/timer f)))

(defn add-exhaustion
  "The model after the player's act cost amount of exhaustion (hunger/exhaust); nothing without
   hunger (a model given no :difficulty)."
  [sim amount]
  (cond-> sim (:sim/difficulty sim) (with-food-value (hunger/exhaust (food-value sim) amount))))

(defn held-slot
  "The window-0 slot of the hotbar place the client last selected (set-carried-item), the
   first when it never selected one."
  [sim]
  (inventory/slot [:hotbar (:sim/held sim 0)]))

(defn held-item
  "The item id in the slot the client last selected (set-carried-item), or nil for an empty
   hand."
  [sim]
  (get-in sim [:sim/inv (held-slot sim) :item]))

(defn full-ms
  "ms the server takes to break block id with the held item by its own clock: the formula the
   client uses (dig/ms, no penalties: the model has no view of water or the ground, which errs
   on the side of accepting a careful client), a log by hand for a block the tables do not
   know, times :sim/slow-breaks (a server whose ticks fall behind; 1 when absent)."
  [sim id held]
  (* (:sim/slow-breaks sim 1) (or (dig/ms id held {}) dig-ms)))

(defn break-ms
  "ms the server requires between START and FINISH on block id with the held item to break it
   at the FINISH: the 70% threshold the real server applies to full-ms."
  [sim id held]
  (* break-threshold (full-ms sim id held)))

(def ^:const roll-bits 53)             ; a double's mantissa: the bits of a roll kept from the generator's 64

(defn roll
  "[sim' r]: the model's next roll, r in [0, 1), from :sim/rng by one 64-bit linear congruential
   step (Knuth's MMIX constants); pure, so the same :seed rolls the same luck."
  [sim]
  (let [s (unchecked-add (unchecked-multiply (long (:sim/rng sim)) 6364136223846793005)
                         1442695040888963407)]
    [(assoc sim :sim/rng s)
     (/ (double (unsigned-bit-shift-right s (- 64 roll-bits))) (math/pow 2 roll-bits))]))

(defn loot
  "[sim' item] for breaking block id with held: the item (an id) its loot table drops, or nil
   when the held item does not harvest the block or nothing drops. The table's tries go in
   order (dig/drop-tries); one of chance 1.0 always drops, one below takes a roll and drops
   when the roll is under its chance (gravel: flint one time in ten, else gravel)."
  [sim id held]
  (if (dig/harvest? id held)
    (reduce (fn [[sim _] [item chance]]
              (if (>= chance 1.0)
                (reduced [sim (get blocks/items item)])
                (let [[sim r] (roll sim)]
                  (if (< r chance) (reduced [sim (get blocks/items item)]) [sim nil]))))
            [sim nil]
            (dig/drop-tries id))
    [sim nil]))

(defn wear
  "The inventory after one accepted dig with the tool in slot: its durability counts down from
   the material's, and the slot empties at zero (the tool broke). Items with no tool are
   untouched."
  [inv slot]
  (let [{:keys [item durability] :as it} (get inv slot)
        tool (blocks/tool item)]
    (cond (nil? tool) inv
          :else
          (let [left (dec (or durability (:durability (blocks/materials (:tier tool)))))]
            (if (pos? left) (assoc inv slot (assoc it :durability left)) (dissoc inv slot))))))
(def pickup-delay 500)                ; ms before a dropped item can be picked up (vanilla: 10 ticks)
(def keep-alive-every 15000)          ; ms between the model's keep-alives (vanilla: 15 s)
(def max-stack 64)                    ; items per slot

(defn stack-size
  "How many of item one slot holds: one for a tool (blocks/tool: the game's tools never stack,
   each wears on its own), max-stack for anything else. With every item stacking to 64, a spare
   pickaxe went into the slot of the one in use, and the dig that broke one emptied the slot of
   both (issue #36: the sim's runs lost their spare at the moment it was needed)."
  [item]
  (if (blocks/tool item) 1 max-stack))
(def max-air 300)                     ; a full breath, as the game's
(def drown-every 20)                  ; ticks between drowning hurts at zero air
(def drown-damage 2.0)                ; health taken by each
(def max-health 20.0)                 ; a full life, the health a spawn and a respawn give
(def air-regained 4)                  ; breath a tick gives back with the eyes out of water
(def drop-rise 0.25)                  ; how far over the bottom of a broken block its drop appears

(defn init
  "A fresh server before the handshake, from {:columns {[cx cz] bytes} :spawn [x y z]} (a world:
   its chunk columns as a real server sent them, a capture of one; or {:column bytes}, the world
   of the one column [0 0]), with optional :decoded {[cx cz] column} (the same columns decoded,
   once, when the world was read, so the runs of one world share them); the whole world goes to
   the client at login and stays, unless the
   optional :view-distance r streams the columns within r chunks as the player moves, as a
   server does. Then the optional
   :keep-alive-every ms, :drop-clicks #{ordinal}, :drop-breaks #{ordinal}, :inventory {slot item}
   (a slot is a named place, [:hotbar 0] as inventory/slot reads it, or a window-0 slot
   number), :lag-ticks,
   :slow-breaks f (blocks take f times their time to break by the server's clock, a loaded
   server's: a FINISH at the client's time is acked but the break comes later, full-ms),
   :respawn [x y z] (where a new life begins; the spawn when absent, as a gym landing may differ
   from the world spawn), :seed (where the loot rolls start, 0 when absent), :tick-seed (where
   a run's tick randomness starts; 0.5 every tick when absent), :kill-at ms (the
   player dies then, a fault as an input: a death mid-task), :dimension (the dimension the
   player joins in, the overworld when absent: a run that starts in the Nether is a gym's tp
   there), :food (the hunger bar the player joins with, told at login; absent, no food is
   modelled and none is told), :nether {:column bytes :at [x y z]} (the Nether a portal leads to: its one column
   and where the player arrives; :columns {[cx cz] bytes} in place of :column, a whole Nether,
   with optional :decoded, the same columns decoded once, :view-distance r, the chunks streamed
   round the player there, and :at, absent for where the server would link the portal: arrive) and :difficulty (:peaceful, :easy, :normal or :hard, as the
   server's setting: hunger drains the bar, a full one when :food is absent, and heals and
   starves by it, issue #260; absent, nothing drains the bar, as before, so a snapshot from then
   plays the same; given, the day turns from :day-time (game ticks, 0 when absent), hostiles
   spawn at night and in the dark, and animals stand on newly loaded chunks), and :mobs
   [{:type kind :pos [x y z]}] (a fixture's mobs, mob/kinds, sent at login and never despawned),
   :stronghold [x y z] (where a thrown eye flies toward; absent, an eye is not thrown) and :end
   {:columns {[cx cz] bytes} :dragon [x y z] :dragon-yaw deg :crystals [[x y z] ...]} (the End
   an end portal leads to: its columns, a void when absent, where the dragon perches, the
   fountain's top when absent, the way it faces, its head harmless when absent, and its
   crystals, one on every spike when absent)."
  [{:keys [column columns decoded view-distance spawn keep-alive-every drop-clicks drop-breaks
           inventory lag-ticks slow-breaks respawn seed tick-seed kill-at dimension nether food
           difficulty day-time mobs stronghold end]
    :or {keep-alive-every keep-alive-every}}]
  (cond->
    {:sim/phase :handshake
     :sim/lag-ticks (or lag-ticks 0)
     :sim/inv (into {} (for [[k it] inventory] [(if (vector? k) (inventory/slot k) k) it]))
     :sim/state-id 1
     :sim/held 0
     :sim/placed {}
     :sim/furnaces {}
     :sim/falls {}
     :sim/falling {}
     :sim/fluids {}
     :sim/fluid-sources-ready? true
     :sim/next-window 1
     :sim/clicks 0
     :sim/drop-clicks (or drop-clicks #{})
     :sim/breaks 0
     :sim/drop-breaks (or drop-breaks #{})
     :sim/rng (or seed 0)
     :sim/violations []
     :sim/keep-alive-every keep-alive-every
     :sim/now 0
     :sim/out []
     :sim/columns (or columns {[0 0] column})
     :sim/view #{}
     :sim/spawn spawn
     :sim/respawn (or respawn spawn)
     :sim/broken #{}
     :sim/air max-air
     :sim/health max-health
     :sim/respawns 0
     :sim/items {}
     :sim/next-eid 100
     :sim/next-keep-alive keep-alive-every
     :sim/keep-alives-pending #{}}
    decoded (assoc :sim/decoded decoded)
    slow-breaks (assoc :sim/slow-breaks slow-breaks)
    tick-seed (assoc :sim/tick-seed tick-seed)
    view-distance (assoc :sim/view-distance view-distance)
    kill-at (assoc :sim/kill-at kill-at)
    dimension (assoc :sim/dimension dimension)
    food (assoc :sim/food food)
    nether (assoc :sim/nether nether)
    difficulty
    (-> (assoc :sim/difficulty difficulty :sim/day-time (or day-time 0))
        (with-food-value (assoc hunger/fresh :food/level (or food food/max-food)))
        (assoc :sim/sent-health [max-health (or food food/max-food) false]))
    (or difficulty (seq mobs)) (assoc :sim/mob-rng (bit-xor (long (or seed 0)) mob-salt))
    difficulty (assoc :sim/wild-rng (bit-xor (long (or seed 0)) wild-salt))
    stronghold (assoc :sim/stronghold stronghold)
    end (assoc :sim/end end)
    (seq mobs)
    (assoc :sim/fixture-mobs (vec mobs)
           :sim/sent-health [max-health (or food food/max-food) false])))

(defn send "Queue pkt for the client in :sim/out." [sim pkt] (update sim :sim/out conj pkt))

(defn joined
  "A server already in play with a player it never saw join (issue #299: a real race's moment,
   real/model): init's world from opts (:columns and :decoded the columns it holds, :spawn,
   :respawn, :seed, :tick-seed, :difficulty, :day-time, :dimension, :food, :inventory as
   window-0 slots), then what the real server held at that moment, given as data in play: the
   time (:now, ms, the bot's clock), the player's :pos, :look, :on-ground?, :held hotbar place,
   window 0's :state-id, :health, :saturation and :air, its entity id (:player-eid), the blocks
   changed since their columns were sent (:placed {pos state}), the items lying about (:items
   {eid {:pos :item}}, ready to pick up) and the mobs (:mobs {eid {:type :pos :health}}), under
   the ids the client tracks them by, and the columns the client holds (:view, every one within
   :view-distance chunks of the player, a square as stream counts it). Nothing is sent: the
   client has it all already, but for the entities it tracks that this model does not carry
   (:gone ids), told removed (remove-entities) with its first tick's packets. Every column the
   model holds is taken as populated: the animals the real server placed are the mobs given, so
   the model adds none. The keep-alive clock starts at :now, and with hunger what it last told
   is what the client knows (sync-health tells only a change)."
  [opts
   {:keys [now pos look on-ground? held state-id health saturation air player-eid placed items mobs
           view view-distance gone]}]
  (let [fresh (init opts)
        here (column-key pos)
        sim (cond->
              (assoc fresh
                     :sim/phase :play
                     :sim/now now
                     :sim/player-pos pos
                     :sim/player-look look
                     :sim/on-ground? (boolean on-ground?)
                     :sim/held held
                     :sim/state-id state-id
                     :sim/health health
                     :sim/air air
                     :sim/placed placed
                     :sim/items (update-vals items #(assoc % :spawned (- now pickup-delay)))
                     :sim/mobs mobs
                     :sim/next-eid
                     (inc (reduce max
                                  (dec (:sim/next-eid fresh))
                                  (concat (keys items) (keys mobs) gone)))
                     :sim/next-keep-alive (+ now (:sim/keep-alive-every fresh))
                     :sim/view view
                     :sim/view-at here
                     :sim/view-distance view-distance
                     :sim/populated (set (keys (:sim/columns fresh)))
                     :sim/populated-at here)
              player-eid (assoc :sim/player-eid player-eid)
              saturation (assoc :sim/saturation saturation))]
    (cond-> sim
      (:sim/difficulty sim) (assoc :sim/sent-health (told sim))
      (seq gone) (send {:packet/name :remove-entities :ids (vec (sort gone))}))))

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

(defn stream
  "The model as a server keeps a player's chunks: every column that came into view since the last
   look is sent, in one batch, and every one that left it is forgotten, as the player walks, is
   teleported or respawns. Nearest first, so the column under the feet comes before the rest.
   Nothing to do while the player stays in the column it was streamed from (:sim/view-at)."
  [sim]
  (let [here (column-key (:sim/player-pos sim (:sim/spawn sim)))]
    (cond (= here (:sim/view-at sim)) sim
          :else (stream-from sim here))))

(defn stream-from
  "stream's work for a player in column here: send what came into view, forget what left it.
   Fresh columns carry their changed blocks after the captured bytes, before the batch ends.
   Sim race 38999010135 (issue #328): the portal dropped the client's overlay, and the original
   column put its dug room back in deepslate, failing the first fortress tunnel :no-stand."
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

(defmulti on-packet
  "The server's answer to one client packet, by [phase packet-name]; unknown packets are
   ignored."
  {:arglists '([sim pkt])}
  (fn [sim pkt] [(:sim/phase sim) (:packet/name pkt)]))
(defmethod on-packet :default [sim _] sim)

(defmethod on-packet [:handshake :intention] [sim _] (assoc sim :sim/phase :login))
(defmethod on-packet [:login :hello]
  [sim {:keys [username uuid]}]
  (send sim {:packet/name :login-finished :uuid uuid :username username}))
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

(defn health
  "The set-health packet that tells the client the player's health and food as the model holds
   them: :sim/food, or a full bar when no food is modelled, and the saturation hunger keeps
   (:sim/saturation, issue #260), the start's 5 when it keeps none."
  [sim]
  {:packet/name :set-health
   :health (:sim/health sim)
   :food (:sim/food sim food/max-food)
   :saturation (:sim/saturation sim hunger/start-saturation)})

(defn told
  "What a set-health tells, as sync-health compares it: the health, the food, and whether the
   saturation is zero (ServerPlayer.doTick's three)."
  [sim]
  (let [{:keys [health food saturation]} (health sim)] [health food (zero? saturation)]))

(defn tell-health
  "The model with its set-health queued for the client (health), what it told kept in
   :sim/sent-health for sync-health."
  [sim]
  (-> sim
      (send (health sim))
      (assoc :sim/sent-health (told sim))))

(defn sync-health
  "The model at the end of a tick, with a set-health when what it tells (told) changed since the
   last one (ServerPlayer.doTick's rule). Only with hunger (:sim/difficulty) or mobs that can hurt
   the player (:sim/mobs): a model with neither tells health only where it always did."
  [sim]
  (cond (or (not (or (:sim/difficulty sim) (seq (:sim/mobs sim))))
            (= (:sim/sent-health sim) (told sim)))
        sim
        :else (tell-health sim)))

(defmethod on-packet [:configuration :finish-configuration]
  [sim _]
  (let [[x y z] (:sim/spawn sim)]
    (-> sim
        (assoc :sim/phase :play)
        (send (login (:sim/dimension sim dimension/overworld)))
        (send {:packet/name :player-position
               :teleport-id 1
               :x x
               :y y
               :z z
               :dx 0.0
               :dy 0.0
               :dz 0.0
               :yaw 0.0
               :pitch 0.0
               :flags 0})
        (send {:packet/name :container-set-content
               :window-id 0
               :state-id (:sim/state-id sim)
               :items (mapv #(get-in sim [:sim/inv %])
                            (range (inventory/slot-count (inventory/layouts :inventory))))
               :carried nil})
        (cond-> (:sim/food sim) tell-health)
        stream
        (with-mobs (:sim/fixture-mobs sim))
        (dissoc :sim/fixture-mobs))))

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
  "Does breaking block id at pos leave a water source, as the game's IceBlock.playerDestroy does
   for ice broken without silk touch (the bot never holds it) over a block that blocks motion or
   a fluid, outside the Nether, where the water evaporates? Before it, ice broke to air here: sim
   race 38806318231 world R2/43 (issue #177, 83 runs) came up under a frozen sea, dug the ice
   over its head from the water, and floated a block under the hole's rim, the ice two over the
   water's top and no bank in the impulse's lift, until its escape timed out three times; on a
   server the hole fills, the water's top is a block higher and the ice round it is the bank."
  [sim [x y z] id]
  (let [below (block-at sim [x (dec y) z])]
    (boolean (and (melting-ice (blocks/name-of id))
                  (not (ultrawarm? sim))
                  (or (blocks/fluid below)
                      (some-> below
                              blocks/solid?))))))

(defn spawn-item
  "The model with stack {:item id :count n} lying at point [x y z] as an item entity: its own
   entity id, handed over once its pickup delay has passed (pick-up), the client told
   (add-entity)."
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
               :z z}))))

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
        n (:sim/breaks sim 0) ; a snapshot's model from before the counter (issue #77's, saved at 7239df5) counts from zero
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
          (>= (- (:sim/now sim) at) (full-ms sim id held))
          (-> sim
              (dissoc :sim/delayed)
              (break-block pos id held slot))
          :else sim)))

(def release-use-item 5)              ; player-action status RELEASE_USE_ITEM: the use key let go (ServerboundPlayerActionPacket.Action)

(defmethod on-packet [:play :player-action]
  ;; a release of the use key stops eating (ServerPlayerGameMode's releaseUsingItem)
  [sim {:keys [status pos] :as p}]
  (cond (= status (packet/value :dig :start-destroy))
        (assoc sim :sim/dig {:pos pos :at (:sim/now sim)})
        (= status (packet/value :dig :stop-destroy)) (finish-dig sim p)
        (= status release-use-item) (dissoc sim :sim/eating)
        :else sim))

;; ---------------------------------------------------------------- windows

(defn result-of
  "What the grid of view crafts, {:item id :count n}, or nil when it matches no recipe."
  [view layout]
  (let [grid (into {}
                   (for [s (inventory/grid-slots layout)
                         :let [it (get view s)]
                         :when it]
                     [s (recipe/item-name (:item it))]))]
    (when-let [r (recipe/match grid (:size layout))]
      {:item (recipe/item-id (:recipe/result r)) :count (:recipe/count r)})))

(defn own-path
  "Where the sim keeps the own slots of the open container of kind: the table's grid on its
   window (it empties when the window closes), a furnace's slots at its position (they stay)."
  [sim kind]
  (case kind
    :table [:sim/window :grid]
    :furnace [:sim/furnaces (get-in sim [:sim/window :pos]) :slots]))

(defn view-of
  "The slots of window kind as the click rules see them, {slot item}, never a crafting result:
   :inventory is window 0; a container is its own slots plus the player's where it shows them
   (inventory/layouts)."
  [sim kind]
  (if (= kind :inventory)
    (:sim/inv sim)
    (let [layout (inventory/layouts kind)]
      (merge (get-in sim (own-path sim kind))
             (into {}
                   (for [[s it] (:sim/inv sim)
                         :let [t (inventory/translate (inventory/layouts :inventory) layout s)]
                         :when t]
                     [t it]))))))

(defn with-view
  "Write a clicked view of window kind back into the sim; the inverse of view-of. A crafting
   result slot is dropped, since it is always derived."
  [sim kind view]
  (let [layout (inventory/layouts kind)
        view (cond-> view (inventory/crafting? layout) (dissoc inventory/result-slot))]
    (if (= kind :inventory)
      (assoc sim :sim/inv view)
      (let [inv (inventory/layouts :inventory)]
        (->
          sim
          (assoc-in (own-path sim kind) (select-keys view (inventory/own-slots layout)))
          (assoc
           :sim/inv
           (merge
            (into {} (remove (fn [[s _]] (inventory/translate inv layout s)) (:sim/inv sim)))
            (into
             {}
             (for [[s it] view :let [w (inventory/translate layout inv s)] :when w] [w it])))))))))

(defn full
  "view with its result slot set to what its grid crafts, when the window crafts."
  [view layout]
  (cond-> view (inventory/crafting? layout) (assoc inventory/result-slot (result-of view layout))))

(defn insert
  "Put a stack into the given slots, stacking first. Returns [view leftover]."
  [view slots {:keys [item count]}]
  (let [order (concat (filter #(= item (:item (get view %))) slots)
                      (filter #(nil? (get view %)) slots))
        [view n] (reduce (fn [[view n] s]
                           (if (zero? n)
                             (reduced [view n])
                             (let [have (:count (get view s) 0)
                                   k (min n (- (stack-size item) have))]
                               [(assoc view s {:item item :count (+ have k)}) (- n k)])))
                         [view count]
                         order)]
    [view (when (pos? n) {:item item :count n})]))

(defn take-one
  "view with one item removed from slot s; the slot empties at zero. s must hold an item."
  [view s]
  (let [{:keys [count] :as it} (get view s)]
    (if (> count 1) (assoc view s (assoc it :count (dec count))) (dissoc view s))))

(defn craft-once
  "view after one craft: one item taken from every occupied grid slot."
  [view layout]
  (reduce (fn [view s] (if (get view s) (take-one view s) view))
          view
          (inventory/grid-slots layout)))

(defn craft-all
  "Shift-click on the result: craft and move results into the store until the grid matches
   nothing or the store is full."
  [view layout]
  (loop [view view]
    (let [out (result-of view layout)
          [view' left] (when out (insert view (inventory/store-slots layout) out))]
      (cond (or (nil? out) left) view
            :else (recur (craft-once view' layout))))))

(defn burns?
  "Does a furnace burn this item name (the game's fuel table)?"
  [n]
  (contains? blocks/fuel-by-name n))

(defn accepts?
  "May a click put item into slot of a window with layout? A furnace's output takes nothing and
   its fuel slot only what burns or an empty bucket (vanilla's FurnaceResultSlot and
   FurnaceFuelSlot); every other slot takes anything."
  [layout slot {:keys [item]}]
  (let [n (recipe/item-name item)]
    (cond (= slot (:output layout)) false
          (= slot (:fuel layout)) (or (burns? n) (= n :bucket))
          :else true)))

(defn take-onto
  "A loaded cursor clicks a slot that refuses it: vanilla moves what fits of the same item from
   the slot onto the cursor (taking from a furnace's output), and does nothing otherwise."
  [view cursor slot]
  (let [at (get view slot)]
    (if (and at (= (:item at) (:item cursor)))
      (let [k (min (:count at) (- (stack-size (:item cursor)) (:count cursor)))
            left (- (:count at) k)]
        {:view (if (pos? left) (assoc view slot (assoc at :count left)) (dissoc view slot))
         :cursor (update cursor :count + k)})
      {:view view :cursor cursor})))

(defn click-kind
  "Which vanilla rule a click in a window with layout falls under: :result (the result slot of a
   window that crafts), :swap (with a hotbar slot), :shift (a quick move), :throw (out of the
   window), :left (the left button) or :right."
  [layout {:keys [slot button mode]}]
  (cond (and (inventory/crafting? layout) (= slot inventory/result-slot)) :result
        (= mode (inventory/click-modes :swap)) :swap
        (= mode (inventory/click-modes :throw)) :throw
        (= mode (inventory/click-modes :quick-move)) :shift
        (= button inventory/left-button) :left
        :else :right))

(defmulti click-view
  "Vanilla click rules over one window's slots, one method per kind of click (click-kind).
   Returns {:view :cursor :violation}."
  {:arglists '([layout view cursor click])}
  (fn [layout _view _cursor click] (click-kind layout click)))

(defmethod click-view :result
  ;; vanilla crafts onto the cursor, or with a shift-click into the store; a click when the grid
  ;; matches nothing is recorded as a violation
  [layout view cursor {:keys [mode]}]
  (let [out (result-of view layout)]
    (cond (nil? out) {:view view :cursor cursor :violation [:click-on-empty-result mode]}
          (= mode (inventory/click-modes :quick-move))
          {:view (craft-all view layout) :cursor cursor}
          (or (nil? cursor)
              (and (= (:item cursor) (:item out))
                   (<= (+ (:count cursor) (:count out)) (stack-size (:item out)))))
          {:view (craft-once view layout) :cursor (update out :count + (:count cursor 0))}
          :else {:view view :cursor cursor})))

(defmethod click-view :swap
  ;; the slot and hotbar slot (hotbar + button) trade places, unless the slot refuses the
  ;; hotbar's item
  [layout view cursor {:keys [slot button]}]
  (let [at (get view slot)
        h (+ (inventory/hotbar-start layout) button)
        other (get view h)]
    (if (and other (not (accepts? layout slot other)))
      {:view view :cursor cursor}
      {:view (-> view
                 (dissoc slot h)
                 (cond-> other (assoc slot other)
                         at (assoc h at)))
       :cursor cursor})))

(defmethod click-view :shift
  ;; a container's own stack (a grid cell, a furnace slot) moves into the store, what does not
  ;; fit stays; anywhere else nothing
  [layout view cursor {:keys [slot]}]
  (let [at (get view slot)]
    (if (and at (some #{slot} (inventory/own-slots layout)))
      (let [[view' left] (insert (dissoc view slot) (inventory/store-slots layout) at)]
        {:view (cond-> view' left (assoc slot left)) :cursor cursor})
      {:view view :cursor cursor})))

(defmethod click-view :throw
  ;; with nothing on the cursor, the stack (or one item of it) leaves the window onto the ground;
  ;; with a loaded cursor nothing happens (vanilla's doClick). The model makes no item entity of
  ;; it, so nothing picks it up again
  [_layout view cursor {:keys [slot button]}]
  (let [at (get view slot)]
    (cond (or cursor (nil? at)) {:view view :cursor cursor}
          (or (= button inventory/whole-stack) (= 1 (:count at)))
          {:view (dissoc view slot) :cursor cursor}
          :else {:view (assoc view slot (update at :count dec)) :cursor cursor})))

(defmethod click-view :left
  ;; pick up, put down, merge up to a full stack (the rest stays on the cursor), or swap; a slot
  ;; that refuses the cursor's item gives what fits of the same item instead
  [layout view cursor {:keys [slot]}]
  (let [at (get view slot)]
    (cond (nil? cursor) {:view (dissoc view slot) :cursor at}
          (not (accepts? layout slot cursor)) (take-onto view cursor slot)
          (nil? at) {:view (assoc view slot cursor) :cursor nil}
          (= (:item at) (:item cursor))
          (let [k (min (:count cursor) (- (stack-size (:item at)) (:count at)))
                left (- (:count cursor) k)]
            {:view (assoc view slot (update at :count + k))
             :cursor (when (pos? left) (assoc cursor :count left))})
          :else {:view (assoc view slot cursor) :cursor at})))

(defmethod click-view :right
  ;; an empty cursor picks up half (rounded up); a loaded one places one into an empty or
  ;; same-item slot that is not full, or takes from a slot that refuses it as a left click does
  [layout view cursor {:keys [slot]}]
  (let [at (get view slot)]
    (cond (and (nil? cursor) at)
          (let [half (long (math/ceil (/ (:count at) 2)))
                rest (- (:count at) half)]
            {:cursor (assoc at :count half)
             :view (if (pos? rest) (assoc view slot (assoc at :count rest)) (dissoc view slot))})
          (nil? cursor) {:view view :cursor nil}
          (not (accepts? layout slot cursor)) (take-onto view cursor slot)
          (or (nil? at) (and (= (:item at) (:item cursor)) (< (:count at) (stack-size (:item at)))))
          {:view (assoc view slot {:item (:item cursor) :count (inc (:count at 0))})
           :cursor (when (> (:count cursor) 1) (update cursor :count dec))}
          :else {:view view :cursor cursor})))

(defn state-id-path
  "Where the sim keeps the state id of window kind: window 0's, or the open container's."
  [kind]
  (if (= kind :inventory) [:sim/state-id] [:sim/window :state-id]))

(defn window-id
  "The protocol window id of window kind: 0, or the open container's."
  [sim kind]
  (if (= kind :inventory) 0 (get-in sim [:sim/window :id])))

(defn sync-window
  "Answer a click the way vanilla does. The click's own changed-slots and cursor fields are the
   client's prediction: the server records them as what the client believes, then sends only
   what differs from that belief. We always predict no changed slots (so every changed slot is
   sent) and an empty cursor (so the cursor is sent only when it is not empty). A stale state
   id gets the full window instead. predicted-cursor is the cursor the client last claimed."
  [sim kind before predicted-cursor stale?]
  (let [layout (inventory/layouts kind)
        slots (range (inventory/slot-count layout))
        after (full (view-of sim kind) layout)
        id (window-id sim kind)
        path (state-id-path kind)]
    (if stale?
      (let [sim (update-in sim path inc)]
        (send sim
              {:packet/name :container-set-content
               :window-id id
               :state-id (get-in sim path)
               :items (mapv #(get after %) slots)
               :carried (:sim/cursor sim)}))
      (as-> sim sim
        (reduce (fn [sim s]
                  (if (= (get before s) (get after s))
                    sim
                    (let [sim (update-in sim path inc)]
                      (send sim
                            {:packet/name :container-set-slot
                             :window-id id
                             :state-id (get-in sim path)
                             :slot s
                             :item (get after s)}))))
                sim
                slots)
        (if (= predicted-cursor (:sim/cursor sim))
          sim
          (send sim {:packet/name :set-cursor-item :item (:sim/cursor sim)}))))))

(defn window-kind
  "Which window a protocol window id names: :inventory, the open container's kind (:table,
   :furnace) when it is that window's id, else nil."
  [sim window-id]
  (cond (zero? window-id) :inventory
        (= window-id (get-in sim [:sim/window :id])) (get-in sim [:sim/window :kind])))

(defmethod on-packet [:play :container-click]
  [sim {:keys [window-id state-id] :as pkt}]
  (let [n (:sim/clicks sim)
        sim (update sim :sim/clicks inc)
        kind (window-kind sim window-id)
        ;; with a container open, the player's own window is not the open menu: vanilla
        ;; ignores the click, and a careful client never sends it
        sim (cond-> sim
              (and (= kind :inventory) (:sim/window sim))
              (update :sim/violations conj [:click-inventory-while-open window-id]))
        kind (when-not (and (= kind :inventory) (:sim/window sim)) kind)]
    (if (or (nil? kind) (contains? (:sim/drop-clicks sim) n))
      sim
      (let [layout (inventory/layouts kind)
            before (full (view-of sim kind) layout)
            {:keys [view cursor violation]}
            (click-view layout (view-of sim kind) (:sim/cursor sim) pkt)
            sim (cond-> (-> sim
                            (with-view kind view)
                            (assoc :sim/cursor cursor))
                  violation (update :sim/violations conj violation))]
        (sync-window sim
                     kind
                     before
                     (:cursor pkt)
                     (not= state-id (get-in sim (state-id-path kind))))))))

(defn close-window
  "The open container closes: a table's grid goes back into the inventory (vanilla), a
   furnace's slots stay in the furnace; window 0 is synced with what came back."
  [sim]
  (let [store (inventory/store-slots (inventory/layouts :inventory))
        before (full (:sim/inv sim) (inventory/layouts :inventory))
        inv (reduce (fn [inv it] (first (insert inv store it)))
                    (:sim/inv sim)
                    (vals (get-in sim [:sim/window :grid])))]
    (-> sim
        (assoc :sim/inv inv)
        (dissoc :sim/window)
        (sync-window :inventory before (:sim/cursor sim) false))))

(defmethod on-packet [:play :container-close]
  [sim {:keys [window-id]}]
  (let [sim (cond-> sim
              (:sim/cursor sim)
              (update :sim/violations conj [:close-with-cursor (:sim/cursor sim)]))]
    (if (= window-id (get-in sim [:sim/window :id])) (close-window sim) sim)))

(defn give
  "An item entity reaches the inventory: stack it into window 0 and tell the client."
  [sim item]
  (let [before (full (:sim/inv sim) (inventory/layouts :inventory))
        [inv _] (insert (:sim/inv sim) (inventory/store-slots (inventory/layouts :inventory)) item)]
    (sync-window (assoc sim :sim/inv inv) :inventory before (:sim/cursor sim) false)))

(defn open-window
  "Open a container window of kind for the client, closing any open one first as vanilla does:
   a fresh window id with state id 1 (extra carries the kind's own keys: a table's empty grid,
   a furnace's position), then open-screen and the whole window with the real cursor."
  [sim kind extra]
  (let [sim (if-let [old (get-in sim [:sim/window :id])]
              (-> sim
                  close-window
                  (send {:packet/name :container-close :window-id old}))
              sim)
        id (:sim/next-window sim)
        layout (inventory/layouts kind)
        sim (-> sim
                (assoc :sim/window (merge {:id id :kind kind :state-id 1} extra))
                (update :sim/next-window inc))
        view (full (view-of sim kind) layout)]
    (-> sim
        (send {:packet/name :open-screen
               :window-id id
               :menu-type (:menu-type layout)
               :title (byte-array 0)})
        (send {:packet/name :container-set-content
               :window-id id
               :state-id 1
               :items (mapv #(get view %) (range (inventory/slot-count layout)))
               :carried (:sim/cursor sim)}))))

;; ---------------------------------------------------------------- the furnace

(def fresh-furnace
  "A furnace nothing has been put in: no slots, unlit, no progress (vanilla's data 0-3)."
  {:slots {} :lit 0 :lit-total 0 :cook 0 :cook-total 0})

(def default-cook-ticks 200)          ; vanilla's cook total when no recipe takes the input

(def smelt-by-input
  "item name → the furnace recipe that takes it (smelting.edn)."
  (into {} (for [r recipe/smelting n (:smelt/input r)] [n r])))

(defn smelt-of
  "The furnace recipe that takes a slot's item, or nil."
  [it]
  (some-> it
          :item
          recipe/item-name
          smelt-by-input))

(def furnace-input (inventory/slot-in :furnace [:input])) ; a furnace's own slots, as its window numbers them
(def furnace-fuel (inventory/slot-in :furnace [:fuel]))
(def furnace-output (inventory/slot-in :furnace [:output]))

(defn furnace-data
  "A furnace's properties as container-set-data sends them, {property-id value}: the fire left
   and lit (:lit, :lit-total), the cooking done and to do (:cook, :cook-total), numbered by the
   furnace layout's :properties."
  [f]
  (into {} (for [[k id] (:properties (inventory/layouts :furnace))] [id (get f k)])))

(defn can-burn?
  "Can the furnace's input cook now: a recipe takes it, and the output is empty or holds the
   result with room for one more batch."
  [{:keys [slots]}]
  (let [r (smelt-of (get slots furnace-input))
        out (get slots furnace-output)]
    (boolean (and r
                  (or (nil? out)
                      (and (= (:item out) (recipe/item-id (:smelt/result r)))
                           (<= (+ (:count out) (:smelt/count r)) max-stack)))))))

(defn smelt-once
  "A furnace's slots after one batch: one input taken, its result added to the output."
  [slots]
  (let [r (smelt-of (get slots furnace-input))
        out (get slots furnace-output)]
    (-> slots
        (take-one furnace-input)
        (assoc furnace-output
               {:item (recipe/item-id (:smelt/result r))
                :count (+ (:count out 0) (:smelt/count r))}))))

(defn reset-on-new-input
  "Vanilla resets the progress, and sets the cook total from the recipe, when the input slot is
   set to a different item; :cooking remembers which item the progress is for."
  [{:keys [slots cooking] :as f}]
  (let [item (:item (get slots furnace-input))]
    (if (= item cooking)
      f
      (cond-> (assoc f
                     :cook 0
                     :cook-total
                     (:smelt/ticks (smelt-of (get slots furnace-input)) default-cook-ticks))
        item (assoc :cooking item)
        (nil? item) (dissoc :cooking)))))

(defn cook
  "One game tick of a furnace, as vanilla's AbstractFurnaceBlockEntity.serverTick: a lit furnace
   burns down one tick; one that is out lights when its input can cook, taking one fuel for
   the fuel's burn ticks (fuel.edn); while lit and able to cook, progress grows, and at the
   cook total one input becomes the result; lit but unable, progress resets; out with no fuel
   or input, progress unwinds two a tick. (A lava bucket's empty bucket is not modelled.)"
  [f]
  (let [f (cond-> (reset-on-new-input f) (pos? (:lit f)) (update :lit dec))
        fuel (get-in f [:slots furnace-fuel])]
    (cond (or (pos? (:lit f)) (and fuel (get-in f [:slots furnace-input])))
          (let [f (if (and (zero? (:lit f)) (can-burn? f))
                    (let [t (get blocks/fuel-by-name (recipe/item-name (:item fuel)) 0)]
                      (-> f
                          (assoc :lit t :lit-total t)
                          (update :slots take-one furnace-fuel)))
                    f)]
            (cond (not (and (pos? (:lit f)) (can-burn? f))) (assoc f :cook 0)
                  (= (inc (:cook f)) (:cook-total f))
                  (-> f
                      (assoc :cook 0)
                      (update :slots smelt-once))
                  :else (update f :cook inc)))
          (pos? (:cook f)) (update f :cook #(max 0 (- % 2)))
          :else f)))

(defn send-data
  "container-set-data for every property of the open window that changed between before and
   after (vanilla sends a property only when it differs from what the client was last told)."
  [sim before after]
  (reduce (fn [sim p]
            (if (= (get before p) (get after p))
              sim
              (send sim
                    {:packet/name :container-set-data
                     :window-id (get-in sim [:sim/window :id])
                     :property p
                     :value (get after p)})))
          sim
          (sort (keys after))))

(defn open-furnace
  "Open the furnace at pos (one is made there if the model has none yet): its window, then
   every property, as vanilla sends a menu's whole state on opening."
  [sim pos]
  (let [sim (update-in sim [:sim/furnaces pos] #(or % fresh-furnace))]
    (-> sim
        (open-window :furnace {:pos pos})
        (send-data {} (furnace-data (get-in sim [:sim/furnaces pos]))))))

(defn tick-furnaces
  "One game tick of every furnace, open or not (a furnace cooks with nobody watching); the one
   whose window is open tells the client what changed: slots with a state id each, then the
   properties."
  [sim]
  (reduce (fn [sim [pos f]]
            (let [open? (= pos (get-in sim [:sim/window :pos]))
                  before (when open? (view-of sim :furnace))
                  f' (cook f)
                  sim (assoc-in sim [:sim/furnaces pos] f')]
              (cond-> sim
                open?
                (-> (sync-window :furnace before (:sim/cursor sim) false)
                    (send-data (furnace-data f) (furnace-data f'))))))
          sim
          (:sim/furnaces sim)))

;; ---------------------------------------------------------------- using items on blocks

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

(defn retell-entities
  "The model with every mob and every item lying about told to the client again (add-entity, a
   mob's health after it), as a vanilla server does for a respawned player, whose client starts
   a new life knowing no entity. Sim race 38900840841 (issue #62): a bot that died and respawned
   never saw the zombie and spider that followed it down the stair, so :defend never fought them
   and it died on the same stair again and again."
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
                             (send (health-data eid health)))))
                     sim
                     (:sim/mobs sim))]
    (reduce (fn [sim [eid {[x y z] :pos}]]
              (send sim
                    {:packet/name :add-entity
                     :entity-id eid
                     :uuid nil
                     :type blocks/item-entity-type
                     :x x
                     :y y
                     :z z}))
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
   falling blocks, which the client dropped. Unchanged with no overworld set aside. Sim race
   39723238841 (issue #504): a death in the Nether respawned the player in the overworld's
   dimension over the Nether's columns, which hold nothing at the spawn, so the bot stood in
   unloaded chunks on stairs-down for hours."
  [sim]
  (if-let [overworld (:sim/overworld sim)]
    (-> (apply dissoc sim :sim/overworld overworld-keys)
        (merge overworld)
        (assoc :sim/items {} :sim/mobs {} :sim/fluids {} :sim/falls {} :sim/falling {})
        (dissoc :sim/spawners :sim/spawners-at))
    sim))

(defmethod on-packet [:play :client-command]
  [sim {:keys [action]}]
  ;; a dead player asking to respawn gets a new life at spawn, full health and breath, a fresh
  ;; bar with hunger (issue #260), eating nothing, no old container, then the teleport that
  ;; places it; issue #323's furnace survived here and refused every window-0 swap after death
  (if (and (= action (packet/value :client-command :perform-respawn)) (<= (:sim/health sim) 0.0))
    (let [[x y z] (:sim/respawn sim)]
      (-> sim
          (cond-> (not= dimension/overworld (:sim/dimension sim dimension/overworld))
                  (-> return-overworld
                      (assoc :sim/view #{})))
          (assoc :sim/health max-health
                 :sim/air max-air
                 :sim/player-pos [x y z]
                 :sim/dimension dimension/overworld)
          (dissoc :sim/eating :sim/window :sim/view-at)
          (cond-> (:sim/difficulty sim) (with-food-value hunger/fresh))
          (update :sim/respawns inc)
          (send respawn)
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

;; ---------------------------------------------------------------- fire and portals (issue #8)

(def portal-ms 4000)                  ; a survival player in a portal this long changes dimension (80 game ticks)
(def nether
  "The respawn the model sends for the Nether: the dimension change a portal makes."
  (assoc respawn
         :dimension-type (:dimension/id (dimension/by-name dimension/nether))
         :dimension dimension/nether))

(defn frame-around
  "The six interior cells of a complete portal frame round cell along axis (:x or :z), or nil: an
   interior two wide and three high, air (or fire) inside, obsidian on its four sides, the
   corners not needed (PortalShape)."
  [sim [x y z] axis]
  (let [along (fn [[a b c] d] (if (= axis :x) [(+ a d) b c] [a b (+ c d)]))
        obsidian? #(= :obsidian (blocks/name-of (block-at sim %)))
        open? #(contains? #{:air :fire} (blocks/name-of (block-at sim %)))]
    (first (for [da [0 -1]
                 dy [0 -1 -2]
                 :let [base (along [x (+ y dy) z] da)
                       inside (for [a [0 1] b [0 1 2]] (update (along base a) 1 + b))
                       frame (concat (for [a [0 1]] (update (along base a) 1 dec))
                                     (for [a [0 1]] (update (along base a) 1 + 3))
                                     (for [b [0 1 2]] (update (along base -1) 1 + b))
                                     (for [b [0 1 2]] (update (along base 2) 1 + b)))]
                 :when (and (every? open? inside) (every? obsidian? frame))]
             inside))))

(defn light
  "use-item-on with flint and steel: fire in the cell the clicked face looks into when it is air;
   fire inside a complete frame becomes the portal instead (BaseFireBlock.onPlace finds the
   PortalShape round it). Then the ack."
  [sim pos face ack]
  (let [dest (mapv + pos (faces face))
        inside (when (= :air (blocks/name-of (block-at sim dest)))
                 (or (some-> (frame-around sim dest :x)
                             (vector (blocks/default-state :nether_portal)))
                     (some-> (frame-around sim dest :z)
                             (vector (inc (blocks/default-state :nether_portal))))))
        fire (blocks/default-state :fire)]
    (cond (nil? (block-at sim dest)) (send sim ack)
          inside
          (let [[cells state] inside]
            (-> (reduce (fn [sim c]
                          (-> sim
                              (assoc-in [:sim/placed c] state)
                              (send {:packet/name :block-update :pos c :state state})))
                        sim
                        cells)
                (send ack)))
          (= :air (blocks/name-of (block-at sim dest)))
          (-> sim
              (assoc-in [:sim/placed dest] fire)
              (send {:packet/name :block-update :pos dest :state fire})
              (send ack))
          :else (send sim ack))))

(def exit-search 16)                  ; blocks round the exit point the server looks for a place to stand (PortalForcer: 16 in the Nether)
(def platform-min-y 70)               ; a forced platform's lowest feet y (PortalForcer.createPortal)
(def platform-below-top 10)           ; and its highest, this under the logical height (118 in the Nether)

(defn exit-point
  "The cell a portal whose player stands at pos [x y z] in the overworld leads to in the Nether:
   x and z scaled by the two dimensions' coordinate scales (1/8), floored, y kept, as
   NetherPortalBlock.getExitPortal scales the entity's position."
  [[x y z]]
  (let [scale (/ (:dimension/coordinate-scale (dimension/by-name dimension/overworld))
                 (:dimension/coordinate-scale (dimension/by-name dimension/nether)))]
    [(long (math/floor (* x scale))) (long (math/floor y)) (long (math/floor (* z scale)))]))

(defn standing?
  "Can a player stand with its feet in cell [x y z] of a world read by at (a cell → its state id,
   nil where nothing is loaded): a solid block under it, and air in it and the cell over it (never
   lava or water)."
  [at [x y z]]
  (let [floor (at [x (dec y) z])
        feet (at [x y z])
        head (at [x (inc y) z])]
    (boolean (and floor feet head (blocks/solid? floor) (blocks/air? feet) (blocks/air? head)))))

(defn portal-space?
  "Can a linked portal's frame fit at feet in either horizontal axis: four solid floor cells
   with four air cells over each, as PortalForcer.canHostFrame checks? A single standing? cell
   is not enough: issue #286's arrival was gravel suspended over the lava sea."
  [at [x y z :as feet]]
  (boolean (and (standing? at feet)
                (some (fn [[dx dz]]
                        (every? (fn [n]
                                  (let [p [(+ x (* n dx)) y (+ z (* n dz))]]
                                    (and (standing? at p)
                                         (every? #(blocks/air? (at (update p 1 + %))) [2 3]))))
                                (range -1 3)))
                      [[1 0] [0 1]]))))

(defn exit-spot
  "The cell a player arrives in through a portal whose exit point is target, in a Nether read by
   at: of the portal-space? cells within exit-search blocks of target across (x and z) whose frames
   are under top (the Nether's logical height, 128: over it is the bedrock roof), the nearest to
   target, by distance squared and then position, so a world and a target give one cell; nil
   when there is none. PortalForcer.createPortal's search, as the server places a new linked
   portal, with room for the frame's four by five, though no frame is built."
  [at [tx _ tz :as target] top]
  (let [d2 (fn [c] (reduce + (map #(let [d (- %1 %2)] (* d d)) c target)))]
    (reduce (fn [best c]
              (cond (not (portal-space? at c)) best
                    (or (nil? best) (neg? (compare (into [(d2 c)] c) (into [(d2 best)] best)))) c
                    :else best))
            nil
            (for [x (range (- tx exit-search) (inc (+ tx exit-search)))
                  z (range (- tz exit-search) (inc (+ tz exit-search)))
                  y (range 1 (- top 3))]
              [x y z]))))

(defn platform
  "Where the server puts a player when no cell near target can be stood in: target with its y
   held between platform-min-y and platform-below-top under top, on an obsidian floor two by
   three with three cells of air over it (PortalForcer.createPortal's forced platform). Returns
   {:feet cell :placed {cell state}}."
  [[tx ty tz] top]
  (let [y (max platform-min-y (min (long ty) (- top platform-below-top)))]
    {:feet [tx y tz]
     :placed (into {}
                   (for [x [(dec tx) tx]
                         z [(dec tz) tz (inc tz)]
                         dy [-1 0 1 2]]
                     [[x (+ y dy) z]
                      (if (neg? dy) (blocks/default-state :obsidian) blocks/air)]))}))

(defn arrival
  "Where a portal at pos leads in a Nether of decoded columns ({[cx cz] column}), a pure function
   of the two: {:at the feet's position :placed {cell state}}, the exit-spot nearest pos's
   exit-point with nothing changed, or, with none, the platform the server builds there."
  [decoded pos]
  (let [top (:dimension/logical-height (dimension/by-name dimension/nether))
        target (exit-point pos)
        spot (exit-spot #(chunk/block-at decoded %) target top)
        {[x y z] :feet placed :placed} (if spot {:feet spot :placed {}} (platform target top))]
    {:at [(+ x 0.5) (double y) (+ z 0.5)] :placed placed}))

(defn arrive
  "The model in the Nether: with a :sim/nether, its column becomes the world (the overworld's
   placed and broken blocks were the other dimension's) and the player stands at its :at; without
   one, the same column where the player stood (a one-column model of the portal alone). A
   :sim/nether with :columns ({[cx cz] bytes}, a real Nether as a real race's recording holds it,
   issue #299, or a race set's captured Nether, issue #353) is that whole Nether instead, decoded
   once on arrival unless it comes :decoded, and the overworld's items, mobs, fluids and falling
   blocks stay behind, as the client drops their entities. With no :at, the player stands where
   the server would place the portal linked to the one it stood in (arrival); with a
   :view-distance, the Nether's columns stream round it as a server's do, never all at once."
  [sim]
  (let [{:keys [column columns decoded at view-distance]} (:sim/nether sim)]
    (cond columns
          (let [decoded (or decoded (update-vals columns chunk/decode))
                landed (if at {:at at :placed {}} (arrival decoded (:sim/player-pos sim)))]
            (-> sim
                (assoc :sim/columns columns
                       :sim/decoded decoded
                       :sim/placed (:placed landed)
                       :sim/broken #{}
                       :sim/items {}
                       :sim/mobs {}
                       :sim/fluids {}
                       :sim/falls {}
                       :sim/falling {}
                       :sim/player-pos (:at landed)
                       :sim/view #{})
                (cond-> view-distance (assoc :sim/view-distance view-distance))
                (dissoc :sim/view-at :sim/spawners :sim/spawners-at)))
          column
          (-> sim
              (assoc :sim/columns {[0 0] column}
                     :sim/placed {}
                     :sim/broken #{}
                     :sim/player-pos at
                     :sim/view #{}) ; a dimension change drops every chunk the client held
              (dissoc :sim/view-at :sim/spawners :sim/spawners-at))
          :else (dissoc sim :sim/view-at))))

(defn through-portal
  "The model a tick on: a player whose feet stand in a nether portal portal-ms on end changes
   dimension (arrive, then the Nether's respawn, a teleport where it now stands and its column);
   out of a portal, the count starts over."
  [sim now]
  (let [pos (:sim/player-pos sim)
        in? (and pos
                 (= :nether_portal
                    (blocks/name-of (block-at sim
                                              (mapv #(long (math/floor %)) (update pos 1 + 0.1))))))
        since (:sim/portal-since sim)]
    (cond (not in?) (dissoc sim :sim/portal-since)
          (= dimension/nether (:sim/dimension sim)) sim
          (nil? since) (assoc sim :sim/portal-since now)
          (< (- now since) portal-ms) sim
          :else
          (let [sim (arrive (leave-overworld sim))
                [x y z] (:sim/player-pos sim)]
            (-> sim
                (assoc :sim/dimension dimension/nether)
                (dissoc :sim/portal-since)
                (send nether)
                (send {:packet/name :player-position
                       :teleport-id 3
                       :x x
                       :y y
                       :z z
                       :dx 0.0
                       :dy 0.0
                       :dz 0.0
                       :yaw 0.0
                       :pitch 0.0
                       :flags 0})
                (assoc :sim/view #{}) ; the client dropped the old dimension's chunks
                stream)))))

(defn place
  "use-item-on that is not on a container: place the held block item at pos + face when the
   destination is one a block may take the place of (blocks/placeable-into?: air, a grass or a
   fluid, as the server's replaceable tag says; a flower refuses it), the clicked block is
   solid and the block would not overlap the player; else tell the client the destination's
   real state. Either way, then the ack. Sim race 38515210080 (issue #44, 198 runs): the first
   op of the portal's pad placed cobblestone into a water cell at the pond's edge, which a real
   server takes and the model refused, three times, :rejected."
  [sim pos face ack]
  (let [dest (mapv + pos (faces face))
        slot (held-slot sim)
        held (get-in sim [:sim/inv slot])
        d (block-at sim dest)
        overlap? (overlaps-player? sim dest)
        placing (some-> held
                        :item
                        recipe/item-name
                        blocks/default-state) ; the held item is a block: it is placed
        ok? (and placing
                 d
                 (blocks/placeable-into? d)
                 (blocks/solid? (or (block-at sim pos) blocks/air))
                 (not overlap?))
        sim (cond-> sim overlap? (update :sim/violations conj [:place-into-player dest]))]
    (if ok?
      (let [before (full (:sim/inv sim) (inventory/layouts :inventory))]
        (-> sim
            (assoc-in [:sim/placed dest] placing)
            (disturb dest)
            (update :sim/inv take-one slot)
            (send {:packet/name :block-update :pos dest :state placing})
            (sync-window :inventory before (:sim/cursor sim) false)
            (send ack)))
      (-> sim
          (send {:packet/name :block-update :pos dest :state (or d blocks/air)})
          (send ack)))))

(defmethod on-packet [:play :use-item-on]
  ;; a right-click on a container opens it (ack first, as with a placement); anything else is
  ;; a placement against the clicked face
  [sim {:keys [pos face sequence]}]
  (let [ack {:packet/name :block-changed-ack :sequence sequence}]
    (case (blocks/name-of (block-at sim pos))
      :crafting_table
      (-> sim
          (send ack)
          (open-window :table {:grid {}}))
      :furnace
      (-> sim
          (send ack)
          (open-furnace pos))
      :end_portal_frame (frame-takes-eye sim pos face ack)
      (if (= (:item (get-in sim [:sim/inv (held-slot sim)])) (get blocks/items :flint_and_steel))
        (light sim pos face ack)
        (place sim pos face ack)))))

(def use-range 4.5)                   ; how far the eyes reach for a block, as the server's interaction range
(def ray-step 0.05)                   ; the length of one step along a ray

(defn ray-cells
  "The cells a ray from the player's eye (its last reported position) along look [yaw pitch]
   passes through within use-range, in order, without repeats."
  [sim look]
  (let [[px py pz] (:sim/player-pos sim)]
    (physics/cells-along [px (+ py 1.62) pz] (physics/direction look) use-range ray-step)))

(def bucket-fluids
  "What each bucket holds, by item id: the empty one nothing, the filled ones the source state they
   pour."
  {(get blocks/items :bucket) nil
   (get blocks/items :water_bucket) blocks/water-base
   (get blocks/items :lava_bucket) blocks/lava-base})

(def filled-bucket
  "The bucket a scooped source state fills an empty one into."
  {blocks/water-base (get blocks/items :water_bucket)
   blocks/lava-base (get blocks/items :lava_bucket)})

(defn scooped
  "The fluid source an empty bucket used along look takes: the first water or lava source on the
   ray (BucketItem with ClipContext.Fluid.SOURCE_ONLY), unless a block with an outline comes
   first (ClipContext.Block.OUTLINE: anything but air and water or lava, a plant as much as
   stone); nil when none."
  [sim look]
  (reduce (fn [_ cell]
            (let [id (block-at sim cell)]
              (cond (contains? filled-bucket id) (reduced cell)
                    (or (nil? id)
                        (blocks/air? id)
                        (contains? blocks/water-states id)
                        (contains? blocks/lava-states id))
                    nil
                    :else (reduced nil))))
          nil
          (ray-cells sim look)))

(defn poured
  "Where a filled bucket used along look puts its fluid: the cell before the first solid block on
   the ray, the ray passing through every fluid (ClipContext.Fluid.NONE: a water pour aimed at the
   floor under a lava source goes through the lava and replaces it), when that cell can take a
   fluid (air, a plant, a fluid); nil when no solid block is in reach."
  [sim look]
  (reduce (fn [before cell]
            (let [id (block-at sim cell)]
              (cond (nil? id) (reduced nil)
                    (blocks/solid? id) (reduced before)
                    :else cell)))
          nil
          (ray-cells sim look)))

(def neighbours
  "The six cells round a cell, as offsets."
  [[0 1 0] [0 -1 0] [1 0 0] [-1 0 0] [0 0 1] [0 0 -1]])

(defn around "The cell and the six round it." [pos] (cons pos (map #(mapv + pos %) neighbours)))

(defn ultrawarm?
  "Is the player's dimension the Nether, where lava runs faster and further?"
  [sim]
  (= dimension/nether (:sim/dimension sim)))

(defn wake-fluids
  "Every fluid cell at or round pos ticks its kind's delay from now (the server schedules a liquid's
   tick on its own placement and on a neighbour's change); an earlier tick already due stays."
  [sim pos]
  (let [at #(block-at sim %)]
    (reduce (fn [sim c]
              (if-let [ms (fluid/delay-ms at c (ultrawarm? sim))]
                (update-in sim [:sim/fluids c] #(min (or % Long/MAX_VALUE) (+ (:sim/now sim) ms)))
                sim))
            sim
            (around pos))))

(defn quench
  "The model after water and lava meet at or round pos: each lava cell there that water touches
   turns to obsidian or cobblestone at once (fluid/mix), the client told and the cells round it
   woken."
  [sim pos]
  (reduce (fn [sim cell]
            (if-let [stone (fluid/mix #(block-at sim %) cell)]
              (-> sim
                  (assoc-in [:sim/placed cell] stone)
                  (send {:packet/name :block-update :pos cell :state stone})
                  (wake-fluids cell))
              sim))
          sim
          (around pos)))

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
  "Does a use-item with item (an id) held start eating it: food is modelled (:sim/food), the item
   is food (food/nutrition), and the bar is not full or the food is one eaten anyway
   (food/always-edible, issue #260)?"
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
        src (when (and (:sim/player-pos sim) (nil? fluid)) (scooped sim look))
        into (when (and (:sim/player-pos sim) fluid) (poured sim look))]
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

(defmethod on-packet [:play :use-item]
  ;; the held item used (use-held), then the ack
  [sim {:keys [sequence y-rot x-rot]}]
  (send (use-held sim [y-rot x-rot]) {:packet/name :block-changed-ack :sequence sequence}))

;; ---------------------------------------------------------------- falling blocks

(def fall-delay-ms 100)               ; a falling block looks under itself 2 game ticks after a neighbour changed (FallingBlock.getDelayAfterPlace)
(def fall-gravity 0.04)               ; blocks per tick a falling block gains each tick (FallingBlockEntity.getDefaultGravity)
(def fall-drag 0.98)                  ; the share of its speed it keeps each tick

(defn free?
  "Is the cell holding state id nothing a falling block rests on (FallingBlock.isFree: air, a
   liquid, fire, a plant)? An unloaded cell is not."
  [id]
  (boolean (and id (not (blocks/solid? id)))))

(defn disturb
  "The block at pos changed, and the neighbour updates the server sends follow: a falling block
   over it looks under itself fall-delay-ms from now; lava that water now touches turns to stone
   at once (quench); every fluid at or round it ticks its delay from now (wake-fluids)."
  [sim [x y z :as pos]]
  (let [above [x (inc y) z]]
    (-> (if (blocks/falls? (block-at sim above))
            (assoc-in sim [:sim/falls above] (+ (:sim/now sim) fall-delay-ms))
            sim)
        (quench pos)
        (wake-fluids pos))))

(defn set-cell
  "The model with the block at pos set to state: the client told, and its neighbours updated
   (disturb)."
  [sim pos state]
  (-> sim
      (assoc-in [:sim/placed pos] state)
      (send {:packet/name :block-update :pos pos :state state})
      (disturb pos)))

;; ---------------------------------------------------------------- fluids

(defn fluid-tick
  "One fluid cell's tick: it becomes what the rules say its neighbours feed it (fluid/next-state,
   air when nothing), then writes what it spreads into the cells round it (fluid/spread); each
   change is set-cell's, so the cells round it are woken in turn. A source held by a plant or
   waterlogged block keeps its block while spreading its water."
  [sim pos]
  (let [at #(block-at sim %)
        id (at pos)
        fs (fluid/state-of id)
        nxt (fluid/next-state at pos (ultrawarm? sim))
        now-id (cond (or (nil? fs) (= nxt fs)) id
                     (nil? nxt) blocks/air
                     :else (fluid/block-state nxt))
        sim (if (= now-id id) sim (set-cell sim pos now-id))]
    (reduce (fn [sim [c state]] (if (= state (block-at sim c)) sim (set-cell sim c state)))
            sim
            (fluid/spread #(block-at sim %) pos (ultrawarm? sim)))))

(defn tick-fluids
  "One game tick of fluids: every cell whose tick is due, in position order so a run replays,
   ticks (fluid-tick). Older saved models first wake fluids beside changed cells: their
   scheduler did not recognize aquatic sources. Sim race 39007350790 (issue #344, R3/4) dug
   beneath tall seagrass, leaving an unscheduled source over a dry shaft until :no-escape."
  [sim]
  (let [sim (if (:sim/fluid-sources-ready? sim)
              sim
              (assoc (reduce wake-fluids sim (into (:sim/broken sim #{}) (keys (:sim/placed sim))))
                     :sim/fluid-sources-ready?
                     true))
        now (:sim/now sim)
        due (sort (keep (fn [[p at]] (when (<= at now) p)) (:sim/fluids sim)))]
    (reduce (fn [sim p] (fluid-tick (update sim :sim/fluids dissoc p) p)) sim due)))

(defn rest-y
  "The lowest height a block falling in column x z, its bottom in cell y, can come down to: the
   bottom of the free cells straight under it."
  [sim x y z]
  (last (cons y (take-while #(free? (block-at sim [x % z])) (iterate dec (dec y))))))

(defn start-fall
  "The block at pos falls when it still falls and the cell under it is free: its cell becomes air
   for the client, the block above it is disturbed in turn, and it is in the air from its own
   cell (FallingBlockEntity.fall), keyed by where it fell from."
  [sim [x y z :as pos]]
  (let [state (block-at sim pos)]
    (if (and (blocks/falls? state) (free? (block-at sim [x (dec y) z])))
      (-> sim
          (assoc-in [:sim/placed pos] blocks/air)
          (send {:packet/name :block-update :pos pos :state blocks/air})
          (assoc-in [:sim/falling pos] {:state state :y (double y) :v 0.0})
          (disturb pos))
      sim)))

(defn land
  "A falling block comes to rest in cell to: placed there when the cell is free (vanilla drops it
   as an item otherwise, which the model does not), and it looks under itself again."
  [sim [x y z :as to] state]
  (if (free? (block-at sim to))
    (-> sim
        (assoc-in [:sim/placed to] state)
        (send {:packet/name :block-update :pos to :state state})
        (disturb [x (dec y) z]))
    sim))

(defn fall-tick
  "One game tick of the block in the air that fell from origin (FallingBlockEntity.tick): it gains
   fall-gravity of speed and moves down, landing on whatever is under it now (a block that
   landed before it included), else keeps fall-drag of its speed."
  [sim [x _ z :as origin] {:keys [state y v] :as f}]
  (let [v (+ v fall-gravity)
        y' (- y v)
        bottom (rest-y sim x (long (math/floor y)) z)]
    (if (<= y' bottom)
      (land (update sim :sim/falling dissoc origin) [x bottom z] state)
      (assoc-in sim [:sim/falling origin] (assoc f :y y' :v (* v fall-drag))))))

(defn tick-falls
  "One game tick of falling blocks: every block whose look is due falls or stays, then every one in
   the air moves, the lowest first; in position order, so a run replays."
  [sim]
  (let [now (:sim/now sim)
        due (sort (keep (fn [[p at]] (when (<= at now) p)) (:sim/falls sim)))
        sim (reduce (fn [sim p] (start-fall (update sim :sim/falls dissoc p) p)) sim due)]
    (reduce (fn [sim [origin f]] (fall-tick sim origin f))
            sim
            (sort-by (comp second key) (:sim/falling sim)))))

(defn within-pickup?
  "Is an item at [ix iy iz] inside the pickup box of a player at [px py pz] (its box inflated
   by 1 horizontally and 0.5 vertically)?"
  [[px py pz] [ix iy iz]]
  (and (< (abs (- px ix)) 1.3) (< (abs (- pz iz)) 1.3) (< -0.5 (- iy py) 2.3)))

(defn eye-under?
  "Is the player's eye (y + 1.62, from the position the client last reported) in a water block
   of the model's column?"
  [sim]
  (when-let [[x y z] (:sim/player-pos sim)]
    (= :water
       (:fluid/kind (blocks/fluid (block-at sim
                                            [(long (math/floor x)) (long (math/floor (+ y 1.62)))
                                             (long (math/floor z))]))))))

(def hurt-window-ms 500)              ; after a hurt, only a harder one lands, for its excess (LivingEntity's invulnerableTime above 10 of its 20 ticks)

(defn hurt
  "The model after the player takes amount of damage, exhausting? (a mob's hit, an arrow, a blast:
   hunger/exhaustion :hurt; starving: not): within hurt-window-ms of the last hurt only a harder
   one lands, for what it exceeds the last by (LivingEntity.hurtServer); health never under
   zero. A dead player is not hurt; the client learns its health from sync-health or the
   caller's tell-health. cause (a mob's kind, :starve, :drown) is kept in :sim/death-causes when
   this blow is the one that reaches zero."
  ([sim amount exhausting?] (hurt sim amount exhausting? :unknown))
  ([sim amount exhausting? cause]
   (let [{:keys [at last]} (:sim/hurt sim)
         window? (and at (< (- (:sim/now sim) at) hurt-window-ms))
         dealt (if window? (- amount last) amount)]
     (cond (or (<= (:sim/health sim) 0.0) (and window? (<= amount last))) sim
           :else
           (let [health (max 0.0 (- (:sim/health sim) dealt))]
             (-> sim
                 (assoc :sim/health health
                        :sim/hurt {:at (if window? at (:sim/now sim)) :last amount})
                 (cond-> (<= health 0.0) (update :sim/death-causes (fnil conj []) cause)
                         exhausting? (add-exhaustion (hunger/exhaustion :hurt)))))))))

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
                (with-food-value food')
                (cond-> (pos? healed) (heal healed)
                        (pos? starved) (hurt starved false :starve))
                peaceful-regen)))))

(def use-finished 9)                  ; entity-event status: the player finished using an item (EntityEvent.USE_ITEM_COMPLETE)

(defn ate
  "The model after the player ate the food named n: with hunger the bar and saturation by
   hunger/eat, else the bar up by the food's nutrition, never over full."
  [sim n]
  (cond (:sim/difficulty sim) (with-food-value sim (hunger/eat (food-value sim) n))
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
              (cond-> (pos? (:sim/health sim)) (update :sim/death-causes (fnil conj []) :scheduled))
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
      (and hurt? (<= health 0.0)) (update :sim/death-causes (fnil conj []) :drown)
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
                       (within-pickup? (:sim/player-pos sim) pos))
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

;; ---------------------------------------------------------------- mobs (issue #260)

(def unswell 7.0)                     ; a lit creeper's fuse goes out with the player farther than this (SwellGoal)
(def populate-radius 3)               ; chunks round the player that have had their animals placed

(defn mob-roll
  "[sim' r]: the next roll of the mobs' own stream, r in [0, 1). Spawns, despawns and drops roll
   from :sim/mob-rng (started at :seed mixed with mob-salt), never from the loot's :sim/rng, so
   gravel's flint falls the same with mobs or without."
  [sim]
  (let [[{r :sim/rng} x] (roll {:sim/rng (:sim/mob-rng sim (bit-xor (:sim/rng sim) mob-salt))})]
    [(assoc sim :sim/mob-rng r) x]))

(defn wild-roll
  "[sim' r]: the next roll of the Nether's own spawns' stream, r in [0, 1), from :sim/wild-rng
   (started at :seed mixed with wild-salt, or from the loot's :sim/rng for a model kept before
   it), never from the mobs' :sim/mob-rng, so a try that finds no warped floor costs the mobs'
   stream nothing and a Nether run that meets no warped forest rolls its fortress's spawners
   as before (issue #263). The endermen it spawns act and despawn by mob-roll, as every mob."
  [sim]
  (let [[{r :sim/rng} x] (roll {:sim/rng (:sim/wild-rng sim (bit-xor (:sim/rng sim) wild-salt))})]
    [(assoc sim :sim/wild-rng r) x]))

(defn overworld?
  "Is the player in the overworld, where the day turns and the night rule spawns?"
  [sim]
  (= dimension/overworld (:sim/dimension sim dimension/overworld)))

(defn column-top
  "The height just over the highest block a column of the player's dimension holds."
  [sim]
  (let [{:dimension/keys [min-y height]} (dimension/by-name (:sim/dimension sim
                                                                            dimension/overworld))]
    (+ min-y height)))

(defn free-fn
  "A cell → may a mob's body be in it: loaded, not solid, not lava (a mob keeps out of lava)."
  [sim]
  (fn [c]
    (let [id (block-at sim c)]
      (boolean (and id (not (blocks/solid? id)) (not (contains? blocks/lava-states id)))))))

(defn solid-fn
  "A cell → does it block a line of sight: solid, or not loaded."
  [sim]
  (fn [c] (let [id (block-at sim c)] (or (nil? id) (blocks/solid? id)))))

(defn yaw-byte
  "The byte add-entity carries yaw degrees in: 1/256 turns, -128 to 127."
  [yaw]
  (let [b (mod (math/round (* yaw (/ 256.0 360.0))) 256)] (if (> b 127) (- b 256) b)))

(defn add-mob
  "The model with a mob of kind standing at pos: its own entity id and the kind's full health,
   the client told (add-entity, then its health). With :persistent? in opts it never despawns
   (a fixture's, a chunk's animals); with :yaw it faces that way, told in the add-entity's
   rotation (the dragon's head, mob/bitten?)."
  [sim kind [x y z :as pos] {:keys [persistent? yaw]}]
  (let [eid (:sim/next-eid sim)
        health (:health (mob/kinds kind))
        add
        {:packet/name :add-entity :entity-id eid :uuid nil :type (mob/type-id kind) :x x :y y :z z}]
    (->
      sim
      (assoc :sim/next-eid (inc eid))
      (assoc-in [:sim/mobs eid]
                (cond-> {:type kind :pos pos :health health}
                  persistent? (assoc :persistent? true)
                  yaw (assoc :yaw (double yaw))))
      (send
       (cond-> add
         yaw
         (assoc :vel [0.0 0.0 0.0] :pitch 0 :yaw (yaw-byte yaw) :head-yaw (yaw-byte yaw) :data 0)))
      (send (health-data eid health)))))

(defn with-mobs
  "The model with each of mobs ({:type kind :pos [x y z]}, as a fixture gives them, init's :mobs)
   standing where it says, persistent."
  [sim mobs]
  (reduce (fn [sim {:keys [type pos]}] (add-mob sim type pos {:persistent? true})) sim mobs))

(defn remove-mob
  "The model without mob eid, the client told (remove-entities)."
  [sim eid]
  (-> sim
      (update :sim/mobs dissoc eid)
      (send {:packet/name :remove-entities :ids [eid]})))

(def max-delta 32767)                 ; the largest move move-entity-pos carries, in 1/4096 blocks

(defn tell-move
  "The model having told the client entity eid moved from one point to another as the server
   tells it: move-entity-pos with the change in 1/4096 blocks between the points as sent (so the
   client's sum is the model's position), entity-position-sync for a move too long for it (a
   fall), nothing for none."
  [sim eid from [x y z :as to]]
  (let [q #(math/round (* 4096.0 (double %)))
        d (mapv #(- (q %2) (q %1)) from to)]
    (cond
      (= [0 0 0] d) sim
      (some #(> (abs %) max-delta) d)
      (send sim {:packet/name :entity-position-sync :entity-id eid :x x :y y :z z})
      :else
      (let [[dx dy dz] d]
        (send
         sim
         {:packet/name :move-entity-pos :entity-id eid :dx dx :dy dy :dz dz :on-ground true})))))

(defn move-mob
  "The model with mob eid at pos, the client told (tell-move)."
  [sim eid pos]
  (-> sim
      (tell-move eid (get-in sim [:sim/mobs eid :pos]) pos)
      (assoc-in [:sim/mobs eid :pos] pos)))

(defn drop-loot
  "The model with the loot of a mob of kind killed at [x y z]: each row's count rolled
   (mob/loot-count), each lying where it fell as an item entity."
  [sim kind [x y z]]
  (reduce (fn [sim row]
            (let [[sim r] (mob-roll sim)
                  n (mob/loot-count row r)]
              (cond-> sim
                (pos? n) (spawn-item [x (+ y drop-rise) z] {:item (mob/loot-item row) :count n}))))
          sim
          (:loot (mob/kinds kind))))

(defn armoured
  "What is left of a mob's hit of amount on the player once the armour it wears in window 0's
   four armour slots takes its share (mob/absorbed over the pieces' summed points and
   toughness, mob/armour-points). Issue #263: with no armour in the model, an enderman's 7 a
   second beat every sword before the fight's 10-health break-off, armour or not."
  [sim amount]
  (let [[points toughness]
        (reduce (fn [[p t] n]
                  (let [[dp dt] (get mob/armour-points
                                     (some->> (get-in sim
                                                      [:sim/inv (inventory/slot [:armour n]) :item])
                                              recipe/item-name)
                                     [0 0.0])]
                    [(+ p dp) (+ t dt)]))
                [0 0.0]
                (range 4))]
    (mob/absorbed amount points toughness)))

(defn blast
  "Mob eid blows up with its kind's power (a creeper's fuse, a crystal hit): it is gone, and the
   player is hurt by the blast (mob/blast-damage by its distance from the mob's feet, scaled by
   the difficulty) when the line from the mob's head to the player's eye is clear. A blast
   breaks no blocks here (not modelled)."
  [sim eid]
  (let [{:keys [type pos]} (get-in sim [:sim/mobs eid])
        player (:sim/player-pos sim)
        dmg
        (if player (mob/blast-damage (:blast (mob/kinds type)) (physics/distance pos player)) 0.0)]
    (-> sim
        (remove-mob eid)
        (cond-> (and (pos? dmg)
                     (mob/sight? (solid-fn sim) (mob/head type pos) (physics/eye player)))
                (hurt (armoured sim (mob/scaled dmg (:sim/difficulty sim))) true type)))))

(defn die-mob
  "Mob eid at zero health: a crystal blasts (blast); any other dies, the client told
   (entity-event entity/death-status), its loot dropped when the player killed it (drop-loot), and it is
   removed once its death is over (act-mob)."
  [sim eid by-player?]
  (let [{:keys [type pos]} (get-in sim [:sim/mobs eid])]
    (cond (:crystal (mob/kinds type)) (blast sim eid)
          :else
          (-> sim
              (assoc-in [:sim/mobs eid :dead-at] (:sim/now sim))
              (send {:packet/name :entity-event :entity-id eid :status entity/death-status})
              (cond-> by-player? (drop-loot type pos))))))

(defn anger
  "The model with mob eid turned on the player (:angry?). An enderman shows it as the server
   does when it takes a target: its creepy flag set (entity/creepy-index, set-entity-data), the
   fact the bot's hunt reads to know one is coming (issue #263). Once only."
  [sim eid]
  (let [{:keys [type angry?]} (get-in sim [:sim/mobs eid])]
    (cond angry? sim
          :else
          (cond-> (assoc-in sim [:sim/mobs eid :angry?] true)
            (= :enderman type)
            (send {:packet/name :set-entity-data
                   :entity-id eid
                   :metadata {entity/creepy-index true}})))))

(defn hurt-mob
  "The model after mob eid takes amount of damage, by-player? when the player dealt it: within
   mob/hurt-window-ms of its last hurt only a harder one lands, for its excess (a swing inside
   the window does nothing, the siblings' 40 swings in 66 ms); its health told (set-entity-data);
   hurt by the player it turns on the player (:angry?); at zero it dies (die-mob)."
  [sim eid amount by-player?]
  (let [{:keys [hit-at last-hit health]} (get-in sim [:sim/mobs eid])
        now (:sim/now sim)
        window? (and hit-at (< (- now hit-at) mob/hurt-window-ms))
        left (max 0.0 (- health (if window? (- amount last-hit) amount)))]
    (cond (and window? (<= amount last-hit)) sim
          :else
          (-> sim
              (update-in [:sim/mobs eid]
                         assoc
                         :health left
                         :hit-at (if window? hit-at now)
                         :last-hit amount)
              (cond-> by-player? (anger eid))
              (send (health-data eid left))
              (cond-> (<= left 0.0) (die-mob eid by-player?))))))

(defn wear-weapon
  "The model after a hit with the held item: a sword wears one, any other tool two (the game's
   weapon and tool components), the client told what window 0 holds; anything else untouched."
  [sim]
  (let [slot (held-slot sim)
        kind (:kind (blocks/tool (held-item sim)))
        before (full (:sim/inv sim) (inventory/layouts :inventory))]
    (cond (nil? kind) sim
          :else
          (-> sim
              (update :sim/inv wear slot)
              (cond-> (not= kind :sword) (update :sim/inv wear slot))
              (sync-window :inventory before (:sim/cursor sim) false)))))

(defmethod on-packet [:play :attack]
  ;; the player's hit (775's attack packet): a living mob whose box is in reach of the eye
  ;; (mob/attack-reach) is hurt by the held item (mob/weapon-damage), the item worn, the hit's
  ;; exhaustion spent; out of reach, dead or unknown, nothing, as the server ignores it
  [sim {:keys [entity-id]}]
  (let [m (get-in sim [:sim/mobs entity-id])
        player (:sim/player-pos sim)]
    (cond (or (nil? m)
              (:dead-at m)
              (nil? player)
              (> (mob/box-distance (physics/eye player) (:type m) (:pos m)) mob/attack-reach))
          sim
          :else
          (let [dmg (mob/weapon-damage (held-item sim))]
            (-> sim
                (add-exhaustion (hunger/exhaustion :attack))
                wear-weapon
                (hurt-mob entity-id dmg true))))))

(defn burn
  "An undead mob in the overworld's daylight with no roof over its head (mob/roofed?) burns:
   mob/burn-damage every mob/burn-every ticks, no loot (the player did not kill it). Only in a
   model that keeps the day (:sim/day-time)."
  [sim eid]
  (let [{:keys [type pos]} (get-in sim [:sim/mobs eid])
        day (:sim/day-time sim)]
    (cond (not (and day
                    (:undead (mob/kinds type))
                    (overworld? sim)
                    (zero? (mod (quot (:sim/now sim) physics/tick-ms) mob/burn-every))
                    (not (mob/night? day))
                    (not (mob/roofed? #(block-at sim %)
                                      (mob/cell-of (mob/head type pos))
                                      (column-top sim)))))
          sim
          :else (hurt-mob sim eid mob/burn-damage false))))

(defn provoke
  "An enderman the player stares at (mob/stared?, from the look the client last reported) turns
   on the player."
  [sim eid]
  (let [{:keys [type pos angry?]} (get-in sim [:sim/mobs eid])
        look (:sim/player-look sim)]
    (cond (or angry? (not (:neutral (mob/kinds type))) (nil? look)) sim
          (mob/stared? (solid-fn sim) (physics/eye (:sim/player-pos sim)) look pos) (anger sim eid)
          :else sim)))

(defn targets?
  "Does mob m have the player as its target: the player in its follow range, and, for a neutral
   kind, only once hurt or stared at (:angry?)?"
  [sim {:keys [type pos angry?]}]
  (let [k (mob/kinds type)]
    (and (<= (physics/distance pos (:sim/player-pos sim)) (:follow k))
         (or (not (:neutral k)) angry?))))

(defn trail
  "Mob eid of a kind that finds its way (:paths: the enderman, zombie, skeleton, creeper and spider) a tick on toward the player,
   stopping stop short of it on the plane: along its route (:route) while it has one, a cell
   dropped once its feet stand on that cell's centre and the route dropped when a step there
   leaves it where it stood; with none, straight (mob/step-toward). A straight step that leaves
   it where it stood with the player out of its reach (mob/in-reach?: a wall, a drop, the player
   above or below) searches a route toward the player (mob/route), at most once every
   mob/route-every ticks (:route-at), walked from the next tick."
  [sim eid stop]
  (let [{:keys [type pos route route-at]} (get-in sim [:sim/mobs eid])
        speed (:speed (mob/kinds type))
        n (mob/tall type)
        free? (free-fn sim)
        player (:sim/player-pos sim)
        now (:sim/now sim)
        [wx wy wz :as w] (first route)]
    (cond w
          (let [centre [(+ wx 0.5) (double wy) (+ wz 0.5)]
                to (mob/step-toward free? pos centre speed 0.0 n)
                left (cond (and (= w (mob/cell-of to)) (< (mob/flat-distance to centre) 1e-3))
                           (subvec route 1)
                           (= to pos) []
                           :else route)]
            (cond-> (move-mob sim eid to)
              (seq left) (assoc-in [:sim/mobs eid :route] left)
              (empty? left) (update-in [:sim/mobs eid] dissoc :route)))
          :else
          (let [to (mob/step-toward free? pos player speed stop n)
                stuck? (and (= to pos)
                            (not (mob/in-reach? type pos player))
                            (>= now (+ (or route-at 0) (* mob/route-every physics/tick-ms))))
                found (when stuck? (mob/route free? (mob/cell-of pos) player n))]
            (cond-> (move-mob sim eid to)
              stuck? (assoc-in [:sim/mobs eid :route-at] now)
              (seq found) (assoc-in [:sim/mobs eid :route] found))))))

(defn walk
  "Mob eid a tick on toward the player (mob/step-toward), stopping where its kind fights from: a
   melee mob inside its reach, a shooter at :keep, a creeper a block off; one that does not walk
   (a blaze hovers) stays; one that finds its way (:paths) walks round what stops it (trail)."
  [sim eid]
  (let [{:keys [type pos]} (get-in sim [:sim/mobs eid])
        k (mob/kinds type)
        stop (cond (:melee k) (* 0.8 (:reach k))
                   (:shoot k) (:keep k)
                   :else 1.0)]
    (cond
      (not (pos? (:speed k 0.0))) sim
      (:paths k) (trail sim eid stop)
      :else
      (move-mob
       sim
       eid
       (mob/step-toward (free-fn sim) pos (:sim/player-pos sim) (:speed k) stop (mob/tall type))))))

(defn teleport-rolls
  "[sim' rs]: the next n rolls of the mobs' own stream (mob-roll), in order."
  [sim n]
  (reduce (fn [[sim rs] _] (let [[sim r] (mob-roll sim)] [sim (conj rs r)])) [sim []] (range n)))

(defn teleport-mob
  "[sim' landed?]: mob eid where its teleport aimed at point lands (mob/landing over the
   dimension's height, a cell over its top air), its route dropped, the client told (tell-move:
   a jump this long goes as entity-position-sync), and true; where it lands nowhere, the model
   as it was and false."
  [sim eid point]
  (let [top (column-top sim)
        at #(if (>= (second %) top) 0 (block-at sim %))
        min-y (:dimension/min-y (dimension/by-name (:sim/dimension sim dimension/overworld)))
        to (mob/landing at point min-y)]
    (cond (nil? to) [sim false]
          :else
          [(-> sim
               (move-mob eid to)
               (update-in [:sim/mobs eid] dissoc :route)) true])))

(defn blink
  "Mob eid's teleports at the player it has as its target, for a kind that teleports
   (:teleports, the enderman; EndermanLookForPlayerGoal.tick): stared at (mob/stared?) its far
   count (:far-ticks) goes to zero, and within mob/teleport-near it teleports away (mob/away-aim,
   EnderMan.teleport); not stared at and farther than mob/teleport-far, the count grows a tick,
   and from mob/teleport-wait on it teleports toward the player (mob/toward-aim,
   EnderMan.teleportTowards), the count back to zero where it lands and kept where it does not,
   so it tries again the next tick; nearer, the count stays. Every aim takes three rolls of
   mob-roll; any other kind, the model untouched."
  [sim eid]
  (let [{:keys [type pos far-ticks]} (get-in sim [:sim/mobs eid])
        player (:sim/player-pos sim)
        look (:sim/player-look sim)
        far (or far-ticks 0)
        stared? (and look
                     (:teleports (mob/kinds type))
                     (mob/stared? (solid-fn sim) (physics/eye player) look pos))
        d (physics/distance pos player)]
    (cond (not (:teleports (mob/kinds type))) sim
          (and stared? (< d mob/teleport-near))
          (let [[sim [r1 r2 r3]] (teleport-rolls (assoc-in sim [:sim/mobs eid :far-ticks] 0) 3)]
            (first (teleport-mob sim eid (mob/away-aim pos r1 r2 r3))))
          stared? (assoc-in sim [:sim/mobs eid :far-ticks] 0)
          (<= d mob/teleport-far) sim
          (< far mob/teleport-wait) (assoc-in sim [:sim/mobs eid :far-ticks] (inc far))
          :else
          (let [[sim [r1 r2 r3]] (teleport-rolls sim 3)
                [sim landed?]
                (teleport-mob sim eid (mob/toward-aim pos (physics/eye player) r1 r2 r3))]
            (cond-> sim landed? (assoc-in [:sim/mobs eid :far-ticks] 0))))))

(defn strike
  "A melee mob with the player in reach (on the plane within its :reach, feet within 1.5 in
   height) hits it every :every ticks for its damage scaled by the difficulty."
  [sim eid]
  (let [{:keys [type pos attack-at]} (get-in sim [:sim/mobs eid])
        k (mob/kinds type)
        player (:sim/player-pos sim)
        now (:sim/now sim)]
    (cond (not (and (:melee k) (mob/in-reach? type pos player) (>= now (or attack-at 0)))) sim
          :else
          (-> sim
              (assoc-in [:sim/mobs eid :attack-at] (+ now (* (:every k) physics/tick-ms)))
              (hurt (armoured sim (mob/scaled (:melee k) (:sim/difficulty sim))) true type)))))

(defn shoot
  "A shooter (a skeleton's arrow, a blaze's fireball) within its :range of the player, with a
   clear line from its head to the player's eye, hits every :every ticks for its damage scaled by
   the difficulty; the shot's flight is not modelled, it lands as it is fired."
  [sim eid]
  (let [{:keys [type pos attack-at]} (get-in sim [:sim/mobs eid])
        k (mob/kinds type)
        eye (physics/eye (:sim/player-pos sim))
        now (:sim/now sim)]
    (cond (not (and (:shoot k)
                    (>= now (or attack-at 0))
                    (<= (physics/distance (mob/head type pos) eye) (:range k))
                    (mob/sight? (solid-fn sim) (mob/head type pos) eye)))
          sim
          :else
          (-> sim
              (assoc-in [:sim/mobs eid :attack-at] (+ now (* (:every k) physics/tick-ms)))
              (hurt (armoured sim (mob/scaled (:shoot k) (:sim/difficulty sim))) true type)))))

(defn swell
  "A creeper within :swell of the player lights its fuse; with the player past unswell the fuse
   goes out; :fuse ticks after it was lit, it blasts (blast)."
  [sim eid]
  (let [{:keys [type pos fuse-at]} (get-in sim [:sim/mobs eid])
        k (mob/kinds type)
        d (physics/distance pos (:sim/player-pos sim))
        now (:sim/now sim)]
    (cond (not (:fuse k)) sim
          (and fuse-at (> d unswell)) (update-in sim [:sim/mobs eid] dissoc :fuse-at)
          (and fuse-at (>= (- now fuse-at) (* (:fuse k) physics/tick-ms))) (blast sim eid)
          (and (nil? fuse-at) (<= d (:swell k))) (assoc-in sim [:sim/mobs eid :fuse-at] now)
          :else sim)))

(defn chase
  "A hostile's turn at a living player: an enderman stared at turns (provoke); with the player as
   its target (targets?) it teleports by its kind (blink), walks, then hits, shoots or swells by
   its kind."
  [sim eid]
  (let [player (:sim/player-pos sim)
        sim (if player (provoke sim eid) sim)
        m (get-in sim [:sim/mobs eid])]
    (cond (or (nil? player) (<= (:sim/health sim) 0.0) (not (targets? sim m))) sim
          :else
          (reduce (fn [sim f] (if (get-in sim [:sim/mobs eid]) (f sim eid) sim))
                  sim
                  [blink walk strike shoot swell]))))

(defn act-mob
  "One game tick of mob eid: a dead one is removed once its death is over (mob/death-ticks, the
   dragon's mob/dragon-death-ticks); the perched dragon heals from its crystals (perch); an
   animal and a crystal stand; any other
   burns in daylight when undead (burn) and goes after the player (chase)."
  [sim eid]
  (let [{:keys [type dead-at] :as m} (get-in sim [:sim/mobs eid])
        k (mob/kinds type)
        over (* physics/tick-ms (if (= :ender_dragon type) mob/dragon-death-ticks mob/death-ticks))]
    (cond (nil? m) sim
          dead-at (cond-> sim (>= (- (:sim/now sim) dead-at) over) (remove-mob eid))
          (:perched k) (perch (bite sim eid) eid)
          (or (:passive k) (:crystal k)) sim
          :else
          (let [sim (burn sim eid)
                burnt (get-in sim [:sim/mobs eid])]
            ;; one the sun just killed is dying, not chasing
            (cond-> sim (and burnt (not (:dead-at burnt))) (chase eid))))))

(defn spawning?
  "Does the night rule spawn hostiles: a difficulty above peaceful, in the overworld?"
  [sim]
  (boolean (and (#{:easy :normal :hard} (:sim/difficulty sim)) (overworld? sim))))

(defn hostiles-near
  "How many living hostiles are within mob/despawn-far of the player (the cap's count)."
  [sim]
  (let [player (:sim/player-pos sim)]
    (count (filter (fn [[_ {:keys [type pos dead-at]}]]
                     (and (:hostile (mob/kinds type))
                          (not dead-at)
                          (<= (physics/distance pos player) mob/despawn-far)))
                   (:sim/mobs sim)))))

(defn try-spawn
  "Every mob/spawn-every ticks, one try at a hostile (spawning?, under mob/hostile-cap): a column
   rolled in the ring mob/spawn-near to mob/spawn-far round the player, its dark spots within
   mob/spawn-band of the player's feet (mob/spawn-spots: roofed, or any at night), one of them
   rolled, and a kind rolled by weight (mob/hostile-kinds)."
  [sim]
  (let [player (:sim/player-pos sim)]
    (cond (not (and player
                    (spawning? sim)
                    (zero? (mod (quot (:sim/now sim) physics/tick-ms) mob/spawn-every))
                    (< (hostiles-near sim) mob/hostile-cap)))
          sim
          :else
          (let [[sim r1] (mob-roll sim)
                [sim r2] (mob-roll sim)
                angle (* 2.0 math/PI r1)
                dist (+ mob/spawn-near (* r2 (- mob/spawn-far mob/spawn-near)))
                [px py pz] player
                x (long (math/floor (+ px (* dist (math/cos angle)))))
                z (long (math/floor (+ pz (* dist (math/sin angle)))))
                fy (long (math/floor py))
                spots (vec (mob/spawn-spots #(block-at sim %)
                                            (free-fn sim)
                                            x
                                            z
                                            (- fy mob/spawn-band)
                                            (+ fy mob/spawn-band)
                                            (column-top sim)
                                            (mob/night? (:sim/day-time sim 0))))]
            (cond (empty? spots) sim
                  :else
                  (let [[sim r3] (mob-roll sim)
                        [sim r4] (mob-roll sim)
                        [sx sy sz] (spots (min (dec (count spots)) (long (* r3 (count spots)))))]
                    (add-mob sim
                             (mob/pick mob/hostile-kinds r4)
                             [(+ sx 0.5) (double sy) (+ sz 0.5)]
                             {})))))))

(defn despawn
  "Hostiles not persistent: removed at once beyond mob/despawn-far of the player, and beyond
   mob/despawn-near by a roll of mob/despawn-chance each tick (Mob.checkDespawn)."
  [sim]
  (let [player (:sim/player-pos sim)]
    (reduce (fn [sim eid]
              (let [{:keys [type pos persistent? dead-at]} (get-in sim [:sim/mobs eid])
                    d (physics/distance pos player)]
                (cond (or persistent? dead-at (not (:hostile (mob/kinds type)))) sim
                      (> d mob/despawn-far) (remove-mob sim eid)
                      (> d mob/despawn-near)
                      (let [[sim r] (mob-roll sim)]
                        (cond-> sim (< r mob/despawn-chance) (remove-mob eid)))
                      :else sim)))
            sim
            (if player (sort (keys (:sim/mobs sim))) []))))

(defn in-nether?
  "Is the player in the Nether, where a fortress's spawners run?"
  [sim]
  (= dimension/nether (:sim/dimension sim)))

(defn wild-spawning?
  "Does the Nether's own spawning run: a difficulty above peaceful, in the Nether? Vanilla
   spawns the Nether's hostiles by biome; the model spawns a warped forest's (mob/warped-kinds)."
  [sim]
  (boolean (and (#{:easy :normal :hard} (:sim/difficulty sim)) (in-nether? sim))))

(defn spawn-pack
  "The model with a pack of n of kind walked out from [x y z]: each member mob/pack-step off
   the last on x and on z (four wild-rolls), at the pack's height, placed where it stands (a cell
   mob/nether-spot? and no nearer the player than mob/spawn-near) and skipped where it does
   not, as NaturalSpawner.spawnCategoryForPosition walks and skips them."
  [sim kind [x y z] n]
  (let [player (:sim/player-pos sim)
        at #(block-at sim %)
        free? (free-fn sim)]
    (first (reduce (fn [[sim x z] _]
                     (let [[sim a] (wild-roll sim)
                           [sim b] (wild-roll sim)
                           [sim c] (wild-roll sim)
                           [sim d] (wild-roll sim)
                           x (+ x (mob/pack-step a b))
                           z (+ z (mob/pack-step c d))
                           pos [(+ x 0.5) (double y) (+ z 0.5)]]
                       [(cond-> sim
                          (and (mob/nether-spot? at free? [x y z])
                               (>= (physics/distance pos player) mob/spawn-near))
                          (add-mob kind pos {})) x z]))
                   [sim x z]
                   (range n)))))

(defn try-nether-spawn
  "Every mob/spawn-every ticks in the Nether (wild-spawning?, under mob/hostile-cap), one try
   at a warped forest's pack: a column rolled in the ring mob/spawn-near to mob/spawn-far round
   the player as try-spawn rolls one, its spots within mob/spawn-band of the player's feet
   (mob/nether-spots: warped nylium under the feet, an enderman's three cells free, any light),
   one of them rolled, a kind rolled by weight (mob/warped-kinds) and a pack of
   mob/enderman-pack walked out from it (spawn-pack). A column with no warped floor spawns
   nothing: the Nether's other biomes' hostiles are not modelled. Every roll is a wild-roll,
   so the overworld's night rule rolls as it did, and a fortress's spawners too until an
   enderman spawns (its turns roll from mob-roll)."
  [sim]
  (let [player (:sim/player-pos sim)]
    (cond (not (and player
                    (wild-spawning? sim)
                    (zero? (mod (quot (:sim/now sim) physics/tick-ms) mob/spawn-every))
                    (< (hostiles-near sim) mob/hostile-cap)))
          sim
          :else
          (let [[sim r1] (wild-roll sim)
                [sim r2] (wild-roll sim)
                angle (* 2.0 math/PI r1)
                dist (+ mob/spawn-near (* r2 (- mob/spawn-far mob/spawn-near)))
                [px py pz] player
                x (long (math/floor (+ px (* dist (math/cos angle)))))
                z (long (math/floor (+ pz (* dist (math/sin angle)))))
                fy (long (math/floor py))
                spots (vec (mob/nether-spots #(block-at sim %)
                                             (free-fn sim)
                                             x
                                             z
                                             (- fy mob/spawn-band)
                                             (+ fy mob/spawn-band)))]
            (cond (empty? spots) sim
                  :else
                  (let [[sim r3] (wild-roll sim)
                        [sim r4] (wild-roll sim)
                        spot (spots (min (dec (count spots)) (long (* r3 (count spots)))))]
                    (spawn-pack sim (mob/pick mob/warped-kinds r4) spot mob/enderman-pack)))))))

(defn spawner-cells
  "The set of cells holding a spawner block in the decoded columns within one chunk of the
   player's column (a spawner wakes within 16 blocks, never farther than the next column)."
  [sim]
  (let [[hx hz] (column-key (:sim/player-pos sim))
        near (into {}
                   (for [x (range (dec hx) (+ hx 2))
                         z (range (dec hz) (+ hz 2))
                         :let [c (column-of sim [x z])]
                         :when c]
                     [[x z] c]))]
    (set (for [[x y z] (chunk/find-blocks near #(= % mob/spawner-block))] [x y z]))))

(defn spawner-nearby
  "How many living blazes stand where the spawner at cell sp counts them (mob/spawner-crowded?)."
  [sim sp]
  (count (filter (fn [[_ {:keys [type pos dead-at]}]]
                   (and (= (:kind mob/spawner) type) (not dead-at) (mob/spawner-crowded? sp pos)))
                 (:sim/mobs sim))))

(defn spawn-batch
  "The model after the spawner at cell sp spawns: a batch rolled (mob/spawner-batch), cut to the
   room it has (mob/spawner-room), each try five rolls of the mobs' stream, each blaze placed
   where one fits (mob/spawner-spawns)."
  [sim sp]
  (let [[sim rb] (mob-roll sim)
        tries (min (mob/spawner-batch rb) (mob/spawner-room (spawner-nearby sim sp)))
        [sim rolls] (reduce (fn [[sim acc] _] (let [[sim r] (mob-roll sim)] [sim (conj acc r)]))
                            [sim []]
                            (range (* 5 tries)))]
    (reduce (fn [sim [x y z]] (add-mob sim (:kind mob/spawner) [(+ x 0.5) (double y) (+ z 0.5)] {}))
            sim
            (mob/spawner-spawns (free-fn sim) sp tries (partition 5 rolls)))))

(defn due-at
  "The time in ms of the spawner's next spawn, a delay rolled (mob/spawner-delay) from now:
   [sim' ms]."
  [sim]
  (let [[sim r] (mob-roll sim)] [sim (+ (:sim/now sim) (* physics/tick-ms (mob/spawner-delay r)))]))

(defn tick-spawner
  "One tick of the spawner at cell sp: gone when its block is (broken); nothing with the player
   out of its range (mob/spawner-awake?); the first tick in range sets its delay; once the delay
   is over, a batch (spawn-batch) and the next delay."
  [sim sp]
  (let [due (get-in sim [:sim/spawners sp])]
    (cond (not= mob/spawner-block (block-at sim sp)) (update sim :sim/spawners dissoc sp)
          (not (mob/spawner-awake? sp (:sim/player-pos sim))) sim
          (nil? due) (let [[sim at] (due-at sim)] (assoc-in sim [:sim/spawners sp] at))
          (< (:sim/now sim) due) sim
          :else
          (let [[sim at] (due-at (spawn-batch sim sp))] (assoc-in sim [:sim/spawners sp] at)))))

(defn tick-spawners
  "A tick of the Nether's spawners, in a model with a difficulty above peaceful and a player in
   the Nether: the spawners round the player are found when it comes into another column
   (:sim/spawners {cell due-ms}, kept apart from the mobs' timers), then each ticks in order
   (tick-spawner)."
  [sim]
  (let [player (:sim/player-pos sim)
        here (when player (column-key player))]
    (cond (not (and player (#{:easy :normal :hard} (:sim/difficulty sim)) (in-nether? sim))) sim
          :else
          (let [sim (cond-> sim
                      (not= here (:sim/spawners-at sim))
                      (-> (assoc :sim/spawners-at here)
                          (update :sim/spawners
                                  #(merge (zipmap (spawner-cells sim) (repeat nil)) %))))]
            (reduce tick-spawner sim (sort (keys (:sim/spawners sim))))))))

(defn grass-top
  "The feet height on the highest grass block of column x z with room to stand over it, from 32
   over y down to 32 under it; nil when there is none."
  [sim x z y]
  (let [free? (free-fn sim)]
    (first (filter #(and (= :grass_block (blocks/name-of (block-at sim [x (dec %) z])))
                         (mob/stands? free? [x % z]))
                   (range (+ y 32) (- y 32) -1)))))

(defn populate-column
  "Chunk [cx cz] has its animals placed, once (:sim/populated): one time in ten (mob/animal-chance)
   a herd of one rolled kind on the grass at its middle (grass-top), as a server populates a
   chunk it generates."
  [sim [cx cz]]
  (let [sim (update sim :sim/populated (fnil conj #{}) [cx cz])
        [sim r] (mob-roll sim)]
    (cond (>= r mob/animal-chance) sim
          :else
          (let [[sim r2] (mob-roll sim)
                kind (nth mob/passive-kinds
                          (min (dec (count mob/passive-kinds))
                               (long (* r2 (count mob/passive-kinds)))))
                x (+ (* 16 cx) 8)
                z (+ (* 16 cz) 8)
                y (grass-top sim x z (long (math/floor (second (:sim/player-pos sim)))))]
            (cond (nil? y) sim
                  :else
                  (reduce
                   (fn [sim dx]
                     (cond-> sim
                       (mob/stands? (free-fn sim) [(+ x dx) y z])
                       (add-mob kind [(+ x dx 0.5) (double y) (+ z 0.5)] {:persistent? true})))
                   sim
                   (range mob/herd)))))))

(defn populate
  "The chunks within populate-radius of the player that never had their animals placed get them
   (populate-column), nearest the player's column first; only in the overworld of a model with a
   difficulty, and only when the player came into another column."
  [sim]
  (let [player (:sim/player-pos sim)
        [hx hz :as here] (when player (column-key player))]
    (cond (or (nil? player)
              (nil? (:sim/difficulty sim))
              (not (overworld? sim))
              (= here (:sim/populated-at sim)))
          sim
          :else
          (reduce populate-column
                  (assoc sim :sim/populated-at here)
                  (sort-by (fn [[x z]] [(+ (abs (- x hx)) (abs (- z hz))) x z])
                           (for [x (range (- hx populate-radius) (inc (+ hx populate-radius)))
                                 z (range (- hz populate-radius) (inc (+ hz populate-radius)))
                                 :when (and (contains? (:sim/columns sim) [x z])
                                            (not (contains? (:sim/populated sim) [x z])))]
                             [x z]))))))

(defn tick-mobs
  "One game tick of the mobs: the day moves on (:sim/day-time), chunks coming near get their
   animals (populate), a hostile is tried for (try-spawn), the Nether's warped forests spawn
   their endermen (try-nether-spawn) and its spawners run (tick-spawners), every mob acts in id
   order (act-mob), and far hostiles despawn (despawn). A model with no difficulty and no mobs
   only keeps its day."
  [sim]
  (let [sim (cond-> sim (:sim/day-time sim) (update :sim/day-time inc))]
    (cond (not (or (:sim/difficulty sim) (seq (:sim/mobs sim)))) sim
          :else
          (let [sim (-> sim
                        populate
                        try-spawn
                        try-nether-spawn
                        tick-spawners)]
            (despawn (reduce act-mob sim (sort (keys (:sim/mobs sim)))))))))


;; ---------------------------------------------------------------- eyes, the portal and the End (issue #260)

(def eye-type (get blocks/entity-types :eye_of_ender)) ; add-entity's type for a thrown eye
(def eye-body 0.9)                    ; an eye leaves from the middle of the thrower's body (EnderEyeItem: getY(0.5))
(def dragon-heal-every 10)            ; game ticks between a crystal's heals of the dragon (EnderDragon.checkCrystals)

(def end-respawn
  "The respawn the model sends for the End: the dimension change an end portal makes."
  (assoc respawn
         :dimension-type (:dimension/id (dimension/by-name dimension/end))
         :dimension dimension/end))

(defmethod use-held :eye
  ;; an eye thrown toward the stronghold (:sim/stronghold): one taken, an eye of ender entity at
  ;; the body's middle flying for end/eye-target (tick-eyes); with no stronghold the eye stays in
  ;; hand, as the game throws none in a world without one
  [sim _]
  (let [[x y z :as player] (:sim/player-pos sim)
        stronghold (:sim/stronghold sim)]
    (cond (not (and player stronghold)) sim
          :else
          (let [eid (:sim/next-eid sim)
                from [x (+ y eye-body) z]
                [sim r] (mob-roll sim)
                before (full (:sim/inv sim) (inventory/layouts :inventory))]
            (-> sim
                (assoc :sim/next-eid (inc eid))
                (update :sim/inv take-one (held-slot sim))
                (sync-window :inventory before (:sim/cursor sim) false)
                (assoc-in [:sim/eyes eid]
                          {:from from
                           :pos from
                           :target (end/eye-target from stronghold)
                           :at (:sim/now sim)
                           :survives? (< r end/eye-survives)})
                (send {:packet/name :add-entity
                       :entity-id eid
                       :uuid nil
                       :type eye-type
                       :x (first from)
                       :y (second from)
                       :z (last from)}))))))

(defn tick-eyes
  "One game tick of every thrown eye: along its flight (end/eye-pos), each move told (tell-move);
   at the end of its life it is gone, and one that survives (end/eye-survives, rolled at the
   throw) drops as an eye item where it falls to."
  [sim]
  (reduce (fn [sim [eid {:keys [from pos target at survives?]}]]
            (let [ticks (quot (- (:sim/now sim) at) physics/tick-ms)
                  to (end/eye-pos from target ticks)]
              (cond (< ticks end/eye-life-ticks)
                    (-> sim
                        (tell-move eid pos to)
                        (assoc-in [:sim/eyes eid :pos] to))
                    :else
                    (-> sim
                        (update :sim/eyes dissoc eid)
                        (send {:packet/name :remove-entities :ids [eid]})
                        (cond-> survives?
                                (spawn-item (mob/fall (free-fn sim) pos)
                                            {:item (recipe/item-id :ender_eye) :count 1}))))))
          sim
          (sort-by key (:sim/eyes sim))))

(defn frame-takes-eye
  "use-item-on an end portal frame with no eye, an eye held: the frame takes it (its state with
   the eye), one eye taken, and when that closes a ring of twelve the portal fills its interior
   (end/lit); then the ack. With anything else held it is a placement against the frame's face
   (place); a frame that has its eye only acks."
  [sim pos face ack]
  (let [id (block-at sim pos)
        eye? (= (held-item sim) (recipe/item-id :ender_eye))]
    (cond (not eye?) (place sim pos face ack)
          (end/eyed? id) (send sim ack)
          :else
          (let [before (full (:sim/inv sim) (inventory/layouts :inventory))
                sim (-> sim
                        (set-cell pos (end/with-eye id))
                        (update :sim/inv take-one (held-slot sim))
                        (sync-window :inventory before (:sim/cursor sim) false))]
            (->
              (reduce (fn [sim c] (set-cell sim c end/portal)) sim (end/lit #(block-at sim %) pos))
              (send ack))))))

(defn arrive-end
  "The model in the End: the overworld's columns, blocks, items, eyes and mobs gone (the client
   drops them at the respawn), the End's columns (:sim/end's, none for a void), the player on
   the obsidian platform (end/platform, told block by block after the chunks), the dragon
   perched at :sim/end's :dragon (the fountain's top when absent) and a crystal on every spike
   (:sim/end's :crystals, end/spikes' when absent)."
  [sim]
  (let [{:keys [columns dragon dragon-yaw crystals]} (:sim/end sim)
        sim (leave-overworld sim)
        [x y z] end/spawn
        platform (end/platform)
        sim (-> sim
                (assoc :sim/dimension dimension/end
                       :sim/columns (or columns {})
                       :sim/placed platform
                       :sim/broken #{}
                       :sim/items {}
                       :sim/eyes {}
                       :sim/mobs {}
                       :sim/fluids {}
                       :sim/falls {}
                       :sim/falling {}
                       :sim/player-pos end/spawn
                       :sim/view #{})
                (dissoc :sim/decoded :sim/view-at :sim/portal-since)
                (send end-respawn)
                (send {:packet/name :player-position
                       :teleport-id 4
                       :x x
                       :y y
                       :z z
                       :dx 0.0
                       :dy 0.0
                       :dz 0.0
                       :yaw 0.0
                       :pitch 0.0
                       :flags 0})
                stream)]
    (reduce #(add-mob %1 :end_crystal %2 {:persistent? true})
            (-> (reduce (fn [sim [c state]]
                          (send sim {:packet/name :block-update :pos c :state state}))
                        sim
                        (sort-by key platform))
                (add-mob :ender_dragon
                         (or dragon [0.5 65.0 0.5])
                         (cond-> {:persistent? true} dragon-yaw (assoc :yaw dragon-yaw))))
            (or crystals (map end/crystal-at end/spikes)))))

(defn through-end-portal
  "The model a tick on: a player whose feet are in an end portal outside the End arrives in it at
   once (arrive-end), as the game's end portal has no wait."
  [sim]
  (let [pos (:sim/player-pos sim)]
    (cond (or (nil? pos) (= dimension/end (:sim/dimension sim))) sim
          (= end/portal (block-at sim (mob/cell-of (update pos 1 + 0.1)))) (arrive-end sim)
          :else sim)))

(defn bite
  "The perched dragon eid's head and neck: with a yaw, a player within them (mob/bitten?) is hurt
   mob/bite-damage, scaled by the difficulty and its armour, every mob/bite-every ticks (the
   tick's sync-health tells it)."
  [sim eid]
  (let [{:keys [pos yaw attack-at]} (get-in sim [:sim/mobs eid])
        player (:sim/player-pos sim)
        now (:sim/now sim)]
    (cond (not (and yaw player (mob/bitten? pos yaw player) (>= now (or attack-at 0)))) sim
          :else
          (-> sim
              (assoc-in [:sim/mobs eid :attack-at] (+ now (* mob/bite-every physics/tick-ms)))
              (hurt (armoured sim (mob/scaled mob/bite-damage (:sim/difficulty sim)))
                    true
                    :ender_dragon)))))

(defn perch
  "The perched dragon's tick: its bite, and while any crystal stands, one health back every
   dragon-heal-every ticks up to its kind's full health, told (set-entity-data)."
  [sim eid]
  (let [{:keys [health]} (get-in sim [:sim/mobs eid])
        full-health (:health (mob/kinds :ender_dragon))
        crystal? (some #(and (= :end_crystal (:type %)) (not (:dead-at %))) (vals (:sim/mobs sim)))]
    (cond (not (and crystal?
                    (< health full-health)
                    (zero? (mod (quot (:sim/now sim) physics/tick-ms) dragon-heal-every))))
          sim
          :else
          (let [h (min full-health (inc health))]
            (-> sim
                (assoc-in [:sim/mobs eid :health] h)
                (send (health-data eid h)))))))


(defn tick
  "Advance the server clock to now, one game tick: the scheduled death, breath or drowning,
   hunger (feed) and eating, a delayed destroy, every furnace, falling gravel and sand, flowing
   water and lava, a player carried through a portal or an end portal, thrown eyes (tick-eyes),
   the mobs (tick-mobs), a keep-alive when one
   is due, every item
   handed over whose pickup delay has passed and that the player stands within reach of, and,
   with hunger, the health and food told when they changed (sync-health)."
  [sim now]
  (-> (assoc sim :sim/now now)
      kill
      drown
      feed
      tick-eating
      tick-delayed
      tick-furnaces
      tick-falls
      tick-fluids
      (through-portal now)
      through-end-portal
      tick-eyes
      tick-mobs
      keep-alive
      pick-up
      sync-health))

(defn step
  "The model's reducer: a client packet or a tick in, the next sim out."
  [sim {:sim/keys [kind packet now]}]
  (case kind
    :packet (on-packet sim packet)
    :tick (tick sim now)
    sim))

(defn drain "[sim' packets-for-the-client]" [sim] [(assoc sim :sim/out []) (:sim/out sim)])

(defn sends
  "The packets a bot world asked to send, from its :bot/effects."
  [world]
  (mapv :effect/packet (filter #(= :send (:effect/kind %)) (:bot/effects world))))

(defn tick-rand
  "[r' x]: the next state of a tick seed r and its roll x in [0, 1), by the same pure step as
   the loot's rolls (roll)."
  [r]
  (let [[{r' :sim/rng} x] (roll {:sim/rng r})] [r' x]))

(def moment-every-ms 30000)       ; simulated ms between the moments a run keeps (play-from)
(def moment-keep 12)              ; moments kept: the last six simulated minutes

(defn kept
  "The moments with m added when every (simulated ms, nil to keep none) have passed since the last
   kept, the oldest dropped past moment-keep."
  [moments m every]
  (let [last-t (:t (peek moments))]
    (cond (or (nil? every) (and last-t (< (- (:t m) last-t) every))) moments
          :else (let [v (conj moments m)] (if (> (count v) moment-keep) (subvec v 1) v)))))

(defn tick-from
  "One tick of a bot reducer against the model from moment m (see play-from): {:next m'} the
   moment after it, or {:stop {:world :sim}} when (stop? world) holds after it or t is past
   until-t, the last tick's packets delivered to the model."
  [bot-step {:keys [world sim pending t went? inflight] r :rand} stop? until-t go lag]
  (let [sim (reduce #(step %1 {:sim/kind :packet :sim/packet %2}) sim pending)
        sim (step sim {:sim/kind :tick :sim/now t})
        [sim fresh] (drain sim)
        inflight (into inflight (map (fn [p] [(+ t lag) p]) fresh))
        inbound (map second (take-while #(<= (first %) t) inflight))
        inflight (vec (drop-while #(<= (first %) t) inflight))
        world (reduce #(bot-step %1 {:event/kind :packet :event/packet %2}) world inbound)
        [r rand] (if r (tick-rand r) [nil 0.5])
        world (bot-step world {:event/kind :tick :event/now t :event/rand rand})
        go? (and (not went?) (:player/loaded? world))
        world (if go? (bot-step world go) world)
        out (sends world)
        world (assoc world :bot/effects [])]
    (cond (or (stop? world) (> t until-t))
          {:stop {:world world :sim (reduce #(step %1 {:sim/kind :packet :sim/packet %2}) sim out)}}
          :else
          {:next {:world world
                  :sim sim
                  :pending out
                  :t (+ t physics/tick-ms)
                  :rand r
                  :went? (boolean (or went? go?))
                  :inflight inflight}})))

(defn play-from
  "Play a bot reducer against the model on from a moment m, a tick (physics/tick-ms) at a time
   (tick-from), until (stop? world) or max-ms of play. A moment is everything the next tick
   depends on: {:world :sim :pending :t :rand :went? :inflight}: the bot's world and the model's
   state, what the bot has sent and the model not yet taken (pending), the tick's time, the tick
   seed's state (rand), whether go was sent, and the packets still in flight under
   :sim/lag-ticks. So playing on from a moment is the run itself, packet for packet. Returns
   {:world :sim :before :last :moments}: :last the moment of the stopping tick, :before its world
   and model, and, with every (simulated ms; nil keeps none), :moments, the run's moments that
   far apart, the last moment-keep of them, for a failure to be kept from before the choice that
   led to it (simrace/snapshot). A tick that throws ends the play there with :crashed (the
   exception) and the world and model of the moment before it, so a crash is kept and judged
   like any other failure (issue #152: a crash used to leave no moment and no stage). go is sent
   once the bot is loaded, or never when nil. Each tick's :event/rand is 0.5, or, with a rand
   state, the next of a pure generator (tick-rand), so runs of one world with different seeds
   are different runs, each the same every time."
  [bot-step m stop? max-ms go every]
  (let [lag (* physics/tick-ms (:sim/lag-ticks (:sim m) 0))
        until-t (+ (:t m) max-ms)]
    (loop [m m
           moments []]
      (let [moments (kept moments m every)
            r (try (tick-from bot-step m stop? until-t go lag) (catch Exception e {:crashed e}))
            ended {:before [(:world m) (:sim m)] :last m :moments moments}]
        (cond (:crashed r) (assoc ended :world (:world m) :sim (:sim m) :crashed (:crashed r))
              (:stop r) (merge ended (:stop r))
              :else (recur (:next r) moments))))))

(defn play-on
  "Play a bot reducer against the model on from world and sim as they are, from t0 with pending
   not yet taken by the model (the start packets, for a run from the handshake), until (stop?
   world) or max-ms of play: play-from from that moment, the tick seed started at :sim/tick-seed,
   no moments kept. Returns {:world :sim :before [world sim] :last}, :before the pair one tick
   before the stop."
  [bot-step world sim stop? max-ms t0 pending go]
  (play-from bot-step
             {:world (assoc world :bot/effects [])
              :sim sim
              :pending pending
              :t t0
              :rand (:sim/tick-seed sim)
              :went? (boolean (nil? go))
              :inflight []}
             stop?
             max-ms
             go
             nil))

(defn start-moment
  "The moment a run from a fresh handshake starts from: the bot's world after :start, the model,
   the start packets pending, time 0, the tick seed at :sim/tick-seed, go not yet sent."
  [bot-step world0 sim0]
  (let [w (bot-step world0 {:event/kind :start})]
    {:world (assoc w :bot/effects [])
     :sim sim0
     :pending (sends w)
     :t 0
     :rand (:sim/tick-seed sim0)
     :went? false
     :inflight []}))

(defn run+
  "Run a bot reducer against the model from a fresh handshake (start-moment) until (stop? world)
   or max-ms, sending go (default {:event/kind :go}) once the bot is loaded, keeping a moment every
   moment-every-ms: play-from's answer, {:world :sim :before :last :moments}."
  [bot-step world0 sim0 stop? max-ms & [go]]
  (play-from bot-step
             (start-moment bot-step world0 sim0)
             stop?
             max-ms
             (or go {:event/kind :go})
             moment-every-ms))

(defn run
  "Run a bot reducer against the model from a fresh handshake, a tick (physics/tick-ms) at a
   time, until (stop? world) or max-ms. Sends go (default {:event/kind :go}) once the bot is
   loaded. Returns [world sim] (run+ for the moment before the stop too)."
  [bot-step world0 sim0 stop? max-ms & [go]]
  (let [{:keys [world sim]} (run+ bot-step world0 sim0 stop? max-ms go)] [world sim]))

;;;; I/O: the model behind a channel pair, on a thread of its own ;;;;


(defn connect!
  "The model behind a channel pair shaped like conn/open!'s: {:in chan :out chan :close! fn}.
   Packet maps the client puts on :out are stepped into the model; every physics/tick-ms of wall time
   the model ticks; whatever it says for the client goes onto :in, in order. Both channels are
   bounded, as conn's are. :in closes when the client closes :out or calls :close!, which the
   loop reads as the socket closing."
  [sim0]
  (let [in (async/chan 1024)
        out (async/chan 256)
        stop (async/chan)
        t0 (System/currentTimeMillis)]
    (async/thread (loop [sim sim0
                         next-tick physics/tick-ms]
                    (let [wait (max 0 (- next-tick (- (System/currentTimeMillis) t0)))
                          [v ch] (async/alts!! [stop out (async/timeout wait)] :priority true)
                          closed? (or (= ch stop) (and (= ch out) (nil? v)))
                          sim (cond closed? sim
                                    (= ch out) (step sim {:sim/kind :packet :sim/packet v})
                                    :else (step sim {:sim/kind :tick :sim/now next-tick}))
                          [sim said] (drain sim)]
                      (doseq [p said] (async/>!! in p))
                      (cond closed? (async/close! in)
                            (= ch out) (recur sim next-tick)
                            :else (recur sim (+ next-tick physics/tick-ms))))))
    {:in in :out out :close! (fn [] (async/close! stop) (async/close! out))}))
