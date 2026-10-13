(ns clojurecraft.capture-test
  "What the capture takes round a landing: stops whose views cover every column within its
   radius, and the columns kept being exactly those."
  (:require [clojure.test :refer [deftest is]]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [clojurecraft.capture :as capture]))

(defn seen
  "The columns a client standing at each stop is sent: view chunks round it on each axis."
  [stops]
  (set (for [[x z] stops
             dx (range (- capture/view) (inc capture/view))
             dz (range (- capture/view) (inc capture/view))]
         [(+ (capture/chunk-of x) dx) (+ (capture/chunk-of z) dz)])))

(deftest the-stops-see-every-column-of-the-square
  ;; any landing, any radius: no column within radius of the landing's is out of every stop's
  ;; view, so a complete capture is possible from the stops alone
  (let [r (tc/quick-check 300
                          (prop/for-all [x (gen/choose -30000 30000) z (gen/choose -30000 30000)
                                         radius (gen/choose 0 24)]
                                        (let [landing [x z 70]
                                              [cx cz] [(capture/chunk-of x) (capture/chunk-of z)]
                                              want
                                              (for [kx (range (- cx radius) (inc (+ cx radius)))
                                                    kz (range (- cz radius) (inc (+ cz radius)))]
                                                [kx kz])]
                                          (every? (seen (capture/stops landing radius)) want))))]
    (is (:pass? r) (pr-str (select-keys r [:fail :shrunk :num-tests :seed])))))

(deftest a-landing-keeps-the-columns-within-its-radius
  (let [got (into {} (for [x (range -3 4) z (range -3 4)] [[x z] (byte-array 1)]))
        landing [8 8 70]] ; chunk [0 0]
    (is (= 25 (count (capture/within got landing 2))))
    (is (capture/complete? got landing 2))
    (is (not (capture/complete? (dissoc got [2 2]) landing 2)) "a corner missing is incomplete")
    (is (= 1 (count (capture/within got landing 0))))))
