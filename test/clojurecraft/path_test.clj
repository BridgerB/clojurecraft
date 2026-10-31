(ns clojurecraft.path-test
  "The pathfinder over fixture worlds built from one chunk column (stone below y 64, air above).
   Every route it returns is checked the same way: no waypoint is water, lava, awkward or
   unknown, every waypoint is standable, and consecutive waypoints differ by exactly one move."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [clojurecraft.blocks :as blocks]
            [clojurecraft.fixtures :as fx]
            [clojurecraft.game :as game]
            [clojurecraft.intent :as intent]
            [clojurecraft.path :as path]
            [clojurecraft.physics :as physics]
            [clojurecraft.spec]
            [clojurecraft.terrain :as terrain]
            [clojurecraft.world :as world]))

(use-fixtures :once fx/instrumented)

(def stone 1)
(def water 86)
(def lava 110)
(def oak-slab 13332)
(def oak-stairs 3908)
(def short-grass 2248)

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
  (let [dx (- bx ax)
        dz (- bz az)
        dy (- by ay)]
    (and (<= (abs dx) 1)
         (<= (abs dz) 1)
         (not= [dx dz] [0 0])
         (or (= dy 1) (= dy 0) (<= (- path/max-fall) dy -1)))))

(defn sound-route?
  "Every waypoint standable and sound, consecutive waypoints one move apart from where the
   plan began (:path/from when it snapped the start, else from)."
  [w from {:path/keys [waypoints] :as route}]
  (and (every? #(path/standable? w %) waypoints)
       (every? #(not (#{:water :lava :awkward :unknown} (path/classify w %))) waypoints)
       (every? (fn [[a b]] (one-move-apart? a b))
               (partition 2 1 (cons (:path/from route from) waypoints)))))

;; ---------------------------------------------------------------- cells

(deftest cells-are-classified-for-a-route
  (is (= :solid (path/classify-id stone)))
  (is (= :water (path/classify-id water)))
  (is (= :lava (path/classify-id lava)))
  (is (= :awkward (path/classify-id oak-slab)))
  (is (= :solid (path/classify-id oak-stairs))
      "stairs are a floor, climbed a block at a time as the physics takes them (issue #266)")
  (is
   (= :awkward (path/classify-id (blocks/default-state :cocoa)))
   "a cocoa pod collides though nothing listed it: gym batch iron-a9 run 5 walked into one on a
       jungle log and never moved again")
  (is (= :clear (path/classify-id (blocks/default-state :moss_carpet)))
      "a carpet is walked over like the floor")
  (is (= :clear (path/classify-id short-grass)) "a plant is walked through")
  (is (= :clear (path/classify-id path/one-layer-snow))
      "one layer of snow has no collision shape: walked through, the feet in its cell")
  (is (= :awkward (path/classify-id (inc path/one-layer-snow)))
      "two layers collide a quarter block high, which the physics cannot simulate")
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
    (is (= #{[4 64 5] [6 64 5] [5 64 4] [5 64 6] [4 64 4] [4 64 6] [6 64 4] [6 64 6]}
           (set (map :node ns))))
    (is (= #{:walk :diagonal} (set (map :move ns))))))

(deftest a-wall-ahead-is-a-jump-up-and-a-two-high-wall-is-not
  (let [w (world-of {[6 64 5] stone} [0.5 64.0 0.5])
        ns (path/neighbors w [5 64 5])]
    (is (some #(= [[6 65 5] :jump-up] [(:node %) (:move %)]) ns) "one block up over the step")
    (is (not-any? #(= [6 64 5] (:node %)) ns) "not into the step"))
  (let [w (world-of {[6 64 5] stone [6 65 5] stone} [0.5 64.0 0.5])]
    (is (not-any? #(= 6 (first (:node %))) (path/neighbors w [5 64 5]))
        "a two-high wall is a wall")))

(deftest an-edge-is-a-drop-to-the-floor-below-within-max-fall
  (let [ground (fill stone (range 0 16) [64 65] (range 0 16))
        pit (fill 0 [6 7] [64 65] (range 0 16))
        w (world-of (merge ground pit) [0.5 66.0 0.5])
        ns (path/neighbors w [5 66 5])]
    (is (some #(= [[6 64 5] :drop] [(:node %) (:move %)]) ns) "two blocks down onto stone")
    (is (= (+ path/walk-cost (* 2 path/fall-cost-per-block))
           (:cost (first (filter #(= :drop (:move %)) ns))))))
  (testing "too far is refused"
    (let [ground (fill stone (range 0 16) (range 64 69) (range 0 16))
          pit (fill 0 [6 7] (range 64 69) (range 0 16))
          w (world-of (merge ground pit) [0.5 69.0 0.5])]
      (is (not-any? #(= :drop (:move %)) (path/neighbors w [5 69 5]))
          "a 5-block fall is never offered"))))

(deftest lava-beside-or-under-a-cell-forbids-every-move-into-it
  (let [w (world-of {[7 64 5] lava} [0.5 64.0 0.5])
        ns (path/neighbors w [5 64 5])]
    (is (not-any? #(= [6 64 5] (:node %)) ns) "the cell beside the lava")
    (is (not-any? #(= [6 64 4] (:node %)) ns) "nor the diagonal next to it")
    (is (some #(= [4 64 5] (:node %)) ns) "away from it is fine"))
  (let [w (world-of {[6 63 5] lava [6 64 5] 0} [0.5 64.0 0.5])]
    (is (not-any? #(= 6 (first (:node %))) (path/neighbors w [5 64 5]))
        "a drop onto lava is refused")))

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
  (merge (fill stone [4] [64] (range 0 16))
         (fill stone [5] [64 65] (range 0 16))
         (fill stone [6] [64 65 66] (range 0 16))
         (fill stone [7] [64 65 66 67] (range 0 16))
         (fill stone [8] [64 65 66] (range 0 16))
         (fill stone [9] [64 65] (range 0 16))
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

;; ---------------------------------------------------------------- traversability, generated

(def terrain-gen
  "A 16x16 column: a random heightmap whose steps between neighbouring columns are -3..+1 (the
   moves a route may take), with a few water pools one block deep, and two cells to route
   between."
  (gen/let [rows (gen/vector (gen/vector (gen/choose -3 1) 15) 16)
            base (gen/choose 64 66)
            pools (gen/vector (gen/tuple (gen/choose 0 15) (gen/choose 0 15)) 0 4)
            from (gen/tuple (gen/choose 0 15) (gen/choose 0 15))
            to (gen/tuple (gen/choose 0 15) (gen/choose 0 15))]
    (let [heights (vec (for [row rows]
                         (vec (reductions (fn [h d] (max 64 (min 72 (+ h d)))) base row))))
          surface (fn [x z] (get-in heights [z x]))
          blocks (into {} (for [x (range 16) z (range 16) y (range 64 (surface x z))] [[x y z] 1]))
          blocks (reduce (fn [b [px pz]] (assoc b [px (dec (surface px pz)) pz] 86)) blocks pools)]
      {:blocks blocks :surface surface :from from :to to})))

(defn drive
  "Walk the route with the walk intent's own controls over physics alone (no server): from the
   feet at start, for each waypoint up to 80 ticks until it is reached. Returns the waypoints
   reached, in order, and the final feet position."
  [w start waypoints]
  (let [solid? (terrain/solid-fn w)]
    (reduce
     (fn [[reached pos vel og] wp]
       (let [[hit? pos vel og]
             (reduce
              (fn [[_ pos vel og] _]
                (let [w (assoc w :player/pos pos :player/vel vel :player/on-ground? og)
                      w (physics/step solid? w (intent/follow w wp))
                      w (assoc w :player/horizontal-collision? (:player/horizontal-collision? w))]
                  (if (intent/waypoint-reached? w wp)
                    (reduced [true (:player/pos w) (:player/vel w) (:player/on-ground? w)])
                    [false (:player/pos w) (:player/vel w) (:player/on-ground? w)])))
              [false pos vel og]
              (range 80))]
         (if hit? [(conj reached wp) pos vel og] (reduced [reached pos vel og]))))
     [[] [(+ (first start) 0.5) (double (second start)) (+ (nth start 2) 0.5)] [0.0 0.0 0.0] true]
     waypoints)))

(defn settle
  "The feet position after up to 20 ticks of physics with no input from pos: where a bot that
   just reached a waypoint mid-step comes to rest (a drop lands only after the fall)."
  [w pos vel]
  (let [solid? (terrain/solid-fn w)]
    (:player/pos
     (reduce (fn [w _] (physics/step solid? w {}))
             (assoc w :player/pos pos :player/vel [0.0 (second vel) 0.0] :player/on-ground? false) ; the walk stops pressing forward at arrival
             (range 20)))))

(deftest a-descending-walk-does-not-run-past-its-landing
  (let [w (world-of {[6 63 5] 0 [6 62 5] stone [7 63 5] 0 [7 62 5] 0 [7 61 5] 0} [5.5 64.0 5.5])
        target [6 63 5]
        solid? (terrain/solid-fn w)]
    (doseq [start [[5.5 64.0 5.5] [6.25 64.0 5.5]]]
      (let [landed (reduce (fn [w _]
                             (let [controls (if (and (:player/on-ground? w)
                                                     (intent/centred? w target))
                                              {}
                                              (intent/follow w target))]
                               (physics/step solid? w controls)))
                           (assoc w :player/pos start :player/vel [0.0 0.0 0.0])
                           (range 40))]
        (is (:player/on-ground? landed))
        (is (intent/centred? landed target))))
    (is (= {} (intent/follow (assoc w :player/pos [6.5 63.8 5.5]) target))))
  (testing "level walks and climbs still press forward"
    (is (:control/forward? (intent/follow {:player/pos [5.5 64.0 5.5]} [6 64 5])))
    (is (:control/jump? (intent/follow {:player/pos [5.5 64.0 5.5]} [6 65 5])))))

(deftest a-found-route-is-walked-by-the-physics-and-ends-in-the-goal
  ;; issue #4's property: over generated terrain, when plan says :found, driving physics/step
  ;; with the walk's follow controller reaches every waypoint in ≤ 80 ticks each and ends in
  ;; the goal; and plan is deterministic. The drive starts where the plan did (:path/from: the
  ;; start, or the standable cell beside it). Worlds with no :found route only check determinism.
  (let [r (tc/quick-check
           200
           (prop/for-all
            [{:keys [blocks surface from to]} terrain-gen]
            (let [[fx fz] from
                  [tx tz] to
                  start [fx (surface fx fz) fz]
                  goal {:goal/kind :near :goal/pos [tx (surface tx tz) tz] :goal/range 1}
                  w (world-of blocks [(+ fx 0.5) (double (second start)) (+ fz 0.5)])
                  route (path/plan w start goal)]
              (and (= route (path/plan w start goal))
                   (sound-route? w start route)
                   (or (not= :found (:path/status route))
                       (let [[reached pos vel] (drive w (:path/from route) (:path/waypoints route))]
                         (and (= (count reached) (count (:path/waypoints route)))
                              (path/goal-done? w goal (path/feet-cell (settle w pos vel))))))))))]
    (is (:pass? r) (pr-str (select-keys r [:fail :shrunk :num-tests :seed])))))

(defn shuffle-by
  "coll in an order fixed by seed: a generated order that shrinks and replays."
  [seed coll]
  (let [l (java.util.ArrayList. ^java.util.Collection coll)]
    (java.util.Collections/shuffle l (java.util.Random. seed))
    (vec l)))

(deftest the-cells-of-a-world-answer-as-classify-does
  ;; a search reads each cell once through path/cells; whatever it holds must be what classify
  ;; says of the world, asked in any order, any number of times, loaded or not (x and z -1 and
  ;; 16 are outside the one column), and every predicate answers the same from either
  (let [r (tc/quick-check
           50
           (prop/for-all
            [{:keys [blocks]} terrain-gen lava-at
             (gen/tuple (gen/choose 0 15) (gen/choose 64 70) (gen/choose 0 15)) order gen/nat]
            (let [w (world-of (assoc blocks lava-at lava) [0.5 80.0 0.5])
                  at (path/cells w)
                  cells (vec (for [x (range -1 17) y (range 62 74) z (range -1 17)] [x y z]))
                  asked (concat (shuffle-by order cells) cells)]
              (and (every? #(= (path/classify w %) (at %)) asked)
                   (identical? at (path/cells at))
                   (every? #(= (path/standable? w %) (path/standable? at %)) cells)
                   (every? #(= (path/landing w %) (path/landing at %)) cells)))))]
    (is (:pass? r) (pr-str (select-keys r [:fail :shrunk :num-tests :seed])))))

(deftest no-two-cells-share-a-key
  ;; cells inside the world border, at any height from -64 to 2047, each to its own key
  (let [cell (gen/tuple (gen/choose -30000000 30000000)
                        (gen/choose -64 2047)
                        (gen/choose -30000000 30000000))
        r (tc/quick-check 2000
                          (prop/for-all [a cell b cell]
                                        (= (= a b)
                                           (= (apply path/cell-key a) (apply path/cell-key b)))))]
    (is (:pass? r) (pr-str (select-keys r [:fail :shrunk :num-tests :seed]))))
  (is (= 5
         (count (set (map #(apply path/cell-key %)
                          [[0 64 0] [0 64 1] [1 64 0] [0 65 0] [-1 -64 -1]]))))))

(deftest a-start-on-a-block-edge-searches-from-the-cell-the-box-rests-on
  ;; gym landing 21280,18720: the feet at x 21281.0 on the edge of a stone step, flooring into
  ;; the step itself; the standable cell is the one beside it. Before this the walk failed
  ;; :no-path three times in half a second and the plan died
  (let [hill (fill stone (range 8 16) [64 65] (range 0 16)) ; a two-high step from x 8
        w (world-of hill [8.0 64.0 5.5]) ; feet on the step's edge
        from (path/feet-cell [8.0 64.0 5.5])]
    (is (= [8 64 5] from) "the coordinates floor into the step")
    (is (not (path/standable? w from)))
    (is (= [7 64 5] (path/start-cell w from)) "the box rests on the ground beside it")
    (let [r (path/plan w from {:goal/kind :block :goal/pos [2 64 5]})]
      (is (= :found (:path/status r)))
      (is (sound-route? w [7 64 5] r))))
  (let [w (world-of (fill stone (range 0 16) [64 65 66] (range 0 16)) [5.5 64.0 5.5])] ; buried: nothing near stands
    (is (nil? (path/start-cell w [5 64 5])))
    (is (= {:path/waypoints [] :path/cost 0.0 :path/status :none}
           (path/plan w [5 64 5] {:goal/kind :block :goal/pos [2 70 5]})))))

(deftest a-box-over-a-ledge-starts-on-the-block-it-rests-on-not-one-across-a-gap
  ;; sim snapshot R1-26-1 (issue #284): the centre at x 401.73 on a one-block ledge, the box over
  ;; the east block's edge, the feet cell's floor air. The first standable neighbour in start-cell's
  ;; order was the block across the gap to the west, and no route led from it to the east: every
  ;; walk failed :no-path three times in a row and the tunnel with it
  (let [gap (fill 0 [2] [63] (range 16)) ; a one-block gap in the ground at x 2
        pos [2.9 64.0 5.5] ; the box spans x 2.6 to 3.2: over the gap and the block east of it
        w (world-of gap pos)
        from (path/feet-cell pos)
        goal {:goal/kind :block :goal/pos [8 64 5]}]
    (is (= [2 64 5] from))
    (is (not (path/standable? w from)) "the feet cell's floor is the gap")
    (is (= [[2 64 5] [3 64 5]] (path/rests w from))
        "the cells the box overlaps, the one under most of it first")
    (is (= [1 64 5] (path/start-cell w from))
        "without the box, the first neighbour is across the gap")
    (is (= [3 64 5] (path/start-cell w from (path/rests w from))))
    (let [r (path/plan w from goal)]
      (is (= [3 64 5] (:path/from r)) "the search begins on the block under the box")
      (is (= :found (:path/status r)))
      (is (sound-route? w from r)))
    (is (nil? (path/rests w [3 64 5])) "only the feet cell of the position has a box")))

(deftest a-route-leaves-occupied-awkward-footing-without-entering-it
  (let [from [5 64 5]
        to [4 65 5]
        w (world-of (merge (fill stone (range 16) [64 65 66] (range 16))
                           {[5 63 5] (blocks/default-state :soul_sand)
                            [5 64 5] 0
                            [5 65 5] 0
                            [5 66 5] 0
                            [5 64 4] 0
                            [5 65 4] 0
                            [4 65 5] 0
                            [4 66 5] 0})
                    [5.5 64.0 5.5])
        goal {:goal/kind :block :goal/pos to}
        route (path/plan w from goal)]
    (is (not (path/standable? w from)))
    (is (= [5 64 4] (path/start-cell w from)) "the neighbouring start is isolated")
    (is (= from (:path/from route)) "depart from the actual feet instead")
    (is (= :found (:path/status route)))
    (is (= [to] (:path/waypoints route)))
    (is (sound-route? w from route))
    (is (= [to] (first (drive w from (:path/waypoints route)))))
    (is (not= :found (:path/status (path/plan w to {:goal/kind :block :goal/pos from}))))
    (testing "unsupported floors, occupied body cells and lava still forbid the actual start"
      (doseq [[pos state] [[[5 63 5] water] [[5 63 5] lava] [[5 63 5] 0] [[5 64 5] stone]
                           [[5 65 5] stone] [[5 64 5] water] [[5 65 5] water] [[6 64 5] lava]
                           [[6 65 5] lava]]]
        (is (not= from (:path/from (path/plan (assoc-in w [:world/blocks pos] state) from goal)))))
      (is (not= from (:path/from (path/plan (assoc w :world/chunks {}) from goal)))))))

(deftest a-snowfield-is-crossed-through-its-one-layer-snow
  ;; race 38477665442 (issue #38): 39 bots landed on snowy ground, one layer of snow on every
  ;; block round them. The feet stood in the snow's cell, which was :awkward, so neither it
  ;; nor any cell beside it was standable, every plan was :none and twelve walks failed
  ;; :no-path in a second, at the landing
  (let [snow (fill path/one-layer-snow (range 0 16) [64] (range 0 16)) ; a layer over the stone
        w (world-of snow [2.5 64.0 2.5]) ; the feet in the snow, on the stone under it
        from (path/feet-cell [2.5 64.0 2.5])
        r (path/plan w from {:goal/kind :near :goal/pos [13 64 13] :goal/range 1})]
    (is (path/standable? w from) "a floor under the snow, the body in it and the head clear")
    (is (= :found (:path/status r)))
    (is (sound-route? w from r)))
  (let [deep (fill (inc path/one-layer-snow) (range 0 16) [64] (range 0 16)) ; two layers everywhere
        w (world-of deep [2.5 64.0 2.5])]
    (is (nil? (path/start-cell w [2 64 2])) "deeper snow collides: nothing near can be stood on"))
  (let [bank (merge (fill path/one-layer-snow (range 0 16) [64] (range 0 16))
                    (fill stone (range 0 16) [64] (range 0 2))) ; a step up at z 0-1, the snow on it
        w (world-of bank [5.5 65.0 2.0]) ; feet on the step's edge, flooring over the snow beside it
        from (path/feet-cell [5.5 65.0 2.0])]
    (is (= [5 65 2] from))
    (is (not (path/standable? w from)) "nothing solid under: the snow's cell is below")
    (is (= [5 65 1] (path/start-cell w from)) "the search starts on the step the box rests on")
    (let [r (path/plan w from {:goal/kind :near :goal/pos [12 64 12] :goal/range 1})]
      (is (= :found (:path/status r)) "and drops into the snowfield")
      (is (sound-route? w [5 65 1] r)))))

(deftest a-bot-on-the-rim-of-a-hole-in-the-ice-is-on-the-ice
  ;; issue #105 (sim race 39550841573, R2/32 seed 3): the ice under the feet dug out for the
  ;; water under it, the body on the sheet's edge, in a cell with no ice under it
  (let [ice (blocks/default-state :ice)
        sheet (fill ice (range 0 10) [63] (range 16))
        holed (assoc sheet [2 63 8] blocks/air)]
    (is (path/on-ice? (world-of holed [2.5 64.0 8.5])) "over the hole, the rim beside it")
    (is (not (path/on-ice? (world-of holed [10.5 64.0 8.5]))) "on land beside the sheet")))

(deftest a-bot-on-a-sheet-of-ice-walks-off-it-but-never-onto-it
  ;; race 39088700340 bot Clj_r02 (issue #71) climbed out of a frozen sea onto the ice, and no
  ;; route led off the sheet: ice is no floor to a route
  (let [ice (blocks/default-state :ice)
        sheet (fill ice (range 0 10) [63] (range 16))
        goal-land {:goal/kind :near :goal/pos [14 64 8] :goal/range 1}
        goal-ice {:goal/kind :near :goal/pos [3 64 8] :goal/range 1}
        on-ice (world-of sheet [2.5 64.0 8.5])
        on-land (world-of sheet [12.5 64.0 8.5])]
    (is (path/on-ice? on-ice))
    (is (not (path/on-ice? on-land)))
    (is (= :found (:path/status (path/plan on-ice (path/feet-cell [2.5 64.0 8.5]) goal-land)))
        "from the sheet, over it to the land")
    (is (not= :found (:path/status (path/plan on-land (path/feet-cell [12.5 64.0 8.5]) goal-ice)))
        "from the land, never onto the sheet")))

;; ---------------------------------------------------------------- wary ground

(def crimson-nylium (blocks/default-state :crimson_nylium))

(defn on-patch?
  "Does the route stand on the floor cell [x 63 z] of patch (a set of [x z])?"
  [patch route]
  (some (fn [[x _ z]] (contains? patch [x z])) (:path/waypoints route)))

(deftest a-short-detour-round-crimson-ground-beats-a-way-through-it
  ;; gym plan run 41134469333 (pearls-travel-b) jobs 1 and 2 died crossing crimson forest
  (let [patch (set (for [x (range 3 8) z (range 4 7)] [x z]))
        floor (into {} (for [[x z] patch] [[x 63 z] crimson-nylium]))
        w (world-of floor [0.5 64.0 5.5])
        goal {:goal/kind :block :goal/pos [11 64 5]}
        r (path/plan w (path/feet-cell [0.5 64.0 5.5]) goal {})]
    (is (= :found (:path/status r)))
    (is (not (on-patch? patch r)) "round it")
    (is (< (:path/cost r) (+ 11 (count patch))) "for less than through it")))

(deftest crimson-ground-with-no-way-round-is-still-crossed
  (let [patch (set (for [x (range 3 8) z (range 0 16)] [x z]))
        floor (into {} (for [[x z] patch] [[x 63 z] crimson-nylium]))
        w (world-of floor [0.5 64.0 5.5])
        r (path/plan w (path/feet-cell [0.5 64.0 5.5]) {:goal/kind :block :goal/pos [11 64 5]} {})]
    (is (= :found (:path/status r)) "a modest cost, not a wall")
    (is (on-patch? patch r))))

(deftest fire-is-no-way
  ;; blaze-race-g and -i: in_fire hit up to 26 times a run; a route went through fire blocks
  (let [fire (blocks/default-state :fire)]
    (is (= :awkward (path/classify-id fire)))
    (is (= :awkward (path/classify-id (blocks/default-state :soul_fire))))
    (let [w (world-of {[9 64 8] fire [9 65 8] fire} [8.5 64.0 8.5])
          r (path/plan w [8 64 8] {:goal/kind :block :goal/pos [10 64 8]} {:path/max-nodes 400})]
      (is (= :found (:path/status r)))
      (is (not-any? #{[9 64 8]} (:path/waypoints r)) "round the fire, not through it"))))
