(ns clojurecraft.stronghold-test
  "Finding the stronghold (issue #265): two throws' lines crossed on the plane, nothing when they
   are parallel or cross behind a throw."
  (:require [clojure.math :as math]
            [clojure.test :refer [deftest is testing]]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [clojurecraft.stronghold :as stronghold]))

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
