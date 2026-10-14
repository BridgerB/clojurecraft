(ns clojurecraft.fluid-test
  "Water and lava in the server model as the server moves them (issue #125): the rules of
   clojurecraft.fluid, ticked by sim. Each world is one chunk column, stone below y 64, air above,
   so a fluid stops at the column's edge as at an unloaded chunk."
  (:require [clojure.test :refer [deftest is testing]]
            [clojurecraft.blocks :as blocks]
            [clojurecraft.fluid :as fluid]
            [clojurecraft.sim :as sim]
            [clojurecraft.world :as world]))

(defn sim-over
  "A model of one column over cells (stone below y 64 but where cells say otherwise)."
  [cells]
  (sim/init {:column (world/column-bytes cells) :spawn [8.5 64.0 8.5]}))

(defn after
  "The model ms later, a game tick at a time."
  [sim ms]
  (let [t0 (:sim/now sim)] (reduce (fn [s t] (sim/tick s t)) sim (range (+ t0 50) (+ t0 ms 1) 50))))

(defn put
  "The model with state set at p, as a pour or a dig sets a cell."
  [sim p state]
  (sim/set-cell sim p state))

(defn fluid-at "The fluid state at p." [sim p] (fluid/state-of (sim/block-at sim p)))
(defn amount-at
  "How much of kind is at p, 0 for none."
  [sim p kind]
  (let [fs (fluid-at sim p)] (if (= kind (:fluid/kind fs)) (:fluid/amount fs) 0)))
(defn name-at "The name of the block at p." [sim p] (blocks/name-of (sim/block-at sim p)))

(deftest every-water-and-lava-state-round-trips
  (doseq [id (concat blocks/water-states blocks/lava-states)]
    (is (= id (fluid/block-state (fluid/state-of id))) (pr-str id (fluid/state-of id)))))

(deftest water-spreads-seven-blocks-on-a-floor
  (let [sim (after (put (sim-over {}) [8 64 8] blocks/water-base) 3000)]
    (is (:fluid/source? (fluid-at sim [8 64 8])) "the source stays")
    (is (= [7 6 5 4 3 2 1] (for [x (range 9 16)] (amount-at sim [x 64 8] :water)))
        "one less a block")
    (is (= 6 (amount-at sim [9 64 9] :water)) "two blocks off, round the corner")
    (is (= 0 (amount-at sim [8 65 8] :water)) "never up")))

(deftest a-flow-recedes-when-its-source-is-taken
  (let [sim (-> (sim-over {})
                (put [8 64 8] blocks/water-base)
                (after 3000)
                (put [8 64 8] blocks/air)
                (after 6000))]
    (is (every? #(= blocks/air (sim/block-at sim [% 64 8])) (range 0 16)))
    (is (every? #(= blocks/air (sim/block-at sim [8 64 %])) (range 0 16)))))

(deftest water-falls-down-a-shaft-and-spreads-where-it-lands
  (let [shaft (into {} (for [y (range 59 64)] [[8 y 8] blocks/air]))
        cave (into {} (for [x (range 4 13)] [[x 58 8] blocks/air]))
        placed (put (sim-over (merge shaft cave)) [8 64 8] blocks/water-base)
        first-tick (after placed 300)
        sim (after placed 4000)]
    (is (:fluid/falling? (fluid-at first-tick [8 63 8])) "its first tick goes down")
    (is (= 0 (amount-at first-tick [9 64 8] :water)) "and only down, while the cell below is open")
    (is (every? #(:fluid/falling? (fluid-at sim [8 % 8])) (range 58 64)) "falling all the way down")
    (is (= 7 (amount-at sim [9 58 8] :water)) "a falling flow passes on seven where it lands")
    (is
     (= 7 (amount-at sim [9 64 8] :water))
     "once its own water is below, a source spreads on the level too (WaterFluid.canBeReplacedWith)")))

(deftest a-flow-goes-toward-the-nearest-way-down
  (let [sim (after (put (sim-over {[10 63 8] blocks/air}) [8 64 8] blocks/water-base) 300)]
    (is (= 7 (amount-at sim [9 64 8] :water)) "toward the hole two blocks east")
    (is (every? #(= blocks/air (sim/block-at sim %)) [[7 64 8] [8 64 9] [8 64 7]])
        "and only that way")))

(deftest two-sources-beside-a-flow-make-it-a-source
  (let [pool (into {} (for [x [8 9] z [8 9]] [[x 63 z] blocks/air]))
        sim (-> (sim-over pool)
                (put [8 63 8] blocks/water-base)
                (put [9 63 9] blocks/water-base)
                (after 1000))]
    (is (every? #(:fluid/source? (fluid-at sim %)) (for [x [8 9] z [8 9]] [x 63 z]))
        "the 2x2 is four sources")))

(deftest lava-spreads-three-blocks-at-its-own-pace
  (let [placed (put (sim-over {}) [8 64 8] blocks/lava-base)]
    (testing "a block every 1.5 s, not water's 0.25"
      (let [sim (after placed 1600)]
        (is (= 6 (amount-at sim [9 64 8] :lava)))
        (is (= 0 (amount-at sim [10 64 8] :lava)))))
    (testing "two less a block, three blocks out"
      (let [sim (after placed 6000)]
        (is (= [6 4 2 0] (for [x (range 9 13)] (amount-at sim [x 64 8] :lava))))))))

(deftest lava-in-a-hole-cut-into-the-rock-stays-there
  (let [sim (after (put (sim-over {[8 63 8] blocks/air}) [8 63 8] blocks/lava-base) 6000)]
    (is (:fluid/source? (fluid-at sim [8 63 8])))
    (is (every? #(= blocks/air (sim/block-at sim %)) [[8 64 8] [9 64 8] [7 64 8]])
        "the rock holds it")))

(deftest water-directly-above-a-lava-source-makes-obsidian
  (let [sim (-> (sim-over {[8 63 8] blocks/air})
                (put [8 63 8] blocks/lava-base)
                (put [8 64 8] blocks/water-base))]
    (is (= :obsidian (name-at sim [8 63 8])) "at once, not on a tick")))

(deftest water-beside-lava-mixes-by-what-the-lava-is
  (testing "a source beside water: obsidian"
    (let [sim (-> (sim-over {})
                  (put [8 64 8] blocks/lava-base)
                  (put [9 64 8] blocks/water-base))]
      (is (= :obsidian (name-at sim [8 64 8])))))
  (testing "a flow beside water: cobblestone"
    (let [flow (fluid/block-state (fluid/flowing :lava 6 false))
          sim (-> (sim-over {})
                  (put [8 64 8] flow)
                  (put [9 64 8] blocks/water-base))]
      (is (= :cobblestone (name-at sim [8 64 8]))))))

(deftest lava-flowing-down-into-water-makes-the-water-stone
  (let [sim (-> (sim-over {})
                (put [8 64 8] blocks/water-base)
                (put [8 66 8] blocks/lava-base)
                (after 3500))]
    (is (= :stone (name-at sim [8 64 8])))))
