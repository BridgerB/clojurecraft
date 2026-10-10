(ns clojurecraft.memory-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojurecraft.blocks :as blocks]
            [clojurecraft.fixtures :as fx]
            [clojurecraft.game :as game]
            [clojurecraft.memory :as memory]))

(def oak-y 137)                       ; oak_log, axis y
(def oak-x 136)                       ; oak_log, axis x

(defn seen
  "A world that observed each [pos id] in order, one millisecond apart."
  [& sightings]
  (reduce (fn [w [i [pos id]]] (memory/observe (assoc w :time/now i) pos id))
          (game/init fx/opts) (map-indexed vector sightings)))

(deftest positions-now-is-what-was-seen-and-not-seen-gone
  (let [w (seen [[0 64 0] oak-y]                 ; a log, later dug
                [[1 64 0] oak-y]                 ; a log, later seen as another log state
                [[2 64 0] oak-y]                 ; a log, never seen again
                [[0 64 0] blocks/air]
                [[1 64 0] oak-x])]
    (is (= #{[1 64 0] [2 64 0]} (set (memory/positions-now w blocks/log-states))))
    (testing "the history is still there: it was a log once"
      (is (= #{[0 64 0] [1 64 0] [2 64 0]} (set (memory/positions-ever w blocks/log-states)))))
    (testing "a place seen gone and then a log again counts"
      (let [w (memory/observe (assoc w :time/now 9) [0 64 0] oak-y)]
        (is (contains? (set (memory/positions-now w blocks/log-states)) [0 64 0]))))))
