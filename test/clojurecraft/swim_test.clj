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

(deftest at-sea-with-no-bank-in-reach-the-escape-swims-for-the-shore
  ;; sim race 38811426215 runner 8 (issue #89, worlds R4/21 and R1/32): bots under the sea floor,
  ;; land 33 and 40 blocks off; an escape with no bank within reach rose and floated until it
  ;; timed out
  (let [sea (fn [xs]
              (into {} (for [x xs y (range 55 64) z (range 16)] [[x y z] blocks/water-base])))
        swim {:intent/kind :swim-out :intent/status :active}
        w (assoc (game/init fx/opts)
                 :bot/phase :play
                 :player/pos [8.5 62.0 8.5]
                 :player/loaded? true
                 :player/in-water? true
                 :player/eye-in-water? true
                 :time/tick 10
                 :world/chunks
                 {[0 0] (world/column (sea (range 16)))
                  [1 0] (world/column (sea (range 16)))
                  [2 0] (world/column (sea (range 8)))
                  [3 0] (world/column {})}
                 :plan/intent swim)
        tick {:event/kind :tick :event/now 1000 :event/rand 0.5}
        w2 (intent/run w swim tick)
        i (:plan/intent w2)]
    (is (nil? (:intent/bank i)) "no bank within reach")
    (is (= [40 64 8] (:intent/shore i)) "the shore east, at the water's surface")
    (is (= [true true]
           [(:control/forward? (:player/controls w2)) (:control/jump? (:player/controls w2))])
        "swimming toward it, rising")))

(deftest a-crossing-swims-at-the-surface-and-ends-on-the-far-side
  ;; issue #87 (world R3/4): the :cross wood/crossing starts from an island
  (let [lake (into {}
                   (for [x (range 16)
                         z (range 16)
                         y (range 60 64)
                         :when (< 3 x 13)]
                     [[x y z] blocks/water-base]))
        cross {:intent/kind :cross :intent/target [14 64 8] :intent/status :active}
        at (fn [pos in-water?]
             (assoc (game/init fx/opts)
                    :bot/phase :play
                    :player/pos pos
                    :player/loaded? true
                    :player/on-ground? (not in-water?)
                    :player/in-water? in-water?
                    :time/tick 10
                    :world/chunks {[0 0] (world/column lake)}
                    :plan/intent cross))
        tick {:event/kind :tick :event/now 1000 :event/rand 0.5}
        swimming (intent/run (at [6.5 63.0 8.5] true) cross tick)]
    (is (= :active (:intent/status (:plan/intent swimming))))
    (is (true? (:control/jump? (:player/controls swimming))) "jump held: the eye stays in air")
    (is (true? (:control/forward? (:player/controls swimming))))
    (is (= :active
           (:intent/status (:plan/intent (intent/run (at [2.5 64.0 8.5] false) cross tick))))
        "still on the near shore, never wet: not done")
    (let [i (assoc cross :intent/wet? true :intent/from-dist 11.0 :intent/started 0)
          landed (assoc (at [13.5 64.0 8.5] false) :plan/intent i)]
      (is (= :done (:intent/status (:plan/intent (intent/run landed i tick))))
          "wet, then on dry land ten nearer the trunk: across"))))
