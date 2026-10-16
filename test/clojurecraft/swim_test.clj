(ns clojurecraft.swim-test
  "The :swim-out executor's choices, one tick at a time, in small built worlds."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [clojurecraft.blocks :as blocks]
            [clojurecraft.fixtures :as fx]
            [clojurecraft.game :as game]
            [clojurecraft.intent :as intent]
            [clojurecraft.swim :as swim]
            [clojurecraft.world :as world]))

(use-fixtures :once fx/instrumented)

(deftest a-bank-flooded-since-it-was-picked-is-dropped-for-another
  ;; race 38805758078 runner 1 bot Clj_r44 (issue #157) and race 38709226128 runner 8 bot
  ;; Clj_r31 (issue #156), replayed in probe 38830884734: the swim-out reached the bank it had
  ;; picked a block off, water had flowed into it, the trap held, and stuck-ticks later it dug
  ;; over its head and failed :stuck
  (let [bank [5 64 5]
        swim {:intent/kind :swim-out
              :intent/bank bank
              :intent/target bank
              :intent/best-dist 0.0
              :intent/best-tick 0
              :intent/started 0
              :intent/status :active}
        w (assoc (game/init fx/opts)
                 :bot/phase :play
                 :player/pos [5.5 64.0 5.5]
                 :player/loaded? true
                 :player/on-ground? true
                 :player/in-water? true
                 :time/tick 10
                 :world/chunks {[0 0] (world/column {bank blocks/water-base})}
                 :plan/intent swim)
        tick {:event/kind :tick :event/now 1000 :event/rand 0.5}]
    (is (swim/flooded? w bank))
    (is (not (swim/flooded? w [8 64 5])) "a dry cell on the stone beside it")
    (let [w (intent/run w swim tick)
          i (:plan/intent w)]
      (is (= :active (:intent/status i)))
      (is (nil? (:intent/bank i)) "dropped, not kept until the swim-out is stuck")
      (let [b (:intent/bank (:plan/intent (intent/run w i tick)))]
        (is (some? b) "another bank picked")
        (is (not= bank b))))))
