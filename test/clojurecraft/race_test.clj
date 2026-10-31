(ns clojurecraft.race-test
  "The race (issue #17): the claims reducer never grants one cell to two bots, the spiral visits
   every cell once, and the race's board and summary are functions of values."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [clojurecraft.claims :as claims]
            [clojurecraft.failure :as failure]
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

(def refused
  "A bot whose server refused it (race 39817745908's server 2, gone 25 s in): play-one!'s
   crashed RESULT, its plan never begun."
  {:ok false :reason :crashed :error "Connection refused (connect failed)" :until "x" :plan {}})

(deftest a-bot-that-never-got-into-play-is-infrastructure
  (let [m (race/marked refused)]
    (is (:run/infra? m) "bb failures files it under infrastructure stops, never as an issue")
    (is (= [:wood :crashed] (take 2 (:run/signature (race/marked (assoc refused :until "nether")))))
        "its signature is still kept"))
  (is (:run/infra? (race/marked {:ok false :reason :closed :until "x"}))
      "connected, but closed before its :go: main leaves :plan out")
  (is (not (:run/infra? (race/marked (assoc refused :plan {:plan/status :failed}))))
      "a bot that crashed once it played is a failure of its play"))

(deftest a-server-whose-bots-never-play-is-down-until-it-accepts
  (let [played {:ok false :reason :no-site :plan {:plan/status :failed}}
        after (fn [rs] (reduce race/health race/recovered rs))]
    (is (= {:race/unplayed 4 :race/down? false} (after (repeat 4 refused))))
    (is (= {:race/unplayed race/dead-after :race/down? true}
           (after (repeat race/dead-after refused)))
        "dead-after in a row: down, and its slots start none")
    (is (= {:race/unplayed 0 :race/down? false} (after (concat (repeat 4 refused) [played])))
        "a bot that played resets the count")
    (is (:race/down? (after (concat (repeat race/dead-after refused) [played])))
        "down stays down until a probe finds it accepting (recovered), whatever ends meanwhile")
    (is (= {:race/unplayed 0 :race/down? false} (after [{:ok true :reason :goal}])))))

(deftest a-server-sees-at-most-one-new-bot-a-start-gap
  (is (= 5000 (race/next-start 0 5000)) "long since the last start: now")
  (is (= (+ 5000 race/start-gap-ms) (race/next-start 5000 5000)) "just started one: a gap on")
  (is (= (+ 5000 (* 15 race/start-gap-ms))
         (reduce (fn [last _] (race/next-start last 5000)) 5000 (range 15)))
      "fifteen slots ending at once start one a gap apart"))

(deftest a-race-from-the-fortress-plays-only-the-stages-from-the-blaze-rods
  ;; issue #262: stages 17 and 18 alone, each bot landed in the Nether at its landing's fortress
  (let [chain (plan/through "pearls-12")
        from (race/from-goals "fortress" "pearls-12")]
    (is (= chain (race/from-goals nil "pearls-12")) "no :race/from: every stage, as before")
    (is (= chain (race/from-goals "spawn" "pearls-12")))
    (is (= [:blaze-rods :ender-pearls] from) "the blaze rods, then the pearls")
    (is (= from (race/from-goals :fortress "pearls-12")))
    (is (race/fortress? "fortress"))
    (is (not (race/fortress? "spawn")))))

(deftest a-failure-of-a-fortress-race-is-filed-at-its-own-stages
  ;; a run that played only blaze-rods and ender-pearls: nothing reached, it failed at the blaze
  ;; rods, never at the wood the chain begins with
  (let [r {:until "pearls-12"
           :reason :timeout
           :plan {:plan/goals #{:blaze-rods :ender-pearls} :plan/reached #{}}}]
    (is (= [:blaze-rods :timeout] (failure/signature r)))
    (is (= [:ender-pearls :timeout]
           (failure/signature (assoc-in r [:plan :plan/reached] #{:blaze-rods}))))
    (testing "a run of the whole chain is as before"
      (is (= [:wood :timeout]
             (failure/signature {:until "pearls-12"
                                 :reason :timeout
                                 :plan {:plan/goals (set (plan/through "pearls-12"))
                                        :plan/reached #{}}})))
      (is (= [:wood :timeout]
             (failure/signature {:until "pearls-12" :reason :timeout :plan {:plan/reached #{}}}))
          "no goals recorded: the whole chain"))))

(deftest a-fortress-pad-is-a-place-and-spread-round-the-fortress
  ;; race 41219596455 (first fortress lanes): ten bots a server on one pad, and Clj_r10 and r11 told
  ;; :go/at [176 -160 72] (the pad's [x z y]) and ended :no-landing 30 s later
  (let [fortress [200 -160]
        pads (map #(race/pad-of fortress %) (range 8))]
    (is (= 8 (count (set pads))) "eight bots, eight pads")
    (is (= (first pads) (race/pad-of fortress 8)) "the ninth comes round to the first")
    (is (every? #(= 72 (nth % 2)) pads) "all at the pad height")
    (is (= [152 -160 72] (first pads)) "the first, 48 west of the fortress, as the gym's")
    (is (= [176.5 72.0 -159.5] (race/standing-on [176 -160 72])) "x y z: the middle of the cell")))

(deftest a-spawn-is-pinned-in-the-dimension-the-bot-landed-in
  ;; spawnpoint takes the dimension of its source, the server's overworld: a bot that died at the
  ;; fortress respawned in the overworld
  (is (= "execute in minecraft:the_nether run spawnpoint Clj_r01 152 72 -160"
         (race/spawn-command "Clj_r01" [152.5 72.0 -159.5] "minecraft:the_nether")))
  (is (= "execute in minecraft:overworld run spawnpoint Clj_r02 10 80 -4"
         (race/spawn-command "Clj_r02" [10.5 80.0 -3.5] "minecraft:overworld"))))

(deftest a-landing-near-a-located-fortress-is-not-searched-for-again
  ;; race 41218543330: fifteen bots at once asked the server to locate a fortress, a search in one
  ;; tick each; the tick stalled for 31 s and 120 bots timed out
  (is (nil? (race/nearby-fortress [] [10 10])))
  (is (= [200 -160] (race/nearby-fortress [[200 -160] [900 900]] [260 -100])) "within 128 blocks")
  (is (nil? (race/nearby-fortress [[200 -160]] [600 -160])) "400 off: another fortress"))

(deftest a-bot-is-teleported-onto-a-pad-built-before-it-joined
  ;; race 41225389190: the first pad was built 40 s after ten bots joined, generating Nether chunks
  ;; in the server's thread, and seven of them timed out; the pads are built first (prepare!)
  (is (= "execute in minecraft:the_nether run tp Clj_r03 152.5 72 -159.5"
         (race/tp-command "Clj_r03" [152 -160 72]))
      "x.5 y z.5 of the pad cell: -160 and .5 is -159.5, the middle of the cell"))

(deftest a-replacement-bot-lands-on-its-slots-pad
  ;; a bot that ends is replaced by the next serial (r11, r12 ...): lane 3 of race 41218543330 started
  ;; 116 runs in 31 minutes; a landing by serial would generate a new Nether region each time
  (is (= [0 1 9 0 1 9 5] (map #(race/slot-index % 10) [0 1 9 10 11 19 25])))
  (is (= 7 (race/slot-index 7 nil)) "no slot count: as before")
  (let [fortress [200 -160]]
    (is (= (race/pad-of fortress (race/slot-index 3 10))
           (race/pad-of fortress (race/slot-index 13 10)))
        "the serial after a slot's bot lands on the same pad")))
