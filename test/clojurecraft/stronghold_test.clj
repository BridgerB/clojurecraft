(ns clojurecraft.stronghold-test
  "Finding the stronghold (issue #265): two throws' lines crossed on the plane, nothing when they
   are parallel or cross behind a throw; a throw followed tick by tick over canned packets."
  (:require [clojure.math :as math]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [clojurecraft.fixtures :as fx]
            [clojurecraft.game :as game]
            [clojurecraft.intent :as intent]
            [clojurecraft.plan :as plan]
            [clojurecraft.recipe :as recipe]
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
                                (fx/ticks 2950 3050)))
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
