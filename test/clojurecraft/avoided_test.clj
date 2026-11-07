(ns clojurecraft.avoided-test
  "avoided answers what the code it replaced answered: the same sets and maps under the same
   :plan/* attributes."
  (:require [clojure.test :refer [deftest is]]
            [clojurecraft.avoided :as avoided]))

(deftest a-set-kind-notes-and-answers
  (let [w (-> {}
              (avoided/note :boxed-at [1 64 1])
              (avoided/note :boxed-at [1 64 1])
              (avoided/note-all :boxed-route [[2 64 2] [3 64 3]]))]
    (is (= #{[1 64 1]} (:plan/boxed-at w)))
    (is (avoided/noted? w :boxed-at [1 64 1]))
    (is (not (avoided/noted? w :boxed-at [9 9 9])))
    (is (= #{[2 64 2] [3 64 3]} (avoided/all-of w :boxed-route)))
    (is (= #{} (avoided/all-of {} :cornered)) "empty, never nil")))

(deftest a-map-kind-marks-bumps-and-forgets
  (let [w (-> {}
              (avoided/bump :trunk-failures [4 5])
              (avoided/bump :trunk-failures [4 5])
              (avoided/mark :given-up 7 1000))]
    (is (= {[4 5] 2} (:plan/trunk-failures w)))
    (is (= 1000 (avoided/mark-of w :given-up 7)))
    (is (nil? (avoided/mark-of w :given-up 8)))
    (is (= 0 (avoided/mark-of w :given-up 8 0)))
    (is (= {} (avoided/all-of (avoided/forget w :trunk-failures) :trunk-failures)))
    (is (= {} (avoided/all-of {} :backed-off)))))
