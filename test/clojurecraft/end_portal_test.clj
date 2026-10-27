(ns clojurecraft.end-portal-test
  "The end portal lit and the End entered (issue #266): the rules over the vanilla portal room,
   the route that walks the frames and climbs the stairs, the void that has no landing, and the
   whole bot against the model's End."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojurecraft.blocks :as blocks]
            [clojurecraft.dimension :as dimension]
            [clojurecraft.end :as end]
            [clojurecraft.end-portal :as end-portal]
            [clojurecraft.fixtures :as fx]
            [clojurecraft.game :as game]
            [clojurecraft.main :as main]
            [clojurecraft.memory :as memory]
            [clojurecraft.path :as path]
            [clojurecraft.plan :as plan]
            [clojurecraft.recipe :as recipe]
            [clojurecraft.sim :as sim]
            [clojurecraft.world :as world]))

(use-fixtures :once fx/instrumented)

;; ---------------------------------------------------------------- the vanilla room

(def bricks (blocks/default-state :stone_bricks))
(def stairs (blocks/default-state :stone_brick_stairs))
(def lava (blocks/default-state :lava))
(def spawner (blocks/default-state :spawner))

(defn box
  "{[x y z] id} for every cell from a to b inclusive."
  [[ax ay az] [bx by bz] id]
  (into {} (for [x (range ax (inc bx)) y (range ay (inc by)) z (range az (inc bz))] [[x y z] id])))

(defn ring
  "The twelve frames of the vanilla room at piece coordinates, each facing inward, eyes in the
   first n (in position order): {[x y z] state}."
  [n]
  (let [frames (sort (concat (for [x [4 5 6]] [x 3 8])
                             (for [x [4 5 6]] [x 3 12])
                             (for [z [9 10 11]] [3 3 z])
                             (for [z [9 10 11]] [7 3 z])))
        facing (fn [[x _ z]]
                 (cond (= z 8) :south
                       (= z 12) :north
                       (= x 3) :east
                       :else :west))]
    (into {} (map-indexed (fn [i p] [p (end/frame-state (< i n) (facing p))]) frames))))

(defn room
  "The vanilla portal room (StrongholdPieces.PortalRoom) with its floor at y 64 and its piece
   origin at the column's corner, eyes in the first n frames: the hollow shell of stone bricks,
   the staircase of three stairs blocks with the spawner in the middle of its top step, the
   platform with the 3x3 lava pit, the two lava troughs by the door, and the frames floating two
   over the floor; {[x y z] state}, x and z 0-15 as a column takes them."
  [n]
  (let [at (fn [[x y z]] [x (+ y 64) z])
        shell (merge (box [0 0 0] [10 7 15] bricks) (box [1 1 1] [9 6 14] blocks/air))]
    (into {}
          (map (fn [[p id]] [(at p) id]))
          (merge shell
                 (box [4 1 5] [6 1 7] bricks)
                 (box [4 2 6] [6 2 7] bricks)
                 (box [4 3 7] [6 3 7] bricks)
                 (box [4 1 4] [6 1 4] stairs)
                 (box [4 2 5] [6 2 5] stairs)
                 (box [4 3 6] [6 3 6] stairs)
                 (box [3 1 8] [7 1 12] bricks)
                 (box [4 1 9] [6 1 11] lava)
                 (box [1 1 1] [1 1 3] lava)
                 (box [9 1 1] [9 1 3] lava)
                 (ring n)
                 {[5 3 6] spawner}))))

(def frames
  "The frames' cells in the room, in position order."
  (vec (sort (map #(update % 1 + 64) (keys (ring 0))))))
(def middle [5 67 10])                ; the ring's middle: the portal's middle cell
(def portal (into {} (for [c (end/interior middle)] [c end/portal])))
(def door [5.5 65.0 2.5])             ; where the bot stands: on the room's floor inside its door
(def eyes {[:hotbar 0] {:item (recipe/item-id :ender_eye) :count 14}})

(defn standing
  "A bot in play at pos in the room (blocks over the column), every frame remembered as it
   stands in the room, holding inv."
  [blocks pos inv]
  (let [w (-> (game/init fx/opts)
              (assoc :bot/phase :play
                     :player/pos pos
                     :player/loaded? true
                     :player/on-ground? true
                     :time/now 1000
                     :player/inventory inv)
              (assoc :world/chunks {[0 0] (world/column blocks)}))]
    (reduce (fn [w p] (memory/observe w p (get blocks p))) w (filter blocks frames))))

;; ---------------------------------------------------------------- the rules

(deftest the-ring-is-whole-among-twelve-frames-and-not-eleven
  (is (= middle (end-portal/middle frames)))
  (is (nil? (end-portal/middle (rest frames))) "eleven close no ring")
  (is (nil? (end-portal/middle []))))

(deftest frames-the-portal-and-stairs-are-floors-to-a-route
  (is (= :solid (path/classify-id (end/frame-state false :north))))
  (is (= :solid (path/classify-id (end/frame-state true :east))) "with an eye too")
  (is (= :solid (path/classify-id end/portal)))
  (is (= :solid (path/classify-id stairs)))
  (is (= :awkward (path/classify-id spawner)) "a spawner stays a shape in the way")
  (let [w (standing (room 0) door eyes)
        to (fn [w node] (:path/status (path/plan w [5 65 2] {:goal/kind :block :goal/pos node})))]
    (testing "from the floor up a side of the stairs onto the frames"
      (is (= :found (to w [4 68 8])) "the near row")
      (is (= :found (to w [6 68 12])) "the far row, along the ring"))
    (testing "the interior is lava two down until the portal is lit"
      (is (= :none (to w [5 68 10])))
      (is (= :found (to (standing (merge (room 12) portal) door eyes) [5 68 10]))))))

(deftest the-void-has-no-landing
  ;; the obsidian platform the End puts a player on stands over nothing down to y 0: every
  ;; step off it is a drop with no floor within max-fall, so a route finds no way off
  (let [[px py pz] (mapv long end/spawn)
        at (fn [[x y z]]
             (cond (and (= y (dec py)) (<= -2 (- x px) 2) (<= -2 (- z pz) 2)) :solid
                   :else :clear))]
    (is (path/standable? at [px py pz]))
    (is (= :none
           (:path/status
            (path/search at [px py pz] {:goal/kind :block :goal/pos [(+ px 4) py pz]} 2000))))
    (is (= :found
           (:path/status
            (path/search at [px py pz] {:goal/kind :block :goal/pos [(+ px 2) py pz]} 2000)))
        "the platform's own edge is reached")))

(deftest the-next-op-is-an-eye-into-the-first-empty-frame-then-the-step-in
  (let [w (standing (room 0) door eyes)
        op (end-portal/next-op w false)]
    (is (= [:use-on :ender_eye (first frames) [:eyed (first frames)]]
           ((juxt :build/op :build/item :build/pos :build/done) op)))
    (is (path/standable? w (:build/stand op)) "worked from a cell the feet can stand in")
    (is (= [:use-on (first frames)] ((juxt :build/op :build/pos) (end-portal/next-op w true))))
    (testing "the first frame eyed: the next"
      (is (= (second frames)
             (:build/pos (end-portal/next-op (standing (room 1) door eyes) false)))))
    (testing "every frame eyed"
      (let [lit (standing (room 12) door eyes)]
        (is (nil? (end-portal/next-op lit false)) "the lighting row has nothing left")
        (is (= [:stand-in :lit [:block middle :end_portal]]
               ((juxt :build/op :build/step :build/done) (end-portal/next-op lit true)))
            "the entering row waits for the portal's blocks")
        (is (= [:stand-in :enter [5 68 10] [:dimension dimension/end]]
               ((juxt :build/op :build/step :build/stand :build/done)
                (end-portal/next-op (standing (merge (room 12) portal) door eyes) true)))
            "then steps in over the portal's middle")))
    (testing "in the End there is nothing left"
      (is (nil? (end-portal/next-op (assoc w :player/dimension dimension/end) true))))))

(deftest the-planner-waits-for-frames-eyes-and-the-lighting
  (let [none (standing {} door eyes)
        few (standing (room 0) door {[:hotbar 0] {:item (recipe/item-id :ender_eye) :count 3}})
        w (standing (room 0) door eyes)
        eyed (standing (room 12) door eyes)
        lit (memory/observe eyed middle end/portal)]
    (is (= {:plan/wait :no-frames} (end-portal/next-toward none false)))
    (is (= {:plan/wait :no-eyes} (end-portal/next-toward few true)) "three eyes for twelve frames")
    (is (= {:intent/kind :end-portal :intent/enter? false} (end-portal/next-toward w false)))
    (is (= {:intent/kind :end-portal :intent/enter? true} (end-portal/next-toward w true)))
    (is (= {:plan/wait :lighting} (end-portal/next-toward eyed false))
        "the blocks not yet on record")
    (is (= {:intent/kind :end-portal :intent/enter? true} (end-portal/next-toward eyed true)))
    (is (nil? (end-portal/next-toward lit false)))
    (testing "outside the room's wall within 6 of a frame: tunnels into the room first"
      (let [outside (standing (room 0) [-0.5 64.0 8.5] eyes)]
        (is (= {:intent/kind :tunnel-to
                :intent/target [3 68 9]
                :intent/steps 32
                :intent/dry? true
                :intent/from-feet [-1 64 8]
                :intent/reach 1.5
                :intent/climb 1}
               (end-portal/next-toward outside false)))))
    (testing "the rows' done predicates"
      (let [lit-row (first (filter #(= :end-portal (:goal/id %)) plan/goals))
            end-row (first (filter #(= :enter-end (:goal/id %)) plan/goals))]
        (is (not (plan/done-by eyed lit-row)))
        (is (plan/done-by lit lit-row))
        (is (= middle (end-portal/nearest-portal lit)) "the RESULT's :portal")
        (is (not (plan/done-by w end-row)))
        (is (plan/done-by (assoc w :player/dimension dimension/end) end-row))
        (is (= [[:end-portal] [:enter-end]]
               [(plan/goals-for "end-portal") (plan/goals-for "enter-end")]))))))

;; ---------------------------------------------------------------- against the model

(defn end-columns
  "The End's floor under the platform: end stone from y 40 to 47 and air over it, the columns
   [6 -1] and [6 0], as column-bytes builds them for the End's sixteen sections."
  []
  (let [column (world/column-bytes
                (into {}
                      (for [x (range 16)
                            z (range 16)
                            y (range 40 64)]
                        [[x y z] (if (< y 48) (blocks/default-state :end_stone) blocks/air)]))
                dimension/end)]
    {[6 -1] column [6 0] column}))

(defn run
  "The whole bot against the model from the room's door with fourteen eyes toward goals, the
   End's columns given; [world sim]."
  [goals]
  (sim/run main/step
           (game/init fx/opts)
           (sim/init {:column (world/column-bytes (room 0))
                      :spawn door
                      :inventory eyes
                      :end {:columns (end-columns)}})
           #(or (plan/done? %) (plan/failed? %))
           600000
           {:event/kind :go :go/goals goals}))

(deftest twelve-eyes-light-the-portal-against-the-model
  (let [[w sim] (run [:end-portal])]
    (is (plan/done? w) (pr-str (plan/summary w)))
    (is (every? #(end/eyed? (sim/block-at sim %)) frames) "every frame took its eye")
    (is (every? #(= end/portal (sim/block-at sim %)) (end/interior middle)) "the portal lit")
    (is (contains? (set (end/interior middle)) (end-portal/nearest-portal w))
        "and remembered: the RESULT names the portal block nearest the feet")
    (is (= 2 (end-portal/eyes-held w)) "twelve spent")
    (is (empty? (:sim/violations sim)))))

(deftest the-end-is-entered-against-the-model
  (let [[w sim] (run [:enter-end])]
    (is (plan/done? w) (pr-str (plan/summary w)))
    (is (= dimension/end (:player/dimension w)))
    (is (= dimension/end (:sim/dimension sim)))
    (let [[x y z] (:player/pos w)
          [sx sy sz] end/spawn]
      (is (and (<= (abs (- x sx)) 2.0) (<= (abs (- z sz)) 2.0) (<= (abs (- y sy)) 0.5))
          "standing on the platform where the End put it"))
    (is (empty? (:sim/violations sim)))))
