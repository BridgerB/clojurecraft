(ns clojurecraft.sim.world
  "The model's ground floor: its constants, the rolls and loot, the columns and blocks it holds, what it tells the client (send, sounds, health), and the rules of the world that need nothing above them: fluids that flow, blocks that fall, the cell a change disturbs (set-cell). The packet dispatch (on-packet) is defined here so every layer above adds its methods to one table."
  (:refer-clojure :exclude [send])
  (:require [clojure.math :as math]
            [clojurecraft.blocks :as blocks]
            [clojurecraft.chunk :as chunk]
            [clojurecraft.dig :as dig]
            [clojurecraft.dimension :as dimension]
            [clojurecraft.fluid :as fluid]
            [clojurecraft.food :as food]
            [clojurecraft.hunger :as hunger]
            [clojurecraft.inventory :as inventory]
            [clojurecraft.memo :as memo]
            [clojurecraft.physics :as physics]
            [clojurecraft.registry :as registry]))

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
  "How many of item one slot holds: one for a tool (blocks/tool: the game's tools never stack, each
   wears on its own), max-stack for anything else. With every item stacking to 64, a spare pickaxe
   went into the slot of the one in use, and the dig that broke one emptied the slot of both."
  [item]
  (if (blocks/tool item) 1 max-stack))

(def max-air 300)                     ; a full breath, as the game's

(def drown-every 20)                  ; ticks between drowning hurts at zero air

(def drown-damage 2.0)                ; health taken by each

(def max-health 20.0)                 ; a full life, the health a spawn and a respawn give

(def air-regained 4)                  ; breath a tick gives back with the eyes out of water

(def drop-rise 0.25)                  ; how far over the bottom of a broken block its drop appears

(defn send "Queue pkt for the client in :sim/out." [sim pkt] (update sim :sim/out conj pkt))

(def hostile-source 5)                  ; SoundSource.HOSTILE, the category a monster's sounds are sent in

(defn tell-sound
  "The model having told the client sound (a key of the registry's sounds, :entity.enderman.stare)
   played at point [x y z] as the server does: a sound packet, the registry id and the position in
   eighths of a block, hostile category, volume and pitch 1. Nothing for a name the registry lacks."
  [sim sound [x y z]]
  (let [id (get registry/sound-ids sound)
        q #(math/round (* 8.0 (double %)))]
    (cond (nil? id) sim
          :else
          (send sim
                {:packet/name :sound
                 :sound {:id id}
                 :source hostile-source
                 :x (q x)
                 :y (q y)
                 :z (q z)
                 :volume 1.0
                 :pitch 1.0
                 :seed 0}))))

(defn tell-level-event
  "The model having told the client level event type (1018 a blaze's shot) at the block under point
   [x y z] as the server does (level-event, not global, data 0)."
  [sim type [x y z]]
  (send sim
        {:packet/name :level-event
         :type type
         :pos (mapv #(long (math/floor (double %))) [x y z])
         :data 0
         :global false}))

(defmulti on-packet
  "The server's answer to one client packet, by [phase packet-name]; unknown packets are
   ignored."
  {:arglists '([sim pkt])}
  (fn [sim pkt] [(:sim/phase sim) (:packet/name pkt)]))

(defn health
  "The set-health packet that tells the client the player's health and food as the model holds them:
   :sim/food, or a full bar when no food is modelled, and the saturation hunger keeps, the start's 5
   when it keeps none."
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
  "One game tick of fluids: every cell whose tick is due, in position order so a run replays, ticks
   (fluid-tick). Older saved models first wake fluids beside changed cells: their scheduler did not
   recognize aquatic sources."
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
