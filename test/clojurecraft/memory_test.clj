(ns clojurecraft.memory-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojurecraft.blocks :as blocks]
            [clojurecraft.fixtures :as fx]
            [clojurecraft.game :as game]
            [clojurecraft.memory :as memory]
            [clojurecraft.terrain :as terrain]
            [clojurecraft.world :as world]
            [datascript.core :as d]))

(def oak-y 137)                       ; oak_log, axis y
(def oak-x 136)                       ; oak_log, axis x

(defn seen
  "A world that observed each [pos id] in order, one millisecond apart."
  [& sightings]
  (reduce (fn [w [i [pos id]]] (memory/observe (assoc w :time/now i) pos id))
          (game/init fx/opts)
          (map-indexed vector sightings)))

(deftest positions-now-is-what-was-seen-and-not-seen-gone
  (let [w (seen [[0 64 0] oak-y] ; a log, later dug
                [[1 64 0] oak-y] ; a log, later seen as another log state
                [[2 64 0] oak-y] ; a log, never seen again
                [[0 64 0] blocks/air]
                [[1 64 0] oak-x])]
    (is (= #{[1 64 0] [2 64 0]} (set (memory/positions-now w blocks/log-states))))
    (testing "the history is still there: it was a log once"
      (is (= [oak-y blocks/air] (mapv :block/state (memory/history w [0 64 0])))))
    (testing "a place seen gone and then a log again counts"
      (let [w (memory/observe (assoc w :time/now 9) [0 64 0] oak-y)]
        (is (contains? (set (memory/positions-now w blocks/log-states)) [0 64 0]))))))

(deftest the-same-place-in-two-dimensions-is-two-places
  ;; ruststeve kept one map of columns and read fortress brick 64 blocks off: a sighting says the
  ;; dimension it was seen in, and every question is asked in the bot's
  (let [brick (blocks/default-state :nether_bricks)
        w (seen [[4 61 147] oak-y])
        nether (memory/observe (assoc w :player/dimension "minecraft:the_nether" :time/now 5)
                               [4 61 147]
                               brick)]
    (is (= brick (memory/remembered nether [4 61 147])) "in the Nether, the brick")
    (is (= oak-y
           (memory/remembered (assoc nether :player/dimension "minecraft:overworld") [4 61 147]))
        "back in the overworld, the log is still there")
    (is (= [[4 61 147]] (memory/positions-now nether [brick])))
    (is (empty? (memory/positions-now nether blocks/log-states)) "no log in the Nether")
    (is (= [oak-y] (mapv :block/state (memory/history w [4 61 147]))))
    (testing "a fact written before facts said a dimension is the overworld's"
      (let [old (update w
                        :world/facts
                        d/db-with
                        [{:sight/pos [9 64 9] :sight/state oak-y :sight/at 7}])]
        (is (= oak-y (memory/remembered old [9 64 9])))
        (is (nil? (memory/remembered (assoc old :player/dimension "minecraft:the_nether")
                                     [9 64 9])))))))

(deftest a-spawner-is-remembered-in-the-nether-only
  ;; issue #262: a Nether spawner is a fortress's, where the blaze rods come from; an overworld
  ;; one is a dungeon's, and the forest runs' memory stays as it was
  (let [spawner (blocks/default-state :spawner)
        load-in (fn [dim]
                  (terrain/put-column (assoc (game/init fx/opts) :time/now 3 :player/dimension dim)
                                      [0 0]
                                      (world/column {[3 70 3] spawner})))]
    (is (= [[3 70 3]] (memory/positions-now (load-in "minecraft:the_nether") [spawner])))
    (is (empty? (memory/positions-now (load-in "minecraft:overworld") [spawner])))
    (is (not (memory/watched? spawner)) "nor is it watched with no dimension said")))

;; ---------------------------------------------------------------- pools (issue #5)

(def pond-and-lava
  "A pond three wide at x 2..4, two deep, with a flowing edge cell; a lava pool at x 12 z 12
   under one lava block above it (the top is the surface); a lone cave source far below."
  (merge (into {} (for [x (range 2 5) y [62 63] z (range 4 7)] [[x y z] blocks/water-base]))
         {[5 63 5] (inc blocks/water-base)} ; flowing, not a source
         {[12 60 12] blocks/lava-base [12 61 12] blocks/lava-base} ; a lava column: 61 is its surface
         {[9 20 9] blocks/water-base}))                           ; cave water, 40 below

(defn loaded
  "A world after loading one chunk column of blocks at [0 0], at time now."
  [blocks now]
  (terrain/put-column (assoc (game/init fx/opts) :time/now now) [0 0] (world/column blocks)))

(deftest a-chunk-load-remembers-one-pool-per-fluid
  (let [w (loaded pond-and-lava 7)]
    (testing
      "the water pool is the highest surface source, nearest the chunk's centre (8, 8) among those"
      (is (= {[0 0] [4 63 6]} (memory/pools w :water))))
    (testing "the lava pool is the top of the column"
      (is (= {[0 0] [12 61 12]} (memory/pools w :lava))))
    (testing "two facts, not one per cell"
      (is (= 2 (count (d/datoms (:world/facts w) :aevt :pool/kind)))))
    (testing "loading the same column again adds nothing"
      (is (= 2
             (count (d/datoms (:world/facts
                               (terrain/put-column w [0 0] (world/column pond-and-lava)))
                              :aevt
                              :pool/kind)))))
    (testing "nearest-surface-water from an eye on the bank, and never the cave water"
      (is (= [4 63 6] (memory/nearest-surface-water w [8.5 65.62 8.5] 48)))
      (is (nil? (memory/nearest-surface-water w [8.5 75.0 8.5] 48))
          "a pool 12 below the eye is cave water to the bucket")
      (is (= [4 63 6] (memory/nearest-surface-water w [9.5 30.0 9.5] 48))
          "a pool above is walked up to"))
    (testing "a chunk with no fluid remembers no pool"
      (is (empty? (memory/pools (loaded {} 1) :water))))))

(deftest newest-dones-are-the-newest-done-facts-newest-first
  (let [walked
        (reduce
         (fn [w [id to]]
           (memory/remember-intention w {:intent/id id :intent/kind :walk :intent/target to} :done))
         (assoc (game/init fx/opts) :time/now 0)
         [[1 [0 64 0]] [2 [16 64 0]] [3 [32 64 0]]])
        w (memory/remember-intention walked
                                     {:intent/id 4 :intent/kind :walk :intent/target [48 64 0]}
                                     :failed)]
    (is (= [[32 64 0] [16 64 0]] (mapv :intention/target (memory/newest-dones w :walk 2)))
        "the last two ends walked to, the failed walk no arrival")
    (is (= [[32 64 0]] (mapv :intention/target (memory/newest-dones w :walk 1))))
    (is (= (:intention/target (memory/last-done w :walk)) [32 64 0]) "last-done is the first")
    (is (empty? (memory/newest-dones w :burrow 2)) "none done: none")))
