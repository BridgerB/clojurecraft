(ns clojurecraft.stronghold-test
  "Finding the stronghold (issue #265): two throws' lines crossed on the plane, nothing when they
   are parallel or cross behind a throw; a throw followed tick by tick over canned packets."
  (:require [clojure.math :as math]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [clojurecraft.blocks :as blocks]
            [clojurecraft.fixtures :as fx]
            [clojurecraft.fortress :as fortress]
            [clojurecraft.game :as game]
            [clojurecraft.intent :as intent]
            [clojurecraft.main :as main]
            [clojurecraft.memory :as memory]
            [clojurecraft.plan :as plan]
            [clojurecraft.recipe :as recipe]
            [clojurecraft.sim :as sim]
            [clojurecraft.stronghold :as stronghold]
            [clojurecraft.world :as world]))

(use-fixtures :once fx/instrumented)

(defn toward
  "The throw an eye makes from [x y z] at the stronghold [sx sz]: the unit way to it on the plane,
   as a vanilla eye flies."
  [[x y z] [sx sz]]
  (let [dx (- sx x)
        dz (- sz z)
        d (math/hypot dx dz)]
    {:throw/from [(double x) (double y) (double z)] :throw/dir [(/ dx d) (/ dz d)] :throw/at 0}))

(defn near?
  "Are [x z] points a and b within e of each other?"
  [a b e]
  (and a b (<= (math/hypot (- (first a) (first b)) (- (second a) (second b))) e)))

(deftest two-throws-from-apart-cross-at-the-stronghold
  (let [s [1200 -2600]
        a (toward [0 70 0] s)
        b (toward [212 64 212] s)]
    (is (near? s (stronghold/triangulate a b) 1e-6))
    (is (near? s (stronghold/triangulate b a) 1e-6) "either throw first")))

(deftest parallel-throws-say-nothing
  (testing "two eyes thrown along one line: how far is not in them"
    (let [a {:throw/from [0.0 64.0 0.0] :throw/dir [0.6 0.8] :throw/at 0}
          b {:throw/from [60.0 64.0 80.0] :throw/dir [0.6 0.8] :throw/at 1}]
      (is (nil? (stronghold/triangulate a b)))))
  (testing "one degree apart is under min-crossing"
    (let [t (math/to-radians 1.0)
          a {:throw/from [0.0 64.0 0.0] :throw/dir [1.0 0.0] :throw/at 0}
          b {:throw/from [0.0 64.0 50.0] :throw/dir [(math/cos t) (- (math/sin t))] :throw/at 1}]
      (is (nil? (stronghold/triangulate a b))))))

(deftest a-crossing-behind-a-throw-is-none
  (testing "the lines cross behind the second throw"
    (let [a {:throw/from [0.0 64.0 0.0] :throw/dir [1.0 0.0] :throw/at 0}
          b {:throw/from [100.0 64.0 50.0] :throw/dir [0.0 1.0] :throw/at 1}]
      (is (nil? (stronghold/triangulate a b)))
      (is (= [100.0 0.0] (stronghold/triangulate a (assoc b :throw/dir [0.0 -1.0])))
          "the same lines, the second eye flying toward the crossing")))
  (testing "behind both"
    (let [a {:throw/from [0.0 64.0 0.0] :throw/dir [1.0 0.0] :throw/at 0}
          b {:throw/from [-50.0 64.0 50.0] :throw/dir [0.0 1.0] :throw/at 1}]
      (is (nil? (stronghold/triangulate a b))))))

(def gen-coord (gen/choose -3000 3000))

(deftest exact-throws-from-any-two-places-cross-at-the-stronghold
  ;; every pair of exact throws whose lines are far enough from parallel names the stronghold;
  ;; a crossing is never behind a throw
  (let [r (tc/quick-check 300
                          (prop/for-all
                           [sx gen-coord sz gen-coord ax gen-coord az gen-coord bx gen-coord bz
                            gen-coord]
                           (let [s [sx sz]
                                 a (toward [ax 64 az] s)
                                 b (toward [bx 64 bz] s)
                                 sine (abs (stronghold/cross (:throw/dir a) (:throw/dir b)))
                                 c (stronghold/triangulate a b)]
                             (cond (or (= [ax az] s) (= [bx bz] s)) true ; thrown on the spot: no way to fly
                                   (> sine (* 2 stronghold/min-crossing)) (near? s c 1e-3)
                                   (nil? c) true
                                   :else (near? s c 1.0)))))]
    (is (:pass? r) (pr-str (select-keys r [:fail :shrunk])))))

;; ---------------------------------------------------------------- the throw

(defn standing
  "A bot in play at [8.5 64 8.5] on one column's stone floor, two eyes of ender in hotbar slot 0."
  []
  (-> (game/init fx/opts)
      (assoc :bot/phase :play
             :player/pos [8.5 64.0 8.5]
             :player/loaded? true
             :player/on-ground? true
             :world/chunks {[0 0] (world/column {})}
             :window/state-id 5
             :player/inventory {0 {:item (recipe/item-id :ender_eye) :count 2}})))

(defn intent-step
  "The world reducer plus one run of the current intent per tick while it is active."
  [w e]
  (let [w (game/step w e)]
    (if (and (= :tick (:event/kind e)) (= :active (:intent/status (:plan/intent w))))
      (intent/run w (:plan/intent w) e)
      w)))

(defn eye-added
  "The server adding eye of ender eid at [x y z]."
  [eid [x y z]]
  (fx/packet {:packet/name :add-entity
              :entity-id eid
              :uuid nil
              :type stronghold/eye-type
              :x x
              :y y
              :z z
              :vel [0.0 0.0 0.0]
              :pitch 0
              :yaw 0
              :head-yaw 0
              :data 0}))

(defn eye-moved
  "The server moving entity eid by [dx dy dz] blocks, in its 1/4096 steps."
  [eid [dx dy dz]]
  (fx/packet {:packet/name :move-entity-pos
              :entity-id eid
              :dx (long (* 4096 dx))
              :dy (long (* 4096 dy))
              :dz (long (* 4096 dz))
              :on-ground false}))

(def throwing (assoc (standing) :plan/intent {:intent/kind :throw-eye :intent/status :active}))

(deftest an-eye-is-thrown-followed-and-its-line-kept
  ;; issue #265: the eye in hand, the eyes up, one use-item; the eye the server adds at the bot's
  ;; middle is followed two seconds, the line it flew kept as a throw, and the intent is done
  ;; when the server removes it, knowing where it ended
  (let [flight (mapcat (fn [k]
                         (concat (fx/ticks (+ 450 (* k 250)) (+ 700 (* k 250)))
                                 [(eye-moved 90 [0.6 0.1 0.8])]))
                (range 10))
        [w fx] (fx/fold intent-step
                        throwing
                        (concat (fx/ticks 0 450)
                                [(eye-added 90 [8.5 64.9 8.5])]
                                flight
                                [(fx/packet {:packet/name :remove-entities :ids [90]})]
                                (fx/ticks 2950 4000)))
        use (first (filter #(= :use-item (:packet/name %)) (fx/packets fx)))
        [t :as ts] (:stronghold/throws w)
        i (:plan/intent w)]
    (is (= -20.0 (:x-rot use)) "thrown looking a little up, never down at a frame")
    (is (= 1 (count ts)))
    (is (= [8.5 64.9 8.5] (:throw/from t)) "from where the eye was first seen")
    (is (near? [0.6 0.8] (:throw/dir t) 1e-3) "the way it flew on the plane")
    (is (= :done (:intent/status i)))
    (is (near? [13.9 15.7] (stronghold/plane (:intent/eye-pos i)) 1e-2)
        "where it was last seen on a tick before the server removed it")))

(deftest no-eye-seen-fails-the-throw
  ;; a use-item the server does nothing with (no stronghold: the Nether) is :no-eye
  (let [[w _] (fx/fold intent-step throwing (fx/ticks 0 4000))]
    (is (= :failed (:intent/status (:plan/intent w))))
    (is (= :no-eye (:intent/reason (:plan/intent w))))
    (is (empty? (:stronghold/throws w)))))

(def throw-row (first (filter #(= :eye-throw (:goal/id %)) plan/goals)))

(deftest the-throw-trial-throws-once-with-an-eye-held
  (is (= {:intent/kind :throw-eye :intent/for :stronghold}
         (plan/next-intent (standing) throw-row plan/goals)))
  (is (= {:plan/wait :no-eye}
         (plan/next-intent (assoc (standing) :player/inventory {}) throw-row plan/goals)))
  (is (not (plan/done-by (standing) throw-row)))
  (is (plan/done-by (assoc (standing)
                           :stronghold/throws
                           [{:throw/from [0.0 64.0 0.0] :throw/dir [1.0 0.0] :throw/at 0}])
                    throw-row)))

(deftest a-throw-waits-for-the-eyes-drop-after-the-removal
  ;; the removal and the dropped item's add need not come in one tick: the throw is done when the
  ;; item lies where the eye ended, and the search's next step picks it up
  (let [flight (mapcat (fn [k]
                         (concat (fx/ticks (+ 450 (* k 250)) (+ 700 (* k 250)))
                                 [(eye-moved 90 [0.6 0.1 0.8])]))
                (range 10))
        removed (concat (fx/ticks 0 450)
                        [(eye-added 90 [8.5 64.9 8.5])]
                        flight
                        [(fx/packet {:packet/name :remove-entities :ids [90]})]
                        (fx/ticks 2950 3100))
        [w _] (fx/fold intent-step throwing removed)
        [mid-air _] (fx/fold intent-step
                             w
                             (concat [(fx/packet {:packet/name :add-entity
                                                  :entity-id 91
                                                  :uuid nil
                                                  :type blocks/item-entity-type
                                                  :x 14.5
                                                  :y 70.0
                                                  :z 14.5})]
                                     (fx/ticks 3100 3200)))
        [w2 _] (fx/fold intent-step
                        w
                        (concat [(fx/packet {:packet/name :add-entity
                                             :entity-id 91
                                             :uuid nil
                                             :type blocks/item-entity-type
                                             :x 14.5
                                             :y 64.0
                                             :z 14.5})]
                                (fx/ticks 3100 3200)))]
    (is (= :active (:intent/status (:plan/intent w))) "the eye gone, its drop not yet come")
    (is (= :active (:intent/status (:plan/intent mid-air)))
        "falling through air: not yet on the floor")
    (is (= :done (:intent/status (:plan/intent w2))) "done once the drop lies there")
    (is (= {:intent/kind :collect-drops
            :intent/item :ender_eye
            :intent/target [14 64 14]
            :intent/for :stronghold}
           (stronghold/stronghold-next (assoc w2 :plan/last (:plan/intent w2))))
        "the search picks it up first")))

;; ---------------------------------------------------------------- the search

(def flat
  "Chunk columns of the stone floor from chunk -4 to 4 each way: blocks -64 to 79."
  (into {} (for [cx (range -4 5) cz (range -4 5)] [[cx cz] (world/column {})])))

(defn throw-fact
  "A throw from [x y z] along [dx dz] made at ms at."
  [from dir at]
  {:throw/from (mapv double from) :throw/dir (mapv double dir) :throw/at at})

(defn searching
  "standing on the flat floor at [x 64 z] with the throws ts."
  [[x z] ts]
  (cond-> (assoc (standing) :world/chunks flat :player/pos [x 64.0 z])
    (seq ts) (assoc :stronghold/throws (vec ts))))

(def row (first (filter #(= :stronghold (:goal/id %)) plan/goals)))

(defn plane-distance [[ax _ az] [bx bz]] (math/hypot (- ax bx) (- az bz)))

(deftest the-search-throws-walks-off-the-line-and-throws-again
  (testing "no throw yet: one"
    (is (= {:intent/kind :throw-eye :intent/for :stronghold}
           (stronghold/stronghold-next (searching [8.5 8.5] [])))))
  (testing "one throw: a hop toward the second throw's spot, 64 blocks off at 60 degrees"
    (let [t (throw-fact [8.5 64.9 8.5] [0 1] 0)
          spot (stronghold/second-spot t)
          i (stronghold/stronghold-next (searching [8.5 8.5] [t]))]
      (is (near? [-46.93 40.5] spot 0.01))
      (is (= [:walk :stronghold] [(:intent/kind i) (:intent/for i)]))
      (is (< 28 (plane-distance (:intent/target i) [8.5 8.5]) 36) "a hop of 32")
      (is (< (plane-distance (:intent/target i) spot) (- (plane-distance [8.5 0 8.5] spot) 28))
          "toward the spot")))
  (testing "at the spot: the second throw"
    (let [t (throw-fact [8.5 64.9 8.5] [0 1] 0)
          [sx sz] (stronghold/second-spot t)]
      (is (= {:intent/kind :throw-eye :intent/for :stronghold}
             (stronghold/stronghold-next
              (assoc (searching [8.5 8.5] [t]) :player/pos [sx 64.0 sz]))))))
  (testing "no eye left: the wait names it"
    (is (= {:plan/wait :no-eye}
           (stronghold/stronghold-next (assoc (searching [8.5 8.5] []) :player/inventory {}))))))

(deftest the-next-throws-spot-is-on-the-side-the-feet-are-on
  ;; gym batch stronghold-265b (plan run 38930188463) run 2 walked to and fro between the two
  ;; spots twelve times, a lake between
  (let [t (throw-fact [8.5 64.9 8.5] [0 1] 0)
        [a b] [(stronghold/second-spot t 60.0) (stronghold/second-spot t -60.0)]]
    (is (= [a b] (stronghold/spots (searching [8.5 20.5] [t]) t))
        "on the line: the first way first")
    (is (= [b a] (stronghold/spots (searching [30.5 8.5] [t]) t)) "off it: its own side first")
    (is (= [a b] (stronghold/spots (searching [-12.5 8.5] [t]) t)))))

(deftest two-crossing-throws-walk-to-the-crossing-and-look-round-it
  (testing "far from the crossing: a hop toward it"
    (let [a (throw-fact [8.5 64.9 8.5] [0 1] 0)
          b (throw-fact [108.5 64.9 8.5]
                        [(/ -100 (math/hypot 100 300)) (/ 300 (math/hypot 100 300))]
                        1)
          w (searching [8.5 8.5] [a b])
          i (stronghold/stronghold-next w)]
      (is (near? [8.5 308.5] (stronghold/crossing w) 1e-6))
      (is (= :walk (:intent/kind i)))
      (is (< 36 (nth (:intent/target i) 2) 42) "32 blocks on toward it")))
  (testing "at the crossing, the last throw far off: a throw from here"
    (let [a (throw-fact [8.5 64.9 -291.5] [0 1] 0)
          b (throw-fact [308.5 64.9 8.5] [-1 0] 1)]
      (is (= {:intent/kind :throw-eye :intent/for :stronghold}
             (stronghold/stronghold-next (searching [8.5 8.5] [a b]))))))
  (testing "at the crossing, thrown from near it already: walks round it"
    (let [a (throw-fact [8.5 64.9 -291.5] [0 1] 0)
          b (throw-fact [40.5 64.9 8.5] [-1 0] 1)
          i (stronghold/stronghold-next (searching [8.5 8.5] [a b]))]
      (is (= [:walk :stronghold] [(:intent/kind i) (:intent/for i)])))))

(def lake
  "Water six deep over the stone floor's top, a whole chunk column of it."
  (world/column
   (into {} (for [x (range 16) z (range 16) y (range 58 64)] [[x y z] blocks/water-base]))))

(def rock
  "Stone from the floor up to y 80, a whole chunk column of it."
  (world/column (into {} (for [x (range 16) z (range 16) y (range 64 81)] [[x y z] 1]))))

(deftest a-way-swims-what-no-walk-crosses-and-tunnels-where-no-route-goes
  ;; gym batch stronghold-265a (plan run 38927120252, probe 38929010443): two landings on a lake's
  ;; shore, the stronghold across it, one on a mountainside with no routes; each waited :no-way
  (testing "on an island: a swim toward the far point"
    (let [w (assoc (searching [8.5 8.5] [])
                   :world/chunks
                   (assoc (into {} (for [cx (range -4 5) cz (range -4 5)] [[cx cz] lake]))
                          [0 0]
                          (world/column {})))]
      (is (nil? (stronghold/hop w [-200.0 8.5])) "no walk off the island")
      (is (= {:intent/kind :cross :intent/target [-200 64 8] :intent/for :stronghold}
             (stronghold/way w [-200.0 8.5] 64)))))
  (testing "shut in rock: a dry tunnel toward it, down to the height asked"
    (let [w (assoc (searching [8.5 8.5] [])
                   :world/chunks
                   (assoc (into {} (for [cx (range -4 5) cz (range -4 5)] [[cx cz] rock]))
                          [0 0]
                          (world/column (into {}
                                              (for [x (range 16)
                                                    z (range 16)
                                                    y (range 64 81)
                                                    :when (not (and (= [x z] [8 8]) (<= y 65)))]
                                                [[x y z] 1])))))]
      (is (nil? (stronghold/hop w [-200.0 8.5])))
      (is (= {:intent/kind :tunnel-to
              :intent/target [-200 30 8]
              :intent/steps 32
              :intent/dry? true
              :intent/for :stronghold}
             (stronghold/way w [-200.0 8.5] 30))))))

(deftest a-remembered-frame-is-the-stronghold-found
  (let [frame (blocks/default-state :end_portal_frame)
        w (searching [8.5 8.5] [])]
    (is (not (plan/done-by w row)))
    (is (plan/done-by (memory/observe w [40 20 60] frame) row) "however deep: chunk data holds it")
    (is (= [40 20 60] (stronghold/nearest-frame (memory/observe w [40 20 60] frame))))))

(deftest the-stronghold-is-found-by-throws-and-walks-against-the-model
  ;; issue #265 end to end against the server model (#260's eyes): a frame deep in stone 113 blocks
  ;; off, columns streamed two chunks round the player, six eyes held; a throw, the walk off the
  ;; line, a second throw, the walk to the crossing, the frame seen as its chunk loads
  (let [frame (blocks/default-state :end_portal_frame)
        plain (world/column-bytes {})
        columns (into {}
                      (for [cx (range -2 13)
                            cz (range -2 13)]
                        [[cx cz]
                         (if (= [cx cz] [5 5]) (world/column-bytes {[8 30 8] frame}) plain)]))
        [w sim] (sim/run main/step
                         (game/init fx/opts)
                         (sim/init {:columns columns
                                    :spawn [8.5 64.0 8.5]
                                    :view-distance 2
                                    :stronghold [88.0 30.0 88.0]
                                    :inventory {[:hotbar 0] {:item (recipe/item-id :ender_eye)
                                                             :count 6}}})
                         #(or (plan/done? %) (plan/failed? %))
                         600000
                         {:event/kind :go :go/goals [:stronghold]})]
    (is (plan/done? w) (pr-str (plan/summary w)))
    (is (= [88 30 88] (stronghold/nearest-frame w)))
    (is (<= 2 (count (:stronghold/throws w))) "two lines at least")
    (is (empty? (:sim/violations sim)))))

;; ---------------------------------------------------------------- the portal room

(def room-row (first (filter #(= :portal-room (:goal/id %)) plan/goals)))

(deftest the-portal-room-is-tunnelled-to-once-a-frame-is-remembered
  (let [frame (blocks/default-state :end_portal_frame)
        w (searching [8.5 8.5] [])
        seen (memory/observe w [40 20 60] frame)]
    (testing "no frame yet: the search"
      (is (= {:intent/kind :throw-eye :intent/for :stronghold}
             (plan/next-intent w room-row plan/goals))))
    (testing "a frame remembered 44 under the feet: a staircase toward it, a leg of 8 down"
      (is (= {:intent/kind :stairs-down :intent/to-y 56 :intent/dir [0 1] :intent/for :stronghold}
             (plan/next-intent seen room-row plan/goals)))
      (is (not (plan/done-by seen room-row))))
    (testing "the last leg ends a block over the frame"
      (is (= 21
             (:intent/to-y
              (plan/next-intent (assoc seen :player/pos [8.5 29.0 8.5]) room-row plan/goals)))))
    (testing "within 8 of it: the dry tunnel"
      (is (= {:intent/kind :tunnel-to
              :intent/target [40 20 60]
              :intent/steps 32
              :intent/dry? true
              :intent/from-feet [8 26 8]}
             (plan/next-intent (assoc seen :player/pos [8.5 26.0 8.5]) room-row plan/goals))))
    (testing "a tunnel that took every step without arriving: the stairs"
      (let [spent (assoc seen
                         :player/pos [8.5 26.0 8.5]
                         :plan/last
                         {:intent/kind :tunnel-to
                          :intent/target [40 20 60]
                          :intent/status :done
                          :intent/taken 32
                          :intent/steps 32})]
        (is (stronghold/tunnel-spent? spent [40 20 60]))
        (is (= {:intent/kind :stairs-down :intent/to-y 21 :intent/dir [0 1] :intent/for :stronghold}
               (plan/next-intent spent room-row plan/goals)))
        (testing "one that came nearer is the way on, not spent"
          (let [on (assoc-in spent [:plan/last :intent/from-feet] [0 26 0])]
            (is (not (stronghold/tunnel-spent? on [40 20 60])))
            (is (= :tunnel-to (:intent/kind (plan/next-intent on room-row plan/goals))))))
        (is (stronghold/tunnel-spent? (assoc-in spent [:plan/last :intent/from-feet] [8 26 9])
                                      [40 20 60])
            "a block nearer is nowhere")))
    (testing "within deep over the frame, tunnel move blocked: stairs down fallback"
      (let [w (assoc seen :player/pos [8.5 26.0 8.5])]
        (with-redefs [fortress/choose-move (constantly nil)]
          (is (= :stairs-down (:intent/kind (plan/next-intent w room-row plan/goals)))
              "when dry tunnel has no first step within deep, falls back to stairs"))))
    (testing "when feet cell is in :plan/boxed-at, stairs down fallback is refused"
      (let [w (assoc seen :player/pos [8.5 26.0 8.5] :plan/boxed-at #{[8 26 8]})]
        (with-redefs [fortress/choose-move (constantly nil)]
          (is (= {:plan/wait :no-way} (plan/next-intent w room-row plan/goals))
              "a boxed feet cell never attempts stairs down again"))))
    (testing "within 6 of it: there"
      (is (plan/done-by (assoc seen :player/pos [40.5 22.0 58.5]) room-row)))
    (testing "in water: out to the nearest bank first"
      (is (= {:intent/kind :swim-out :intent/for :retreat}
             (plan/next-intent (assoc seen :player/in-water? true) room-row plan/goals))))
    (testing "on a shore: a walk to a dry start first, water two off the feet no start"
      (let [shore (assoc seen
                         :world/chunks
                         {[0 0] (world/column (into {}
                                                    (for [x (range 10 16)
                                                          z (range 16)]
                                                      [[x 64 z] blocks/water-base])))})
            start (stronghold/dry-start shore [40 20 60])]
        (is (not (stronghold/dry-cell? shore [8 64 8])))
        (is (= [7 64 9] start) "the nearest dry cell, the one nearer the frame first")
        (is (= {:intent/kind :walk :intent/target [7 64 9] :intent/for :retreat}
               (plan/next-intent shore room-row plan/goals)))
        (is (= :stairs-down
               (:intent/kind
                (plan/next-intent (assoc shore :player/pos [7.5 64.0 9.5]) room-row plan/goals)))
            "standing dry: the stairs")
        (is (= :stairs-down
               (:intent/kind (plan/next-intent
                              (assoc shore
                                     :player/pos [7.5 52.0 9.5]
                                     :plan/last {:intent/kind :stairs-down :intent/status :done})
                              room-row
                              plan/goals)))
            "after stairs have begun: continues stairs down, never retreats to the shore")))
    (testing "water everywhere round: no dry start, the stairs where the feet are"
      (let [wet (assoc seen
                       :world/chunks
                       {[0 0] (world/column (into {}
                                                  (for [x (range 16)
                                                        z (range 16)
                                                        y [64 65]
                                                        :when (not= [x z] [8 8])]
                                                    [[x y z] blocks/water-base])))})]
        (is (nil? (stronghold/dry-start wet [40 20 60])))
        (is (nil? (stronghold/far-start wet)) "nothing loaded past the lake")
        (is (= {:intent/kind :stairs-down :intent/to-y 56 :intent/dir [0 1] :intent/for :stronghold}
               (plan/next-intent wet room-row plan/goals)))
        (testing "with ground walked to 92 off: a swim back to it"
          (let [walked (memory/remember-intention
                        wet
                        {:intent/id 7 :intent/kind :walk :intent/target [100 64 8]}
                        :done)]
            (is (= [100 64 8] (stronghold/walked-ground walked)))
            (is (= {:intent/kind :cross :intent/target [100 64 8] :intent/for :stronghold}
                   (plan/next-intent walked room-row plan/goals)))
            (is (= :stairs-down
                   (:intent/kind
                    (plan/next-intent
                     (assoc walked :plan/last {:intent/kind :stairs-down :intent/status :done})
                     room-row
                     plan/goals)))
                "the way down begun: the stairs go on")))))
    (testing "a lake round, dry ground 24 off: a swim to the nearest dry cell past it"
      (let [lake (fn [x z]
                   (for [x (range x (+ x 16))
                         z (range z (+ z 16))
                         y [64 65]
                         :when (not= [x z] [8 8])]
                     [[x y z] blocks/water-base]))
            far (assoc seen
                       :world/chunks
                       {[0 0] (world/column (into {} (lake 0 0)))
                        [1 0] (world/column (into {} (lake 16 0)))
                        [2 0] (world/column {})})]
        (is (nil? (stronghold/dry-start far [40 20 60])))
        (is (= [34 64 8] (stronghold/far-start far)) "two clear of the lake's water")
        (is (= {:intent/kind :cross :intent/target [34 64 8] :intent/for :stronghold}
               (plan/next-intent far room-row plan/goals)))))))

(deftest the-portal-room-is-reached-against-the-model
  ;; a frame 24 blocks under the feet in stone, a pond two blocks from the landing and a cave
  ;; between: the walk to a dry start, the staircase in legs (its floors laid over the cave from
  ;; the cobblestone carried), the tunnel the last blocks to within 6 of the frame; an iron
  ;; pickaxe for the digs
  (let [frame (blocks/default-state :end_portal_frame)
        plain (world/column-bytes {})
        pond (for [x (range 10 13) z (range 7 10)] [[x 63 z] blocks/water-base])
        cave (for [x (range 2 14) z (range 2 14) y (range 50 54)] [[x y z] blocks/air])
        columns (into {}
                      (for [cx (range -1 2)
                            cz (range -1 2)]
                        [[cx cz]
                         (if (= [cx cz] [0 0])
                           (world/column-bytes (into {[8 40 8] frame} (concat pond cave)))
                           plain)]))
        [w sim] (sim/run main/step
                         (game/init fx/opts)
                         (sim/init {:columns columns
                                    :spawn [8.5 64.0 8.5]
                                    :inventory
                                    {[:hotbar 0] {:item (recipe/item-id :iron_pickaxe) :count 1}
                                     [:hotbar 1] {:item (recipe/item-id :cobblestone) :count 64}}})
                         #(or (plan/done? %) (plan/failed? %))
                         600000
                         {:event/kind :go :go/goals [:portal-room]})]
    (is (plan/done? w) (pr-str (plan/summary w)))
    (is (< (second (:player/pos w)) 48.0) "down by the frame")
    (is (= frame (sim/block-at sim [8 40 8])) "the frame never dug")
    (is (empty? (:sim/violations sim)))))

(defn hill-top
  "The top stone cell's y of the hill at lx: a plateau at 79 to lx 8, then a slope east falling
   two a block, steeper than a stair's one in one, to 65 at lx 15."
  [lx]
  (if (<= lx 8) 79 (- 79 (* 2 (- lx 8)))))

(def hill-blocks
  "Stone over the floor making the hill (hill-top), one chunk column of it."
  (into {} (for [lx (range 16) lz (range 16) y (range 64 (inc (hill-top lx)))] [[lx y lz] 1])))

(deftest a-mountain-is-walked-down-toward-the-frame-before-the-stairs
  ;; gym batch stronghold-room-e run 3: stairs cut from a mountain's slope left it into the air
  ;; and laid a bridge into the sky over a flooded valley, :boxed
  (let [frame (blocks/default-state :end_portal_frame)
        f [40 20 8]
        seen (-> (searching [4.5 8.5] [])
                 (assoc-in [:world/chunks [0 0]] (world/column hill-blocks))
                 (assoc :player/pos [4.5 80.0 8.5])
                 (memory/observe f frame))]
    (testing "on the plateau under the sky, the slope toward the frame lower: a walk down it"
      (is
       (= {:intent/kind :walk :intent/target [12 72 8] :intent/for :stronghold}
          (stronghold/downhill seen f))
       "32 and 16 along the way the ground is more than hop-rise under the feet; 8 along, the slope")
      (is (= :walk (:intent/kind (plan/next-intent seen room-row plan/goals)))))
    (testing "at the slope's foot, nothing lower toward the frame: the stairs"
      (let [foot (assoc seen :player/pos [16.5 64.0 8.5])]
        (is (nil? (stronghold/downhill foot f)))
        (is (= :stairs-down (:intent/kind (plan/next-intent foot room-row plan/goals))))))
    (testing "under rock, not the sky: the stairs"
      (let [roofed
            (assoc-in seen [:world/chunks [0 0]] (world/column (assoc hill-blocks [4 83 8] 1)))]
        (is (nil? (stronghold/downhill roofed f)))
        (is (= :stairs-down (:intent/kind (plan/next-intent roofed room-row plan/goals))))))
    (testing "the cell a walk failed at is not chosen again: the way turned"
      (is (= [11 74 12]
             (:intent/target (stronghold/downhill (assoc seen :plan/blacklist #{[12 72 8]}) f)))))))

(deftest the-portal-room-is-reached-down-a-mountain-against-the-model
  ;; the landing on the hill's plateau 16 over the valley floor, the frame 24 under the floor
  ;; 36 blocks east: the walk down the slope, the staircase from the foot, the tunnel the last
  ;; blocks; no floor laid into the air over the slope
  (let [frame (blocks/default-state :end_portal_frame)
        cobblestone (blocks/default-state :cobblestone)
        plain (world/column-bytes {})
        columns (into {}
                      (for [cx (range -1 4)
                            cz (range -1 2)]
                        [[cx cz]
                         (case [cx cz]
                           [0 0] (world/column-bytes hill-blocks)
                           [2 0] (world/column-bytes {[8 40 8] frame})
                           plain)]))
        [w sim] (sim/run main/step
                         (game/init fx/opts)
                         (sim/init {:columns columns
                                    :spawn [4.5 80.0 8.5]
                                    :inventory
                                    {[:hotbar 0] {:item (recipe/item-id :iron_pickaxe) :count 1}
                                     [:hotbar 1] {:item (recipe/item-id :cobblestone) :count 64}}})
                         #(or (plan/done? %) (plan/failed? %))
                         600000
                         {:event/kind :go :go/goals [:portal-room]})
        over-slope (for [lx (range 9 16) y (range (inc (hill-top lx)) 81)] [lx y 8])]
    (is (plan/done? w) (pr-str (plan/summary w)))
    (is (< (second (:player/pos w)) 48.0) "down by the frame")
    (is (= frame (sim/block-at sim [40 40 8])) "the frame never dug")
    (is (not-any? #(= cobblestone (sim/block-at sim %)) over-slope) "no bridge into the air")
    (is (empty? (:sim/violations sim)))))
