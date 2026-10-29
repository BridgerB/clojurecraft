(ns clojurecraft.fleet-test
  "The fleet's choice of lanes to replace for their code (bb fleet): a lane on code main has
   replaced plays its minimum time first, a queued one goes at once, and one on current code
   stays; and after a lane is given back for need, the fleet fills only to its floor for the
   cooldown. Babashka runs these (bb test:bb)."
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

(def config
  "ci/fleet.edn's shape, cap 20, floor two a kind, a 20-minute cooldown."
  {:fleet/cap 20
   :fleet/floor {:real 2 :sim 2}
   :fleet/max {:real 8 :sim 8}
   :fleet/dispatch-grace-s 180
   :fleet/stale-after-minutes 120
   :fleet/cooldown-s 1200})

(defn run
  "A running lane run of kind k, lane n, created minutes-ago before now, with id."
  [k n minutes-ago id]
  {:id id
   :path ({:real ".github/workflows/race.yml" :sim ".github/workflows/simrace.yml"} k)
   :title (str ({:real "race" :sim "simrace"} k) " lane " n)
   :sha "main"
   :status "in_progress"
   :created (str (java.time.Instant/ofEpochMilli (- now (* 60000 minutes-ago))))})

(def at-floor
  "Two real and two sim lanes running, nothing else."
  [(run :real 1 200 1) (run :real 2 200 2) (run :sim 1 200 3) (run :sim 2 200 4)])

(defn look
  "A look over runs and jobs with the fleet's memory."
  [runs jobs memory]
  {:runs runs :jobs jobs :stale-shas #{} :memory memory :config config :now now :continuous true})

(deftest a-lane-given-back-holds-the-fleet-at-its-floor-for-the-cooldown
  (testing "five minutes after a give-back, free runners get no lane above the floor"
    (is (= [] (:dispatch (fleet/decide (look at-floor {} {:gave-back (- now 300000)})))))
    (is (:cooling? (:why (fleet/decide (look at-floor {} {:gave-back (- now 300000)}))))))
  (testing "a kind under its floor is still filled while cooling"
    (is (= [[:real 1]]
           (:dispatch (fleet/decide (look (rest at-floor) {} {:gave-back (- now 300000)}))))))
  (testing "after the cooldown, the free runners fill to the max: six lanes a kind"
    (is (= 12 (count (:dispatch (fleet/decide (look at-floor {} {:gave-back (- now 1500000)})))))))
  (testing "never given back: no cooldown"
    (is (= 12 (count (:dispatch (fleet/decide (look at-floor {} {}))))))))

(deftest a-give-back-for-need-starts-the-cooldown
  (let [runs (conj
              at-floor
              (run :real 3 10 5)
              {:id 9 :path ".github/workflows/plan.yml" :title "plan" :sha "x" :status "queued"})
        jobs {9 {:running 15 :queued 1}}
        d (fleet/decide (look runs jobs {}))]
    (testing "the newest lane above the floor goes" (is (= [5] (map :id (:given-back d)))))
    (testing "remembered as the give-back time"
      (is (= now (:gave-back (fleet/remember {} d runs config now)))))
    (testing "a look with nothing given back keeps the old time"
      (is (= 7
             (:gave-back
              (fleet/remember {:gave-back 7} {:cancel [] :dispatch []} runs config now)))))))
