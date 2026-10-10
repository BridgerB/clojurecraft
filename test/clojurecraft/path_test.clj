(ns clojurecraft.path-test
  "The pathfinder over fixture worlds built from one chunk column (stone below y 64, air above).
   Every route it returns is checked the same way: no waypoint is water, lava, awkward or
   unknown, every waypoint is standable, and consecutive waypoints differ by exactly one move."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojurecraft.fixtures :as fx]
            [clojurecraft.game :as game]
            [clojurecraft.path :as path]
            [clojurecraft.spec]
            [clojurecraft.world :as world]))

(use-fixtures :once fx/instrumented)

(def stone 1)
(def water 86)
(def lava 110)
(def oak-slab 13332)
(def oak-stairs 3908)
(def short-grass 2248)
(def oak-log 136)

(defn world-of
  "A play world standing at pos over a column built from blocks ({[x y z] id}, x and z 0-15)."
  [blocks pos]
  (-> (game/init fx/opts)
      (assoc :bot/phase :play :player/pos pos :player/loaded? true :player/on-ground? true)
      (assoc :world/chunks {[0 0] (world/column blocks)})))

(defn fill
  "{[x y z] id} for every cell with x in xs, y in ys, z in zs."
  [id xs ys zs]
  (into {} (for [x xs y ys z zs] [[x y z] id])))

(defn one-move-apart?
  "Do a and b differ by exactly one move of `path/moves` (a drop may land further down)?"
  [[ax ay az] [bx by bz]]
  (let [dx (- bx ax) dz (- bz az) dy (- by ay)]
    (and (<= (abs dx) 1) (<= (abs dz) 1) (not= [dx dz] [0 0])
         (or (= dy 1) (= dy 0) (<= (- path/max-fall) dy -1)))))

(defn sound-route?
  "Every waypoint standable and sound, consecutive waypoints one move apart."
  [w from {:path/keys [waypoints]}]
  (and (every? #(path/standable? w %) waypoints)
       (every? #(not (#{:water :lava :awkward :unknown} (path/classify w %))) waypoints)
       (every? (fn [[a b]] (one-move-apart? a b)) (partition 2 1 (cons from waypoints)))))

;; ---------------------------------------------------------------- cells

(deftest cells-are-classified-for-a-route
  (is (= :solid (path/classify-id stone)))
  (is (= :water (path/classify-id water)))
  (is (= :lava (path/classify-id lava)))
  (is (= :awkward (path/classify-id oak-slab)))
  (is (= :awkward (path/classify-id oak-stairs)))
  (is (= :clear (path/classify-id short-grass)) "a plant is walked through")
  (is (= :clear (path/classify-id 0)) "air")
  (is (= :unknown (path/classify-id nil)) "an unloaded chunk")
  (let [w (world-of {} [0.5 64.0 0.5])]
    (is (= :solid (path/classify w [3 63 3])))
    (is (= :clear (path/classify w [3 64 3])))
    (is (= :unknown (path/classify w [40 64 3])) "beyond the one loaded column")))

;; ---------------------------------------------------------------- moves

(deftest neighbours-on-a-flat-floor-are-the-eight-around
  (let [w (world-of {} [0.5 64.0 0.5])
        ns (path/neighbors w [5 64 5])]
    (is (= 8 (count ns)))
    (is (= #{[4 64 5] [6 64 5] [5 64 4] [5 64 6] [4 64 4] [4 64 6] [6 64 4] [6 64 6]} (set (map :node ns))))
    (is (= #{:walk :diagonal} (set (map :move ns))))))

(deftest a-wall-ahead-is-a-jump-up-and-a-two-high-wall-is-not
  (let [w (world-of {[6 64 5] stone} [0.5 64.0 0.5])
        ns (path/neighbors w [5 64 5])]
    (is (some #(= [[6 65 5] :jump-up] [(:node %) (:move %)]) ns) "one block up over the step")
    (is (not-any? #(= [6 64 5] (:node %)) ns) "not into the step"))
  (let [w (world-of {[6 64 5] stone [6 65 5] stone} [0.5 64.0 0.5])]
    (is (not-any? #(= 6 (first (:node %))) (path/neighbors w [5 64 5])) "a two-high wall is a wall")))

(deftest an-edge-is-a-drop-to-the-floor-below-within-max-fall
  (let [ground (fill stone (range 0 16) [64 65] (range 0 16))
        pit (fill 0 [6 7] [64 65] (range 0 16))
        w (world-of (merge ground pit) [0.5 66.0 0.5])
        ns (path/neighbors w [5 66 5])]
    (is (some #(= [[6 64 5] :drop] [(:node %) (:move %)]) ns) "two blocks down onto stone")
    (is (= (+ path/walk-cost (* 2 path/fall-cost-per-block)) (:cost (first (filter #(= :drop (:move %)) ns))))))
  (testing "too far is refused"
    (let [ground (fill stone (range 0 16) (range 64 69) (range 0 16))
          pit (fill 0 [6 7] (range 64 69) (range 0 16))
          w (world-of (merge ground pit) [0.5 69.0 0.5])]
      (is (not-any? #(= :drop (:move %)) (path/neighbors w [5 69 5])) "a 5-block fall is never offered"))))

(deftest lava-beside-or-under-a-cell-forbids-every-move-into-it
  (let [w (world-of {[7 64 5] lava} [0.5 64.0 0.5])
        ns (path/neighbors w [5 64 5])]
    (is (not-any? #(= [6 64 5] (:node %)) ns) "the cell beside the lava")
    (is (not-any? #(= [6 64 4] (:node %)) ns) "nor the diagonal next to it")
    (is (some #(= [4 64 5] (:node %)) ns) "away from it is fine"))
  (let [w (world-of {[6 63 5] lava [6 64 5] 0} [0.5 64.0 0.5])]
    (is (not-any? #(= 6 (first (:node %))) (path/neighbors w [5 64 5])) "a drop onto lava is refused")))

(deftest water-is-a-wall-never-a-floor
  (let [w (world-of {[6 64 5] water} [0.5 64.0 0.5])]
    (is (not-any? #(= [6 64 5] (:node %)) (path/neighbors w [5 64 5]))))
  (let [w (world-of {[6 63 5] water} [0.5 64.0 0.5])]
    (is (not-any? #(= [6 64 5] (:node %)) (path/neighbors w [5 64 5])) "nor stood on")))

;; ---------------------------------------------------------------- fixtures

(def cliff
  "A plateau at y 70 for x ≤ 7 with a 3-step stair down at x 8..10; the ground beyond is y 64."
  (merge (fill stone (range 0 8) (range 64 70) (range 0 16))
         (fill stone [8] (range 64 68) (range 0 16))
         (fill stone [9] (range 64 66) (range 0 16))))

(deftest a-cliff-is-descended-by-its-stair-never-by-the-six-block-drop
  (let [w (world-of cliff [2.5 70.0 2.5])
        r (path/plan w [2 70 2] {:goal/kind :block :goal/pos [13 64 2]})]
    (is (= :found (:path/status r)))
    (is (sound-route? w [2 70 2] r))
    (is (= [13 64 2] (last (:path/waypoints r))))
    (is (every? (fn [[[_ ay _] [_ by _]]] (<= (- ay by) 2)) (partition 2 1 (:path/waypoints r)))
        "no step down is more than the 2-block drops the stair offers")))

(def gap
  "A 2-deep trench along x = 6 (z 0..13), bridged only by a ramp at z 14..15."
  (fill 0 [6] [62 63] (range 0 14)))

(deftest a-trench-is-crossed-at-its-ramp-or-not-at-all
  (let [w (world-of gap [2.5 64.0 2.5])
        r (path/plan w [2 64 2] {:goal/kind :block :goal/pos [12 64 2]})]
    (is (= :found (:path/status r)))
    (is (sound-route? w [2 64 2] r))
    (is (some (fn [[x _ z]] (and (= x 6) (>= z 14))) (:path/waypoints r)) "it crosses on the ramp"))
  (let [w (world-of (merge gap (fill 0 [6] [62 63] [14 15])) [2.5 64.0 2.5])
        r (path/plan w [2 64 2] {:goal/kind :block :goal/pos [12 64 2]})]
    (is (= :none (:path/status r)) "with the ramp gone there is no route (no parkour, no digging)")
    (is (sound-route? w [2 64 2] r) "the partial route it returns is still sound")))

(def lake
  "Water over x 4..10 for z 1..15, leaving a dry strip at z 0."
  (fill water (range 4 11) [64] (range 1 16)))

(deftest a-lake-is-skirted-on-its-dry-strip-and-never-waded
  (let [w (world-of lake [1.5 64.0 8.5])
        r (path/plan w [1 64 8] {:goal/kind :block :goal/pos [13 64 8]})]
    (is (= :found (:path/status r)))
    (is (sound-route? w [1 64 8] r))
    (is (not-any? (fn [p] (= :water (path/classify w p))) (:path/waypoints r)))
    (is (some (fn [[x _ z]] (and (<= 4 x 10) (= z 0))) (:path/waypoints r)) "along the strip"))
  (let [w (world-of (merge lake (fill water (range 4 11) [64] [0])) [1.5 64.0 8.5])
        r (path/plan w [1 64 8] {:goal/kind :block :goal/pos [13 64 8]})]
    (is (= :none (:path/status r)) "with the strip flooded there is no way across")))

(def hill
  "One-block steps up to y 68 at x 4..7 and back down at x 8..11 (ground 64 elsewhere)."
  (merge (fill stone [4] [64] (range 0 16)) (fill stone [5] [64 65] (range 0 16))
         (fill stone [6] [64 65 66] (range 0 16)) (fill stone [7] [64 65 66 67] (range 0 16))
         (fill stone [8] [64 65 66] (range 0 16)) (fill stone [9] [64 65] (range 0 16))
         (fill stone [10] [64] (range 0 16))))

(deftest a-hill-is-climbed-by-jumps-and-descended-by-drops
  (let [w (world-of hill [1.5 64.0 8.5])
        r (path/plan w [1 64 8] {:goal/kind :block :goal/pos [13 64 8]})
        ys (map second (cons [1 64 8] (:path/waypoints r)))]
    (is (= :found (:path/status r)))
    (is (sound-route? w [1 64 8] r))
    (is (= 68 (apply max ys)) "over the top")
    (is (= 4 (count (filter #(= 1 %) (map - (rest ys) ys)))) "four one-block climbs")
    (is (= 4 (count (filter #(= -1 %) (map - (rest ys) ys)))) "four one-block drops")))

(def low-ceiling
  "A wall across x = 6 whose only gap is one block high."
  (merge (fill stone [6] (range 64 70) (range 0 16)) {[6 64 8] 0}))

(deftest a-one-high-gap-is-no-way-through
  (let [w (world-of low-ceiling [2.5 64.0 8.5])
        r (path/plan w [2 64 8] {:goal/kind :block :goal/pos [12 64 8]})]
    (is (= :none (:path/status r)))
    (is (sound-route? w [2 64 8] r))))

;; ---------------------------------------------------------------- goals, budget, determinism

(deftest goal-kinds
  (let [w (world-of {} [1.5 64.0 1.5])]
    (testing ":near stops within range"
      (let [r (path/plan w [1 64 1] {:goal/kind :near :goal/pos [10 64 10] :goal/range 3})]
        (is (= :found (:path/status r)))
        (is (<= (path/flat-distance (last (:path/waypoints r)) [10 64 10]) 3))))
    (testing ":xz ignores height"
      (is (= :found (:path/status (path/plan w [1 64 1] {:goal/kind :xz :goal/pos [10 99 10]})))))
    (testing ":away gets farther than range"
      (let [r (path/plan w [1 64 1] {:goal/kind :away :goal/pos [1 64 1] :goal/range 4})]
        (is (= :found (:path/status r)))
        (is (> (path/flat-distance (last (:path/waypoints r)) [1 64 1]) 4))))
    (testing "the start can already satisfy the goal"
      (is (= [] (:path/waypoints (path/plan w [1 64 1] {:goal/kind :near :goal/pos [2 64 2]})))))))

(deftest the-budget-is-in-expansions-and-returns-the-nearest-partial
  (let [w (world-of {} [1.5 64.0 1.5])
        r (path/plan w [1 64 1] {:goal/kind :block :goal/pos [14 64 14]} {:path/max-nodes 5})]
    (is (= :partial (:path/status r)))
    (is (seq (:path/waypoints r)) "it still hands back the way to the nearest node it saw")
    (is (sound-route? w [1 64 1] r))))

(deftest a-plan-is-deterministic
  (let [w (world-of (merge cliff lake) [2.5 70.0 2.5])
        g {:goal/kind :block :goal/pos [13 64 2]}]
    (is (= (path/plan w [2 70 2] g) (path/plan w [2 70 2] g)))))

(deftest an-unloaded-goal-comes-back-partial-toward-it
  (let [w (world-of {} [1.5 64.0 1.5])
        r (path/plan w [1 64 1] {:goal/kind :block :goal/pos [40 64 1]})]
    (is (= :none (:path/status r)) "the frontier ends at the chunk's edge")
    (is (= 15 (first (last (:path/waypoints r)))) "at the loaded edge nearest the goal")))
