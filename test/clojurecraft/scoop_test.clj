(ns clojurecraft.scoop-test
  "Filling a bucket: which water the act walks to (never cave water), and the :scoop intent
   against a server that never answers."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojurecraft.blocks :as blocks]
            [clojurecraft.fixtures :as fx]
            [clojurecraft.game :as game]
            [clojurecraft.intent :as intent]
            [clojurecraft.memory :as memory]
            [clojurecraft.recipe :as recipe]
            [clojurecraft.scoop :as scoop]
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
  (is (empty? (scoop/hunt-targets (standing {} [8.5 8.5]))) "nothing loaded out there: no hunt"))

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
  (is (= {:plan/wait :no-water}
         (scoop/scoop-next (assoc (standing mine [14.5 8.5]) :player/pos [14.5 66.0 8.5])))
      "no landing to climb to"))

(deftest with-no-way-back-up-the-bot-cuts-stairs-up
  ;; batch iron-a2 run 2 mined an iron ore out of the floor under one of its stairs, run 5 dug a
  ;; niche under one: the staircase down was no way back up
  (let [broken (assoc mine [10 69 8] blocks/air) ; the floor under the stair at [10 70 8]
        w
        (assoc (standing broken [14.5 8.5]) :player/pos [14.5 66.0 8.5] :plan/go-at [4.5 76.0 8.5])]
    (is (= {:intent/kind :stairs-up :intent/to-y 76 :intent/for :surface} (scoop/scoop-next w))))
  (testing "under the open sky the bot is out, however low: it hunts instead"
    (let [w (assoc (standing {} [8.5 8.5]) :plan/go-at [8.5 80.0 8.5])]
      (is (nil? (scoop/climb w))))))
