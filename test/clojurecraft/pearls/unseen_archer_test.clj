(ns clojurecraft.pearls.unseen-archer-test
  "A skeleton behind a wall is no swarm: every mover of the hunt is gated on swarmed?."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [clojurecraft.fixtures :as fx]
            [clojurecraft.pearls-support :as ps]
            [clojurecraft.pearls.sense :as psense]))

(use-fixtures :once fx/instrumented)

(deftest a-skeleton-out-of-sight-is-no-swarm
  ;; real-18 Clj_r02: one skeleton 11 to 12 blocks off, unseen, held the hunt on one spot looking
  ;; out for 148 minutes: travel, relocate, wander, the cage-break cuts and the heading all wait
  ;; on swarmed?, and only a lookout was left
  (let [skeleton (ps/mob :skeleton [8.5 64.0 15.5])]
    (is (psense/shooter-kind? :skeleton))
    (is (psense/shooter-kind? :blaze))
    (is (not (psense/shooter-kind? :zombie)))
    (is (psense/swarmed? (ps/standing {5 skeleton})) "in sight: a swarm, as before")
    (is (not (psense/swarmed? (ps/standing {5 skeleton} ps/wall))) "behind the wall: none")))
