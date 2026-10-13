(ns clojurecraft.memo-test
  "A remembered function answers as the function does (so a replay is the same run either way),
   remembers what it is asked again, and the world's parts it is keyed on keep their identity
   while nothing in them changed."
  (:require [clojure.test :refer [deftest is]]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [clojurecraft.memo :as memo]
            [clojurecraft.terrain :as terrain]))

(defn pool
  "Six inputs, two of each value, the pair equal but never identical: what a world's parts are
   to memo/by (a new map with the same contents is another object)."
  []
  (vec (for [v [{:a 1} {:a 2} {:b [1 2]}] _ [0 1]] (into {} v))))

(deftest a-remembered-function-answers-as-the-function-does
  ;; any sequence of calls over a few slots, so slots collide and are replaced, and equal parts
  ;; that are not the same object are asked as different inputs: every answer is f's
  (let [r (tc/quick-check
           300
           (prop/for-all
            [calls (gen/vector (gen/tuple (gen/choose 0 5) (gen/choose 0 3) (gen/choose 0 3)) 1 60)
             slots (gen/choose 1 4)]
            (let [parts (pool)
                  f (fn [part n m] [(hash part) (count part) (* n 7) (- m)])
                  remembered (memo/by [:identical :equal :equal] slots f)]
              (every? (fn [[i n m]] (= (f (parts i) n m) (remembered (parts i) n m))) calls))))]
    (is (:pass? r) (pr-str (select-keys r [:fail :shrunk :num-tests :seed])))))

(deftest a-call-asked-again-is-not-worked-again
  (let [worked (atom 0)
        part {:a 1}
        remembered (memo/by [:identical :equal] 8 (fn [p n] (swap! worked inc) [p n]))]
    (dotimes [_ 5] (remembered part 3))
    (is (= 1 @worked) "the same object and an equal small argument: one call")
    (remembered (into {} part) 3)
    (is (= 2 @worked) "an equal part that is another object is another input")
    (remembered part 4)
    (is (= 3 @worked) "another small argument is another call")))

(deftest the-overlay-keeps-its-identity-when-a-chunk-arrives-it-never-touched
  ;; plan-on and exposed-cells remember by the overlay's identity: a chunk loading far away must
  ;; not make it a new object (put-column rebuilt it on every chunk: a race's profile, a twentieth
  ;; of all the bots' CPU, and every memo missed)
  (let [overlay {[5 64 5] 1 [20 64 3] 0}]
    (is (identical? overlay (terrain/superseded overlay [4 4])) "nothing of chunk [4 4] in it")
    (is (= {[20 64 3] 0} (terrain/superseded overlay [0 0])) "chunk [0 0]'s block is dropped")
    (is (= {} (terrain/superseded nil [0 0])) "no overlay yet is an empty one")))
