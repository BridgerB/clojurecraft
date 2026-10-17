(ns clojurecraft.fleet-test
  "The fleet's choice of lanes to replace for their code (bb fleet): a lane on code main has
   replaced plays its minimum time first, a queued one goes at once, and one on current code
   stays. Babashka runs these (bb test:bb)."
  (:require [clojure.test :refer [deftest is testing]]
            [clojurecraft.fleet :as fleet]))

(def now (.toEpochMilli (java.time.Instant/parse "2026-10-17T12:00:00Z")))

(defn lane
  "A running lane created minutes-ago before now, on sha."
  [minutes-ago sha]
  {:lane 1
   :sha sha
   :status "in_progress"
   :created (str (java.time.Instant/ofEpochMilli (- now (* 60000 minutes-ago))))})

(deftest a-lane-on-old-code-plays-its-time-first
  (testing "26 minutes in, on code main replaced: kept (its runs need 30 to reach the Nether)"
    (is (not (fleet/stale? (lane 26 "old") #{"old"} 120 now))))
  (testing "two hours in: replaced" (is (fleet/stale? (lane 120 "old") #{"old"} 120 now)))
  (testing "queued on old code: replaced at once, it holds no runner"
    (is (fleet/stale? (assoc (lane 1 "old") :status "queued") #{"old"} 120 now)))
  (testing "on current code: never" (is (not (fleet/stale? (lane 500 "new") #{"old"} 120 now))))
  (testing "a round of the old matrix: always"
    (is (fleet/stale? (dissoc (lane 1 "new") :lane) #{} 120 now))))
