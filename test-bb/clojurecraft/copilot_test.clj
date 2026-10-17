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
