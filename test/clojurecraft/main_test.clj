(ns clojurecraft.main-test
  (:require [clojure.core.async :as async]
            [clojure.test :refer [deftest is testing]]
            [clojurecraft.conn :as conn]
            [clojurecraft.game :as game]
            [clojurecraft.main :as main]))

(deftest run-bot-holds-on-timeout-for-truth-check
  (testing "a timeout with hold-ms holds the connection open for the outside judge before closing"
    (let [closed? (atom false)
          in (async/chan 1)
          out (async/chan 1)
          mock-conn {:in in :out out :close! (fn [] (reset! closed? true))}
          world* (atom (game/init {:host "127.0.0.1" :port 25565 :name "Test"}))]
      (with-redefs [conn/open! (fn [_] mock-conn)]
        (let [{:keys [result]} (main/run-bot!
                                {:until "pearls"
                                 :timeout-ms 0
                                 :hold-ms 50
                                 :world* world*
                                 :on-result (fn [_]
                                              (is (false? @closed?)
                                                  "bot is still connected when on-result fires"))})]
          (is (= :timeout (:reason result)))
          (is (false? (:ok result)))
          (is (true? @closed?) "connection is closed after the hold period"))))))

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
