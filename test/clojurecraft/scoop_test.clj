(ns clojurecraft.scoop-test
  "Filling a bucket: which water the act walks to (never cave water), and the :scoop intent
   against a server that never answers."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojurecraft.blocks :as blocks]
            [clojurecraft.build :as build]
            [clojurecraft.fixtures :as fx]
            [clojurecraft.game :as game]
            [clojurecraft.intent :as intent]
            [clojurecraft.memory :as memory]
            [clojurecraft.recipe :as recipe]
            [clojurecraft.scoop :as scoop]
            [clojurecraft.stairs :as stairs]
            [clojurecraft.terrain :as terrain]
            [clojurecraft.world :as world]))

(use-fixtures :once fx/instrumented)

(def water blocks/water-base)

(def pond
  "Three water sources in the stone floor, x 10-12 at z 8, open to the sky."
  {[10 63 8] water [11 63 8] water [12 63 8] water})

(def cave-pool
  "Water at y 50 under the stone floor, with air over it: a cave pool, 14 under the eye."
  {[10 50 8] water [10 51 8] blocks/air [10 52 8] blocks/air})

(defn standing
  "On the floor at [x 64 z] over blocks, an empty bucket in hotbar slot 0 (held)."
  [blocks [x z]]
  (assoc (game/init fx/opts)
         :bot/phase :play
         :player/pos [x 64.0 z]
         :player/loaded? true
         :player/on-ground? true
         :player/inventory {0 {:item (recipe/item-id :bucket) :count 1}}
         :world/chunks {[0 0] (world/column blocks)}))

(deftest the-act-scoops-in-reach-walks-to-water-in-sight-and-refuses-cave-water
  (is (= {:intent/kind :scoop :intent/target [10 63 8]}
         (scoop/scoop-next (standing pond [8.5 8.5])))
      "in reach from the bank")
  (is (= {:intent/kind :walk :intent/target [10 63 8] :intent/for :scoop}
         (scoop/scoop-next (standing pond [3.5 8.5])))
      "in sight, out of reach: walk to it")
  (is (= {:plan/wait :no-water} (scoop/scoop-next (standing cave-pool [8.5 8.5])))
      "fourteen under the eye is cave water: never dived for")
  (is (= {:plan/wait :no-water}
         (scoop/scoop-next
          (assoc (standing pond [8.5 8.5]) :plan/blacklist #{[10 63 8] [11 63 8] [12 63 8]})))
      "a source that failed is not chosen again"))

(defn intent-step
  "The world reducer plus one run of the current intent per tick (no planner)."
  [w e]
  (let [w (game/step w e)]
    (if (and (= :tick (:event/kind e)) (:plan/intent w)) (intent/run w (:plan/intent w) e) w)))

(deftest a-pond-below-a-hill-is-surface-water
  ;; gym batch solid-b1 run 9 stood on a hill at y 70 over ponds at y 62: the rule that water more
  ;; than five under the eye is cave water refused every one, and it waited :no-water. Surface
  ;; water is water under the open sky, whatever the height
  (let [hill (merge pond ; a slope a block down a step, from y 74 at x 0 to the pond's bank
                    (into {} (for [x (range 0 10) y (range 64 (- 74 x)) z (range 16)] [[x y z] 1])))
        w (assoc (standing hill [0.5 8.5]) :player/pos [0.5 74.0 8.5])]
    (is (= [10 63 8] (first (scoop/sources w))))
    (is (= :walk (:intent/kind (scoop/scoop-next w))))))

(deftest a-scoop-the-server-never-answers-fails-no-scoop
  (let [w (assoc (standing pond [8.5 8.5])
                 :plan/intent
                 {:intent/kind :scoop :intent/target [10 63 8] :intent/status :active})
        [w fx] (fx/fold intent-step w (fx/ticks 50 12000))
        uses (filter #(= :use-item (:packet/name %)) (fx/packets fx))]
    (is (= :failed (:intent/status (:plan/intent w))))
    (is (= :no-scoop (:intent/reason (:plan/intent w))))
    (is (= scoop/max-tries (count uses)) "tried at the same source, then gave up")
    (testing "each scoop is aimed at the source by the look it carries"
      (is (every? #(< 30.0 (:x-rot %) 60.0) uses) "down toward the water")
      (is (apply < (map :sequence uses)) "a fresh sequence each"))))

(def low-ground
  "A chunk whose ground is three blocks lower than the floor elsewhere: y 61-63 dug out."
  (into {} (for [x (range 16) y (range 61 64) z (range 16)] [[x y z] blocks/air])))

(defn wide
  "Standing at [x 64 z] in a row of chunks from cx -3 to 3 at cz -2 to 2, the ones named in low
   three blocks lower, no water anywhere; landed at home."
  [[x z] low home]
  (assoc (standing {} [x z])
         :plan/go-at home
         :world/chunks
         (into {}
               (for [cx (range -3 4)
                     cz (range -2 3)]
                 [[cx cz] (world/column (if (contains? low [cx cz]) low-ground {}))]))))

(deftest with-no-water-known-the-hunt-walks-out-and-downhill
  (let [w (wide [8.5 8.5] #{[3 0]} [8.5 64.0 8.5])]
    (is (= {:intent/kind :walk :intent/target [48 61 8] :intent/for :water-hunt}
           (scoop/scoop-next w))
        "the lower ground, forty blocks east"))
  (testing "never back toward the landing"
    (let [w (wide [8.5 8.5] #{[-2 0]} [-31.5 64.0 8.5])
          [x _ _] (first (scoop/hunt-targets w))]
      (is (<= 8 x) "the low ground to the west is behind the bot: the hunt never goes back west")))
  (is (empty? (scoop/hunt-targets (standing {} [8.5 8.5]))) "nothing loaded out there: no hunt")
  (testing
    "never into a cave: race 38477665442 bot Clj_r15 hunted into a floor under a roof, climbed out to the pit it came from, and went between the two for an hour"
    (let [roofed (merge low-ground (into {} (for [x (range 16) z (range 16)] [[x 70 z] 1])))
          w (assoc-in (wide [8.5 8.5] #{[3 0]} [8.5 64.0 8.5])
             [:world/chunks [3 0]]
             (world/column roofed))]
      (is (every? #(terrain/under-sky? w %) (scoop/hunt-targets w)))
      (is (not= 3 (bit-shift-right (ffirst (scoop/hunt-targets w)) 4))
          "not the roofed low ground"))))

(deftest a-hunt-that-knows-a-far-pool-walks-toward-it
  ;; sim race 38806318231 runner 1 (issue #60, world R2/19): the hunt went outward and downhill,
  ;; west, away from surface pools a hundred blocks east it had remembered, to the captured
  ;; world's edge, and waited :no-water; runner 8 (world R4/29) stood in the world's corner with
  ;; pools toward its landing and every bearing that way refused as homeward
  (let [remembered #(memory/remember-pools % [-8 0] (world/column {[8 63 8] water}))
        west {:intent/kind :walk :intent/target [-32 64 8] :intent/for :water-hunt}
        w (remembered (wide [8.5 8.5] #{[3 0]} [8.5 64.0 8.5]))
        away (wide [8.5 8.5] #{} [-100.5 64.0 8.5])]
    (is (= [[-120 63 8]] (vals (memory/pools w :water))) "a pool remembered, its chunk not loaded")
    (is (empty? (scoop/pools-near w)) "past a walk to it")
    (is (= west (scoop/scoop-next w)) "west toward the pool, not down to the low ground east")
    (is (not= west (scoop/scoop-next away)) "with no pool known, never back toward the landing")
    (is (= west (scoop/scoop-next (remembered away)))
        "with the pool known that way, homeward toward it")))

(deftest a-far-pool-no-hunt-reaches-is-cut-toward
  ;; sim race 38811426215 runner 8 (issue #60, world R4/29): in the world's corner, pools 150
  ;; blocks off and under it, no hunt with a route, the bot climbed its pit and waited :no-water
  ;; with the pool remembered and no cut toward it
  (let [far (fn [y]
              (memory/remember-pools (standing {} [8.5 8.5]) [-8 0] (world/column {[8 y 8] water})))
        level (scoop/scoop-next (far 63))
        down (scoop/scoop-next (far 55))
        deep (assoc (far 55) :plan/go-at [8.5 90.0 8.5])]
    (is (empty? (scoop/pools-near (far 63))) "past pool-radius")
    (is (= [:tunnel :bank [-120 63 8]] ((juxt :intent/kind :intent/for :intent/target) level))
        "level with the bank: a tunnel toward it")
    (is (= [:stairs-down :bank [-120 55 8]] ((juxt :intent/kind :intent/for :intent/target) down))
        "the pool lower: stairs down toward it")
    (is (= :stairs-down (:intent/kind (scoop/scoop-next deep)))
        "deep under the landing, the way down is not undone by a climb")))

(deftest water-the-eyes-cannot-reach-is-no-target
  ;; live iron-kit run 3 (local landing 2360,2360): three scoops at aquifer sources with stone
  ;; over them, beside the bot at y 23, each acked by the server and none filling the bucket
  (is (= {:plan/wait :no-water}
         (scoop/scoop-next (standing {[10 64 8] water [10 65 8] 1} [8.5 8.5])))
      "a pocket under stone: no surface to look into")
  (is (= {:intent/kind :walk :intent/target [10 63 8] :intent/for :scoop}
         (scoop/scoop-next (standing {[10 63 8] water [9 64 8] 1 [9 65 8] 1 [9 66 8] 1} [8.5 8.5])))
      "open to the sky, in reach, behind a wall: the ray meets the wall, so round it to the bank")
  (is (= {:intent/kind :scoop :intent/target [10 63 8]}
         (scoop/scoop-next (standing {[10 63 8] water} [8.5 8.5])))
      "the same source with the wall gone"))

(deftest a-plant-on-the-ray-is-a-wall
  ;; race 38477665442 (issue #83): seven bots at beaches sent three scoops each at sources the
  ;; server acked and never filled, all at y 63 by the sea, where sugar cane grows on the sand.
  ;; The game's ray for a bucket stops at any block with an outline: the cane over a source, or
  ;; a grass tuft on the bank between the eyes and the water
  (let [cane (blocks/default-state :sugar_cane)
        grass (blocks/default-state :short_grass)]
    (is (= {:plan/wait :no-water}
           (scoop/scoop-next (standing {[10 63 8] water [10 64 8] cane} [8.5 8.5])))
        "cane over the source: no surface to look into")
    (is (= {:intent/kind :walk :intent/target [10 63 8] :intent/for :scoop}
           (scoop/scoop-next (standing {[10 63 8] water [9 64 8] grass} [8.5 8.5])))
        "a grass tuft on the bank between: round it")
    (is (= {:intent/kind :scoop :intent/target [10 63 8]}
           (scoop/scoop-next (standing {[10 63 8] water [9 64 8] grass} [10.5 9.5])))
        "from the other bank the ray is clear")))

(deftest a-remembered-pool-under-stone-is-no-walk
  ;; gym batch water-a3 run 5: the pool memory kept was a source under stone, in reach; the walk
  ;; to it ended the tick it began and began again, every tick, for four minutes
  (let [blocks {[10 64 8] water [10 65 8] 1}
        w (memory/remember-pools (standing blocks [8.5 8.5]) [0 0] (world/column blocks))]
    (is (= [[10 64 8]] (vals (memory/pools w :water))) "memory keeps it: no water above it")
    (is (= {:plan/wait :no-water} (scoop/scoop-next w)))))

(def mine
  "A stone hill from y 64 to 75 with a one-wide staircase cut down it along +x from [4 76 8] to
   [14 66 8]: feet cells [x (80 - x) 8] with the two above each, as stairs-down opens them."
  (merge (into {} (for [x (range 16) y (range 64 76) z (range 16)] [[x y z] 1]))
         (into {} (for [x (range 4 15) dy [0 1 2]] [[x (+ (- 80 x) dy) 8] blocks/air]))))

(deftest deep-in-the-mine-with-no-water-the-bot-climbs-out-first
  ;; gym batch iron-a1 runs 3 and 5: the bucket was made down in the mine, where no water was in
  ;; sight and the hunt looked for ground near the bot's own height; both failed :no-water
  (let [w (assoc (standing mine [14.5 8.5]) :player/pos [14.5 66.0 8.5] :plan/go-at [4.5 76.0 8.5])]
    (is (= {:intent/kind :walk :intent/target [4 76 8] :intent/for :surface} (scoop/scoop-next w))))
  (let [w (assoc (standing mine [14.5 8.5]) :player/pos [14.5 66.0 8.5])
        i (scoop/scoop-next w)]
    (is (= :walk (:intent/kind i)) "no landing to walk back to: the stair already cut")
    (is (= :opening (:intent/for i)))
    (is (terrain/under-sky? w (:intent/target i)))))

(deftest inside-a-hill-at-the-landings-height-the-bot-cuts-out
  ;; live run 17 climbed out of its mine by stairs to the landing's height, y 65, which 145 blocks
  ;; east was inside a hill: a pond 22 blocks off, no route to it or to any hunt, and it waited
  ;; :no-water
  (let [hill (merge (into {} (for [x (range 16) y (range 64 76) z (range 16)] [[x y z] 1]))
                    {[8 70 8] blocks/air [8 71 8] blocks/air})
        w (assoc (standing hill [8.5 8.5]) :player/pos [8.5 70.0 8.5] :plan/go-at [8.5 70.0 8.5])]
    (is (nil? (scoop/climb w)) "not under the landing")
    (is (= {:intent/kind :stairs-up :intent/to-y (+ 70 stairs/climb-stairs) :intent/for :surface}
           (scoop/scoop-next w))
        "stairs up until out of the ground, a length at a time")))

(deftest the-way-home-runs-out-of-loaded-terrain
  ;; gym batch solid-b1 run 1 climbed under the sea 146 blocks from its landing, where the chunks
  ;; round the landing had unloaded: its own tunnel home ran to the edge of what was loaded, the
  ;; route came back :none, and it cut stairs up under the sea until :boxed five times. A tunnel to
  ;; the chunk's edge, the landing in a chunk not loaded: walk home along it
  (let [rock (merge (into {} (for [x (range 16) y (range 64 72) z (range 16)] [[x y z] 1]))
                    (into {} (for [x (range 2 16) y [64 65]] [[x y 8] blocks/air])))
        w (assoc (standing rock [3.5 8.5]) :plan/go-at [40.5 80.0 8.5])]
    (is (= {:intent/kind :walk :intent/target [40 80 8] :intent/for :surface} (scoop/climb w)))))

(deftest in-a-pit-with-no-way-out-the-bot-tunnels-out
  ;; gym batch solid-b1 run 13 came up its stairs into a pit under the open sky, leaves and rock
  ;; round it with no room to jump out: no hunt had a route, and it waited :no-water
  (let [shaft (merge (into {} (for [x (range 16) y (range 64 71) z (range 16)] [[x y z] 1]))
                     (into {} (for [y (range 64 71)] [[8 y 8] blocks/air])))
        w (standing shaft [8.5 8.5])]
    (is (= {:intent/kind :tunnel
            :intent/dir [1 0]
            :intent/length scoop/tunnel-out-length
            :intent/for :water-hunt}
           (scoop/scoop-next w)))))

(deftest deep-in-a-pit-under-the-sky-the-bot-climbs-to-the-landings-height
  ;; race 38477665442 (issue #60): four bots stood at y 6 at the bottom of one ravine, sixty
  ;; under their landing, the sky open over them; the climb out applies only under the ground,
  ;; no hunt had a route, nothing lay to tunnel toward, and they waited :no-water out
  (let [shaft (merge (into {} (for [x (range 16) y (range 64 71) z (range 16)] [[x y z] 1]))
                     (into {} (for [y (range 64 71)] [[8 y 8] blocks/air])))
        w (assoc (standing shaft [8.5 8.5]) :plan/go-at [8.5 80.0 8.5])]
    (is (nil? (scoop/climb w)) "under the sky: not the climb out of the ground")
    (is
     (= {:intent/kind :stairs-up
         :intent/to-y (stairs/climb-to w 80)
         :intent/for :surface
         :intent/to-height? true}
        (scoop/scoop-next w))
     "stairs up toward the landing's height, a length at a time, the open sky over the pit notwithstanding")
    (is (= :tunnel (:intent/kind (scoop/scoop-next (assoc w :plan/go-at [8.5 70.0 8.5]))))
        "six under the landing is not deep: the tunnel out, as before")))

(deftest with-no-way-back-up-the-bot-cuts-stairs-up
  ;; batch iron-a2 run 2 mined an iron ore out of the floor under one of its stairs, run 5 dug a
  ;; niche under one: the staircase down was no way back up
  (let [broken (assoc mine [10 69 8] blocks/air) ; the floor under the stair at [10 70 8]
        w
        (assoc (standing broken [14.5 8.5]) :player/pos [14.5 66.0 8.5] :plan/go-at [4.5 76.0 8.5])]
    (is (= {:intent/kind :stairs-up :intent/to-y (+ 66 stairs/climb-stairs) :intent/for :surface}
           (scoop/scoop-next w))
        "toward the landing at 76, a length at a time"))
  (testing "under the open sky the bot is out, however low: it hunts instead"
    (let [w (assoc (standing {} [8.5 8.5]) :plan/go-at [8.5 80.0 8.5])]
      (is (nil? (scoop/climb w))))))

(defn deep-in
  "Standing at [5.5 40 5.5], 24 under the landing, in a pocket two high cut in the stone, over
   blocks."
  [blocks]
  (assoc (standing (merge {[5 40 5] blocks/air [5 41 5] blocks/air} blocks) [5.5 5.5])
         :player/pos [5.5 40.0 5.5]
         :plan/go-at [5.5 64.0 5.5]))

(deftest deep-under-water-the-bucket-is-filled-from-below
  ;; sim race 38662908438 (issue #61): climbs for water under a lake or the sea were :boxed
  ;; three times with the water they needed in reach or over the head
  (testing "a source beside the pocket, stone over it: scooped from the side"
    (is (= {:intent/kind :scoop :intent/target [6 41 5] :intent/aim [6.5 41.5 5.5]}
           (scoop/scoop-next (deep-in {[6 41 5] water})))))
  (testing "a lake two blocks of rock over the head: dug up through its bed, bottom first"
    (let [i (scoop/scoop-next (deep-in {[5 44 5] water}))]
      (is (= [:dig [5 42 5] true] [(:intent/kind i) (:intent/target i) (:intent/exposes-ok? i)]))))
  (testing "the bed dug: the source over the head is in sight, scooped from below"
    (is (= {:intent/kind :scoop :intent/target [5 44 5] :intent/aim [5.5 44.5 5.5]}
           (scoop/scoop-next (deep-in {[5 42 5] blocks/air [5 43 5] blocks/air [5 44 5] water})))))
  (testing
    "a climb out to the surface that is boxed ends, its cell remembered, and is not cut again"
    (let [w (-> (deep-in {})
                (assoc :plan/intent
                       {:intent/kind :stairs-up :intent/for :surface :intent/status :active})
                stairs/box)]
      (is (= :done (get-in w [:plan/intent :intent/status])))
      (is (contains? (:plan/boxed-at w) [5 40 5]))
      (is (nil? (scoop/climb w))))))

(deftest water-access-does-not-require-the-beds-drop
  (let [ore (first (blocks/states-where #(= :copper_ore (first %))))]
    (doseq [tools [{} {1 {:item (recipe/item-id :wooden_pickaxe) :count 1}}]]
      (let [w (update (deep-in {[5 42 5] ore [5 43 5] water}) :player/inventory merge tools)
            i (scoop/from-below w)
            [w effects] (fx/fold intent-step (assoc w :plan/intent i) (fx/ticks 50 1500))]
        (is (:intent/drop-ok? i) "only the opening is needed, not the ore")
        (is (= :digging (get-in w [:plan/intent :intent/stage])))
        (is (not= :failed (get-in w [:plan/intent :intent/status])))
        (is (some #(= :player-action (:packet/name %)) (fx/packets effects))
            "the bed is broken even without a pickaxe that harvests it")))
    (doseq [tool [:stone_pickaxe :wooden_pickaxe]]
      (let [w (assoc-in (deep-in {[5 42 5] ore [5 43 5] water})
               [:player/inventory 15]
               {:item (recipe/item-id tool) :count 1})]
        (is (= {:intent/kind :equip :intent/item tool} (scoop/from-below w))
            "a stowed spare is equipped before opening the bed")))))

(deftest a-boxed-cut-toward-the-bank-gives-that-water-up
  ;; race 38805762352 runner 2 (issue #61): bots Clj_r18 (in a cave at y 30) and Clj_r22 (on the
  ;; steps its climb had laid in a cavern) tunnelled toward water no route reached, each tunnel
  ;; :boxed three times in a row while the cut kept the climb out from being chosen
  (let [w (deep-in {[12 40 5] water [12 41 5] blocks/air})
        cut (scoop/scoop-next w)
        boxed (stairs/box (assoc w :plan/intent (assoc cut :intent/status :active)))]
    (is (= [:bank [12 40 5]] [(:intent/for cut) (:intent/target cut)])
        "a cut toward the water seen, naming it")
    (is (= :done (get-in boxed [:plan/intent :intent/status])) "boxed, it ends: no attempt counted")
    (is (contains? (:plan/blacklist boxed) [12 40 5]) "and gives that water up")
    (is (= :surface (:intent/for (scoop/scoop-next boxed))) "so the climb out is chosen next")))

(deftest a-ray-past-a-blocks-edge-is-a-wall
  ;; race 38759618225 runner 4 (issue #83): four bots held the bucket and sent nine scoops each
  ;; along lines that passed within 0.03 of a block's edge; the server's exact ray stopped there
  ;; and filled nothing, while samples of the line stepped past the edge
  (let [src [10 63 10]
        w (standing {src water [9 65 8] 1} [8.7 8.7])]
    (is
     (= [10.25 63.85 10.5] (scoop/surface-aim w src))
     "the line to the surface's centre runs along the stone's edge: a quarter block west is clear")
    (is (= {:intent/kind :scoop :intent/target src} (scoop/scoop-next w))))
  (is (= :walk
         (:intent/kind (scoop/scoop-next (standing {[10 63 10] water [9 65 8] 1 [8 65 9] 1}
                                                   [8.7 8.7]))))
      "both edges walled: no point of the surface in sight, round to the bank")
  (is (nil? (scoop/seen-source (assoc (deep-in {[6 41 5] water}) :plan/blacklist #{[6 41 5]})))
      "a source given up is not scooped from the side again"))

(deftest a-bank-tunnel-that-comes-no-nearer-gives-the-pond-up
  ;; sim race 38839767273 runner 6 (issue #69, worlds R3/8 and R4/35): the tunnel toward a bank
  ;; walked an open corridor past its water, eight steps east, eight back, two thousand tunnels
  ;; until the run's ninety minutes were spent
  (let [pond {[10 63 8] water [11 63 8] water}
        wall (into pond (for [z (range 16) y [64 65]] [[9 y z] 1]))
        w (standing wall [8.5 8.5])
        tunnel (fn [from]
                 {:intent/kind :tunnel
                  :intent/for :bank
                  :intent/target [10 63 8]
                  :intent/from-feet from
                  :intent/dir [1 0]
                  :intent/length 8
                  :intent/level-steps 8
                  :intent/stage :plan
                  :intent/status :active})
        ran #(let [i (tunnel %)]
               (intent/run (assoc w :plan/intent i)
                           i
                           {:event/kind :tick :event/now 1000 :event/rand 0.5}))]
    (let [w (ran [8 64 8])]
      (is (= :done (get-in w [:plan/intent :intent/status])))
      (is (= #{[10 63 8] [11 63 8]} (:plan/blacklist w))
          "no nearer than it began, and walled off: the pond given up"))
    (is (empty? (:plan/blacklist (ran [2 64 8]))) "six blocks nearer: the water kept")
    (let [i (tunnel [8 64 8])
          open (intent/run (assoc (standing pond [8.5 8.5]) :plan/intent i)
                           i
                           {:event/kind :tick :event/now 1000 :event/rand 0.5})]
      ;; sim world R4/30 seed 1 (issue #69's second round): the tunnel had opened the walk to the
      ;; pond's bank, and the pond was given up all the same
      (is (empty? (:plan/blacklist open)) "no nearer, but a walk to the bank has a route: kept"))))

(deftest the-refills-ray-past-a-blocks-edge-is-a-wall
  ;; race 38839564087 runner 1 (issue #225): bots Clj_r12, Clj_r19 and Clj_r21 sent nine scoops each
  ;; at a lava source from the station along lines within 0.03 of a block's edge, and the build
  ;; failed :no-scoop
  (let [src [10 63 10]
        w (standing {src blocks/lava-base [9 65 8] 1} [8.7 8.7])]
    (is (= [9 65 8] (build/sight-block w src)) "the line to the centre runs along the stone's edge")
    (is (= [10.25 63.85 10.5] (build/lava-aim w src)) "a quarter block west of it is clear")
    (is (=
         {:intent/kind :scoop :intent/target src :intent/fluid :lava :intent/aim [10.25 63.85 10.5]}
         (build/scoop-at-station w [src]))
        "scooped along the clear ray, nothing dug")))
