(ns clojurecraft.race-test
  "The race (issue #17): the claims reducer never grants one cell to two bots, the spiral visits
   every cell once, and the race's board and summary are functions of values."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [clojurecraft.claims :as claims]
            [clojurecraft.fixtures :as fx]
            [clojurecraft.game :as game]
            [clojurecraft.plan :as plan]
            [clojurecraft.race :as race]))

(def gen-request
  (gen/hash-map :claim/by (gen/elements ["Clj_r01" "Clj_r02" "Clj_r03" "Clj_r04"])
                :claim/cell (gen/tuple (gen/choose -3 3) (gen/choose -3 3))))

(deftest no-cell-is-granted-to-two-bots
  (let [r (tc/quick-check 500
                          (prop/for-all
                           [requests (gen/vector gen-request 0 60)]
                           (let [answers (second (reduce (fn [[held answers] q]
                                                           (let [[held a] (claims/claim held q)]
                                                             [held (conj answers a)]))
                                                         [{} []]
                                                         requests))
                                 granted (filter :claim/granted? answers)]
                             (every? (fn [[_ as]] (= 1 (count (distinct (map :claim/by as)))))
                                     (group-by :claim/cell granted)))))]
    (is (:pass? r) (pr-str (select-keys r [:fail :shrunk])))))

(deftest first-come-first-served
  (let [[held a] (claims/claim {} {:claim/by "Clj_r01" :claim/cell [0 0]})
        [held b] (claims/claim held {:claim/by "Clj_r02" :claim/cell [0 0]})
        [_ c] (claims/claim held {:claim/by "Clj_r01" :claim/cell [0 0]})]
    (is (:claim/granted? a))
    (is (not (:claim/granted? b)) "taken")
    (is (:claim/granted? c) "the holder may ask again")))

(deftest the-spiral-visits-each-cell-once-ring-by-ring
  (let [cells (take 25 (claims/spiral))]
    (is (= [0 0] (first cells)))
    (is (= 25 (count (distinct cells))))
    (is (= (set (for [x (range -2 3) z (range -2 3)] [x z])) (set cells)))
    (is (every? #(<= (max (abs (first %)) (abs (second %))) 1) (take 9 cells))
        "the first ring first"))
  (testing "a cell and its middle agree"
    (is (= [2 -1] (claims/cell-of [100 100] (claims/middle [100 100] [2 -1]))))))

(deftest the-board-reads-a-world-value
  (is (= "Clj_r01" (race/bot-name "Clj_r" 1)))
  (let [w (plan/begin (assoc (game/init fx/opts) :player/pos [0.5 70.0 0.5])
                      {:go/goals (plan/through "nether") :go/chain? true}
                      plan/goals)]
    (is (= {:race/target :wood
            :race/reached 0
            :race/dimension "overworld"
            :race/y 70
            :race/deaths 0
            :race/status :active}
           (race/standing w)))
    (is (= "Clj_r01 :active at :wood reached 0 overworld y 70 deaths 0"
           (race/board-line "Clj_r01" w)))))

(deftest a-milestone-is-a-fact-of-what-was-reached
  ;; the observer diffs successive world values: what :plan/reached gained is what happened
  (let [before {:plan/reached #{:wood}}
        world {:plan/reached #{:wood :kit} :time/now 90000 :plan/since 30000}]
    (is (= [:kit] (race/reached-new before world)))
    (is (empty? (race/reached-new world world)) "nothing new, no fact")
    (is (= {:milestone/runner 7
            :milestone/seed "race-1-7"
            :milestone/bot "Clj_r03"
            :milestone/goal :kit
            :milestone/at 90000
            :milestone/after-s 60}
           (race/milestone {:runner 7 :seed "race-1-7"} "Clj_r03" world :kit)))
    (is (= 2
           (:milestone/server
            (race/milestone {:runner 7 :server 2 :seed "race-1-7-2"} "Clj_r03" world :kit)))
        "a runner with several servers says which")
    (is (not (contains? (race/milestone {:runner 7 :server nil :seed "s"} "Clj_r03" world :kit)
                        :milestone/server))
        "with one, the fact is as it was")))

(deftest a-racer-lands-on-its-own-landing-of-the-set
  ;; racer i of a server whose bots start at offset lands on landing offset + i, wrapping: the
  ;; servers and runners sharing a set land on different landings, the same ones every commit
  (let [set [[10 20 70] [30 40 71] [50 60 72]]]
    (is (= [[10 20] [30 40] [50 60]] (map #(race/landing-of set 0 %) [0 1 2])))
    (is (= [50 60] (race/landing-of set 2 0)) "a server's bots start at its offset")
    (is (= [10 20] (race/landing-of set 2 1)) "past the end, round to the start")
    (is (= (race/landing-of set 5 7) (race/landing-of set 5 7))
        "the same racer, the same landing")))

(deftest a-ramp-joins-bots-in-steps
  (let [cfg {:race/stagger-ms 3000 :race/ramp #:ramp{:start 10 :step 10 :every-ms 300000}}]
    (is (= [0 3000 27000] (map #(race/start-at cfg %) [0 1 9]))
        "the first ten together, a stagger apart")
    (is (= [300000 303000 600000] (map #(race/start-at cfg %) [10 11 20]))
        "then ten more every five minutes")
    (is (= 6000 (race/start-at {:race/stagger-ms 3000} 2)) "no ramp: a stagger apart")))

(deftest the-servers-tick-times-are-read-from-its-answer
  (is
   (=
    {:avg 4.0 :p50 3.6 :p95 5.0 :p99 18.7}
    (race/tick-times
     "The game is running normallyTarget tick rate: 20.0 per second. Average time per tick: 4.0ms (Target: 50.0ms)Percentiles: P50: 3.6ms P95: 5.0ms P99: 18.7ms. Sample: 100")))
  (is (= {} (race/tick-times nil))))

(deftest a-sample-is-the-race-at-one-moment
  (let [playing {:bot/phase :play
                 :player/loaded? true
                 :player/pos [0 64 0]
                 :plan/go-at [0 64 0]
                 :plan/status :active
                 :plan/reached #{:wood}}
        worlds {"a" (assoc playing :time/tick 400)
                "b" (assoc playing :time/tick 300 :plan/reached #{:wood :kit})
                "c" {:bot/phase :play :bot/disconnected "gone" :player/pos [0 64 0]}
                "d" (assoc playing :time/tick 100 :plan/status :done)}
        s (race/sample {:runner 3} worlds {"a" 100 "b" 100 "d" 100} 15000 {:p95 5.0} 900 1000)]
    (is (= 4 (:sample/joined s)))
    (is (= 2 (:sample/playing s)))
    (is (= 1 (:sample/cut-off s)))
    (is (= 4 (:sample/milestones s)))
    (is (= 1 (:sample/ended s)) "d reached its target: no longer playing, its loop stopped")
    (is (= 20.0 (:median (:sample/tick-rate s))) "a: 300 ticks in 15 s")
    (is (< 13.0 (:min (:sample/tick-rate s)) 14.0) "b fell behind: 200 ticks in 15 s")))

(deftest the-summary-is-a-fold-of-results
  (let [s (race/summary [{:name "a" :result {:ok true :reason :goal :plan {:plan/reached #{:wood}}}}
                         {:name "b" :result {:ok false :reason :no-site}}
                         {:name "c" :result {:ok false :reason :no-site}}])]
    (is (= 3 (:race/bots s)))
    (is (= 1 (:race/ok s)))
    (is (= {:goal 1 :no-site 2} (:race/reasons s)))
    (is (= ["a" "b" "c"] (map :name (:race/runs s))))))

(deftest a-race-plays-for-its-deadline-else-its-minutes
  (is (= 600000 (race/run-ms {:race/deadline-ms 600000 :race/run-minutes 330}))
      "the capacity ramp's one deadline")
  (is (= 19800000 (race/run-ms {:race/run-minutes 330 :race/timeout-ms 5})) "the churn's job")
  (is (= 5 (race/run-ms {:race/timeout-ms 5}))))

(deftest a-bot-the-job-ends-is-cut-never-filed
  (let [r {:ok false :until "nether" :reason :timeout :plan {:plan/reached #{:wood}}}
        m (race/marked r)]
    (is (:run/cut? m))
    (is (:run/infra? m) "bb failures files no infrastructure stop")
    (is (:run/signature m) "the stage it was on is still kept")
    (is (not (:run/cut? (race/marked (assoc r :reason :no-site)))) "a bot's own stop is a failure")
    (is (= {:ok true :reason :goal} (race/marked {:ok true :reason :goal})) "a pass is unchanged")))

(deftest a-slot-backs-off-while-its-bots-never-play
  (let [quick (dec race/quick-ms)]
    (is (= 1000 (race/backoff-ms 0 quick {:reason :crashed})) "the server refused: wait a second")
    (is (= 4000 (race/backoff-ms 2000 quick {:reason :closed})) "still gone: twice as long")
    (is (= race/backoff-max-ms (race/backoff-ms 50000 quick {:reason :disconnected}))
        "at most a minute")
    (is (zero? (race/backoff-ms 8000 race/quick-ms {:reason :crashed}))
        "a bot that played long enough")
    (is (zero? (race/backoff-ms 8000 quick {:reason :no-site}))
        "a bot's own stop is play, not a dead server")))
