(ns clojurecraft.main-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojurecraft.main :as main]))

(deftest an-event-from-outside-is-checked-before-it-is-queued
  (testing "the fixture's :go is an event"
    (is (= [{:event/kind :go :go/goals [:wood] :go/at [0.5 64.0 0.5]} nil]
           (main/external-event "{:event/kind :go :go/goals [:wood] :go/at [0.5 64.0 0.5]}"))))
  (testing "anything else is dropped with the reason"
    (let [[e why] (main/external-event "{:event/kind :go :go/at [0 64 0]}")]
      (is (nil? e))
      (is (re-find #"not an event" why) "a landing must be a position of doubles"))
    (let [[e why] (main/external-event "{:event/kind :jump}")]
      (is (nil? e))
      (is (re-find #"not an event" why)))
    (let [[e why] (main/external-event "{:event/kind")]
      (is (nil? e))
      (is (re-find #"unreadable" why)))))
