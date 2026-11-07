(ns clojurecraft.pearls-support
  "Helpers the pearl hunt's tests share: canned worlds, a stand-in router and the step function."
  (:require [clojurecraft.blocks :as blocks]
            [clojurecraft.fixtures :as fx]
            [clojurecraft.game :as game]
            [clojurecraft.intent :as intent]
            [clojurecraft.make]
            [clojurecraft.memory :as memory]
            [clojurecraft.mob :as mob]
            [clojurecraft.plan :as plan]
            [clojurecraft.recipe :as recipe]
            [clojurecraft.sim :as sim]
            [clojurecraft.sim.mobs :as sim-mobs]
            [clojurecraft.world :as world]))

(def step (game/compose game/step plan/step))

(def stone 1)

(def real-routable? "intent/routable? as loaded, before any test redefines it." intent/routable?)

(defn routable-here
  "A stand-in for intent/routable? that answers with answer (a fn of world and intent) on the
   thread that made it and with the real one on every other: with-redefs is global and the suite
   runs tests in parallel, so a plain (constantly false) answered another test's route tests too
   (#777's burrow-site test lost its site to it)."
  [answer]
  (let [me (Thread/currentThread)]
    (fn [world i]
      (cond (identical? me (Thread/currentThread)) (answer world i)
            :else (real-routable? world i)))))

(defn item "A stack of n of the item named k." [k n] {:item (recipe/item-id k) :count n})

(defn enderman
  "A tracked enderman at pos with its full health."
  [pos]
  {:entity/type (blocks/entity-types :enderman)
   :entity/pos pos
   :entity/seen-at 0
   :entity/health 40.0})

(defn standing
  "A bot in play at [8.5 64 8.5] on the stone floor of one column (with blocks, {[x y z] state},
   over it), an iron sword in hotbar slot 0, with the given entities."
  ([entities] (standing entities {}))
  ([entities blocks]
   (-> (game/init fx/opts)
       (assoc :bot/phase :play
              :player/pos [8.5 64.0 8.5]
              :player/loaded? true
              :player/on-ground? true
              :player/health 20.0
              :world/chunks {[0 0] (world/column blocks)}
              :window/state-id 5
              :player/inventory {0 (item :iron_sword 1)}
              :world/entities entities))))

(def row (first (filter #(= :ender-pearls (:goal/id %)) plan/goals)))

(defn tick [t] {:event/kind :tick :event/now t :event/rand 0.5})

(defn intent-step
  "The world reducer plus one run of the current intent per tick while it is active."
  [w e]
  (let [w (game/step w e)]
    (if (and (= :tick (:event/kind e)) (= :active (:intent/status (:plan/intent w))))
      (intent/run w (:plan/intent w) e)
      w)))

(def wall
  "Stone four high across x 6 to 10 at z 12: it hides the head of an enderman standing at z 15
   from the eye at z 8.5, and a walk goes round it."
  (into {} (for [x (range 6 11) y (range 64 68)] [[x y 12] stone])))

(defn looked-out
  "w after a lookout that found nothing: the hunt's next choice is a roam."
  [w]
  (assoc w :time/now 0 :plan/last {:intent/kind :lookout :intent/status :done}))

(def rock-y (range 64 67))

(defn cave
  "Four chunk columns (x 0 to 31, z 0 to 31) of stone three high over the floor (y 64 to 66),
   with the cells of open (world coordinates) left as air: a cave inside the rock."
  [open]
  (into {}
        (for [cx [0 1]
              cz [0 1]]
          [[cx cz]
           (world/column (into {}
                               (for [lx (range 16)
                                     lz (range 16)
                                     y rock-y
                                     :let [x (+ (* 16 cx) lx)
                                           z (+ (* 16 cz) lz)]
                                     :when (not (contains? open [x y z]))]
                                 [[lx y lz] stone])))])))

(defn passage
  "The cells of a passage two high from the feet at [8 64 8]: north along x 8 to z, then east
   along that z to x."
  [z x]
  (set (concat (for [pz (range 8 (inc z)) y [64 65]] [8 y pz])
               (for [px (range 8 (inc x)) y [64 65]] [px y z]))))

(def cliff
  "Stone six high (y 64 to 69) across z 20 to 31 of the chunk column [0 1]: a ledge no route
   climbs, from the floor at y 64 south of it."
  (into {} (for [lx (range 16) lz (range 4 16) y (range 64 70)] [[lx y lz] stone])))

(defn rock
  "Four chunk columns (x 0 to 31, z 0 to 31) of stone from the floor up to y 79, with the cells of
   open (world coordinates) left as air, under the floor too: a pocket deep in the rock, open to
   the sky from y 80."
  [open]
  (into {}
        (for [cx [0 1]
              cz [0 1]]
          [[cx cz]
           (world/column (into (into {}
                                     (for [[x y z] open
                                           :when (and (< y 64)
                                                      (= [cx cz] [(quot x 16) (quot z 16)]))]
                                       [[(mod x 16) y (mod z 16)] 0]))
                               (for [lx (range 16)
                                     lz (range 16)
                                     y (range 64 80)
                                     :let [x (+ (* 16 cx) lx)
                                           z (+ (* 16 cz) lz)]
                                     :when (not (contains? open [x y z]))]
                                 [[lx y lz] stone])))])))

(defn walled-in
  "A bot in a pocket of the rock (rock open) with an iron pickaxe 40 digs from worn out (spare
   digs and more), and an enderman at pos."
  [open pos]
  (-> (standing {7 (enderman pos)})
      (assoc :world/chunks (rock open))
      (assoc-in [:player/inventory 1] (assoc (item :iron_pickaxe 1) :components {:damage 210}))))

(def feet-cells #{[8 64 8] [8 65 8]})

(defn stuck-in
  "w with a walk ended and then n lookouts, the last of them the plan's last intent, as
   memory keeps them: a hunt that has stood looking since it last walked anywhere."
  [w n]
  (looked-out
   (reduce #(memory/remember-intention %1 {:intent/id (+ 10 %2) :intent/kind :lookout} :done)
           (memory/remember-intention (assoc w :time/now 0) {:intent/id 1 :intent/kind :walk} :done)
           (range n))))

(def air 0)

(def burrow
  "A burrow cut east from [3 64 8], as :burrow cuts one: three stairs, each three cells high
   (x 4 to 6, down to feet 61), then two tunnel steps two high (x 7 and 8, the ground over them)."
  (into {}
        (for [c [[4 63 8] [5 63 8] [5 62 8] [6 63 8] [6 62 8] [6 61 8] [7 62 8] [7 61 8] [8 62 8]
                 [8 61 8]]]
          [c air])))

(defn burrowed
  "standing at feet with the burrow dug and remembered, holding a pickaxe too, with entities,
   and more blocks in the column when given."
  ([feet entities] (burrowed feet entities {}))
  ([feet entities blocks]
   (-> (standing entities (merge burrow blocks))
       (assoc :player/pos feet :pearls/shelter [8 61 8] :pearls/mouth [3 64 8])
       (assoc-in [:player/inventory 1] (item :iron_pickaxe 1)))))

(defn looked
  "w with its burrow cut at time 0 and n lookouts stood since, as memory keeps them (a :burrow
   intention done, then n :lookout intentions done)."
  [w n]
  (reduce
   #(memory/remember-intention %1 {:intent/id (+ 10 %2) :intent/kind :lookout} :done)
   (memory/remember-intention (assoc w :time/now 0) {:intent/id 1 :intent/kind :burrow} :done)
   (range n)))

(defn lured
  "w with its burrow cut at time 0 and n lures walked since (a walk out to the mouth and one back
   in, two :walk intentions done each)."
  [w n]
  (reduce
   #(memory/remember-intention %1 {:intent/id (+ 10 %2) :intent/kind :walk} :done)
   (memory/remember-intention (assoc w :time/now 0) {:intent/id 1 :intent/kind :burrow} :done)
   (range (* 2 n))))

(defn fought
  "w with n :fight intentions on entity 7 ended :done, as memory keeps them."
  [w n]
  (reduce
   #(memory/remember-intention %1 {:intent/id (+ 10 %2) :intent/kind :fight :intent/target 7} :done)
   (assoc w :time/now 0)
   (range n)))

(def ledge-wall
  "Stone three high (y 64 to 66) along x 4, the burrow's first stair left open: from the mouth at
   x 3 the only way east is down through the burrow."
  (into {} (for [z (range 16) :when (not= z 8) y (range 64 67)] [[4 y z] stone])))

(def slab
  "Stone one thick over the whole column at y 80: it hides an enderman standing at y 90 from
   the eye below, and no route reaches it."
  (into {} (for [x (range 16) z (range 16)] [[x 80 z] stone])))

(def north-wall
  "Stone four high across x 1 to 6 at z 12: it hides the head of an enderman standing north of
   the burrow's mouth at z 20 from the eye at the mouth, and a walk goes round it to the east."
  (into {} (for [x (range 1 7) y (range 64 68)] [[x y 12] stone])))

(def arena
  "The model's world for the hunt: two stone columns side by side, an enderman eleven blocks west
   of the spawn, the kit a chained run carries: an iron sword and pickaxe, the iron helmet and
   chestplate (#259's armour row), eleven pearls."
  {:columns {[0 0] (world/column-bytes {}) [1 0] (world/column-bytes {})}
   :spawn [12.5 64.0 8.5]
   :inventory {[:hotbar 0] (item :iron_sword 1)
               [:hotbar 1] (item :iron_pickaxe 1)
               [:armour 0] (item :iron_helmet 1)
               [:armour 1] (item :iron_chestplate 1)
               [:main 0] (item :ender_pearl 11)}
   :mobs [{:type :enderman :pos [1.5 64.0 8.5]}]})

(defn pearl-seed
  "The first seed whose model drops the enderman's pearl: the loot is the first roll of the
   mobs' own stream (sim-mobs/mob-roll) when nothing spawns or despawns, one in two."
  []
  (first (filter #(pos? (mob/loot-count [:ender_pearl 0 1]
                                        (second (sim-mobs/mob-roll (sim/init
                                                                    (assoc arena :seed %))))))
                 (range 64))))

(defn wide
  "w with flat stone floor chunks from -2 to 4 round its own, so routes and roams have ground."
  [w]
  (update w
          :world/chunks
          #(into (or % {})
                 (for [cx (range -2 5)
                       cz (range -2 5)
                       :when (not= [0 0] [cx cz])]
                   [[cx cz] (world/column {})]))))

(def stranded
  "Run 1 of gym plan run 40775811459 (issue #263, pearls-12): at its burrow's end with five
   pearls, endermen tracked 22 blocks out and 14 down, and 16 out and 45 up: none with a route.
   A staircase from the feet is boxed, as the cuts toward them were refused (descend, ascend,
   dig-out and climb-out all ask :plan/boxed-at)."
  (-> (wide (burrowed [8.5 61.0 8.5] {7 (enderman [30.5 50.0 8.5]) 9 (enderman [8.5 106.0 24.5])}))
      (assoc :plan/boxed-at #{[8 61 8]})))

(defn mob
  "A tracked mob of kind k (a keyword) at pos."
  [k pos]
  {:entity/type (blocks/entity-types k) :entity/pos pos :entity/seen-at 0 :entity/health 20.0})

(def burst
  "Run 5 of gym plan run 40825468783 (issue #263, pearls-12): an angry enderman and two zombified
   piglins within reach took 20 health in 1.5 s."
  {7 (assoc (enderman [12.5 64.0 8.5]) :entity/creepy? true)
   8 (mob :zombified_piglin [11.5 64.0 9.5])
   9 (mob :zombified_piglin [5.5 64.0 6.5])})

(def tunnel
  "A tunnel two high cut east through the stone from x 8 to 13 at y 60 and 61, opening into a hall
   (y 60 to 64) at x 14 and 15: the open line a stair cut gave a ghast in gym plan run 40839050567
   job 6 (issue #263, pearls-12), the bot at [2229 96 2686] and the ghast at [2253 83 2703]."
  (into {}
        (concat (for [x (range 8 14) y [60 61]] [[x y 8] air])
                (for [x [14 15] y (range 60 65)] [[x y 8] air]))))

(defn walked
  "w with a :walk intention done to each target of cells, in order, as memory keeps them."
  [w cells]
  (reduce-kv #(memory/remember-intention
               %1
               {:intent/id (+ 100 %2) :intent/kind :walk :intent/target %3}
               :done)
             w
             (vec cells)))

(def nether "minecraft:the_nether")

(def ledge
  "A pillar of stone at [8 y 8] up to y 63 in a void: x and z 2 to 14 and y 40 to 63 are air but
   the pillar and a stair of cells going down from [8 63 9] (the burrow's shaft): every cell
   round the feet at [8 64 8] is open over a drop but the stair."
  (into
   {}
   (concat
    (for [x (range 2 15) z (range 2 15) y (range 40 64) :when (not= [8 8] [x z])] [[x y z] air])
    (for [y [63 62 61]] [[8 y 9] air]))))

(defn distant-world
  "A bot on a loaded stone floor with chunks east loaded, an enderman at pos, logged-state
   style: the 2026-11-05 rod bots' first sightings were 54 to 105 blocks off."
  [pos]
  (let [w (standing {7 (enderman pos)})]
    (reduce (fn [w cx] (assoc-in w [:world/chunks [cx 0]] (world/column {}))) w (range 1 6))))
