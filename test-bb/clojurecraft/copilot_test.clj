(ns clojurecraft.copilot-test
  "The orchestrator's choices (bb copilot dispatch): a hand-off goes to a person without a
   follow-up, a fix with bot files is asked to go on, and an assignment with nothing to show
   after the grace is released for the queue. Babashka runs these (bb test:bb)."
  (:require [clojure.test :refer [deftest is testing]]
            [clojurecraft.copilot :as copilot]))

(def now 1000000000)

(deftest a-hand-off-changes-nothing-of-the-bot
  (testing "#352: evidence only, though GitHub's suffix says Fixes"
    (is (copilot/hand-off? #{351} ["test/issues/351/R1-32-1.edn.gz"])))
  (testing "a Refs-only body" (is (copilot/hand-off? #{} ["src/clojurecraft/build.clj"])))
  (testing "a fix changes the bot"
    (is (not (copilot/hand-off? #{350} ["src/clojurecraft/build.clj" "test/issues/350/a.edn.gz"])))
    (is (not (copilot/hand-off? #{350} ["resources/clojurecraft/goals.edn"])))))

(deftest a-hand-off-is-never-asked-to-go-on
  (let [issue {:number 351 :labels ["bot-failure" "sim"] :assignees ["Copilot"]}
        pr {:number 352 :branch "copilot/x" :checks :red :nudges 0}]
    (is (= :gave-up (:move (copilot/issue-move issue (assoc pr :gave-up? true) #{} nil now))))
    (is (= :nudge (:move (copilot/issue-move issue (assoc pr :gave-up? false) #{} nil now))))
    (testing "a draft with only evidence is a session at work, never a hand-off (#386)"
      (is (= :ready
             (:move
              (copilot/issue-move issue (assoc pr :gave-up? true :draft? true) #{} nil now)))))))

(deftest a-stale-assignment-is-released
  (let [issue {:number 45 :labels ["bot-failure"] :assignees ["BridgerB" "Copilot"]}]
    (testing "past the grace with no pull request and no session: off the issue, not reassigned"
      (is (= :unassign
             (:move (copilot/issue-move issue nil #{} (- now copilot/start-grace-ms 1) now)))))
    (testing "inside the grace: wait for the session"
      (is (= :wait (:move (copilot/issue-move issue nil #{} (- now 1000) now)))))))

(deftest the-most-frequent-failure-first
  (let [batches [{:kind "real"
                  :runs 100
                  :ended "2026-10-17T20:00:00Z"
                  :failures [["portal" "no-stand" 15] ["ingots" "boxed" 2]]}
                 {:kind "sim"
                  :runs 1000
                  :ended "2026-10-17T20:00:00Z"
                  :failures [["cast-one" "timeout" 90] ["ingots" "boxed" 10]]}
                 {:kind "real"
                  :runs 50
                  :ended "2026-10-17T01:00:00Z"
                  :failures [["water-bucket" "timeout" 50]]}]
        w (copilot/weights batches "2026-10-17T14:00:00Z")]
    (testing "each kind's share of its own runs, older batches left out"
      (is (= 0.15 (get w "portal no-stand")))
      (is (= 0.09 (get w "cast-one timeout")))
      (is (= 0.03 (get w "ingots boxed")))
      (is (nil? (get w "water-bucket timeout"))))
    (testing "the queue: heaviest first, then newest"
      (is (= [196 135 37 400 300]
             (map :number
                  (copilot/ranked [{:number 300 :title "Bot stops at wood: no-log"}
                                   {:number 37 :title "Bot stops at ingots: boxed"}
                                   {:number 196 :title "Bot stops at portal: no-stand"}
                                   {:number 400 :title "Bot stops at furnace: boxed"}
                                   {:number 135 :title "Bot stops at cast-one: timeout"}]
                                  w)))))))
