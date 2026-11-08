(ns clojurecraft.race-test
  "The race (issue #17): the claims reducer never grants one cell to two bots, the spiral visits
   every cell once, and the race's board and summary are functions of values."
  (:require [clojure.math :as math]
            [clojure.string :as str]
            [clojure.test :refer [are deftest is testing]]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [clojurecraft.claims :as claims]
            [clojurecraft.failure :as failure]
            [clojurecraft.fixtures :as fx]
            [clojurecraft.game :as game]
            [clojurecraft.land :as land]
            [clojurecraft.main :as main]
            [clojurecraft.plan :as plan]
            [clojurecraft.race :as race]
            [clojurecraft.race-facts :as facts]))

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
  (is (= "Clj_r01" (facts/bot-name "Clj_r" 1)))
  (let [w (plan/begin (assoc (game/init fx/opts) :player/pos [0.5 70.0 0.5])
                      {:go/goals (plan/through "nether") :go/chain? true}
                      plan/goals)]
    (is (= {:race/target :wood
            :race/reached 0
            :race/dimension "overworld"
            :race/y 70
            :race/deaths 0
            :race/status :active}
           (facts/standing w)))
    (is (= "Clj_r01 :active at :wood reached 0 overworld y 70 deaths 0"
           (facts/board-line "Clj_r01" w)))))

(deftest a-milestone-is-a-fact-of-what-was-reached
  ;; the observer diffs successive world values: what :plan/reached gained is what happened
  (let [before {:plan/reached #{:wood}}
        world {:plan/reached #{:wood :kit} :time/now 90000 :plan/since 30000}]
    (is (= [:kit] (facts/reached-new before world)))
    (is (empty? (facts/reached-new world world)) "nothing new, no fact")
    (is (= {:milestone/runner 7
            :milestone/seed "race-1-7"
            :milestone/bot "Clj_r03"
            :milestone/goal :kit
            :milestone/at 90000
            :milestone/after-s 60}
           (facts/milestone {:runner 7 :seed "race-1-7"} "Clj_r03" world :kit)))
    (is (= 2
           (:milestone/server
            (facts/milestone {:runner 7 :server 2 :seed "race-1-7-2"} "Clj_r03" world :kit)))
        "a runner with several servers says which")
    (is (not (contains? (facts/milestone {:runner 7 :server nil :seed "s"} "Clj_r03" world :kit)
                        :milestone/server))
        "with one, the fact is as it was")))

(deftest a-racer-lands-on-its-own-landing-of-the-set
  ;; racer i of a server whose bots start at offset lands on landing offset + i, wrapping: the
  ;; servers and runners sharing a set land on different landings, the same ones every commit
  (let [set [[10 20 70] [30 40 71] [50 60 72]]]
    (is (= [[10 20] [30 40] [50 60]] (map #(land/landing-of set 0 %) [0 1 2])))
    (is (= [50 60] (land/landing-of set 2 0)) "a server's bots start at its offset")
    (is (= [10 20] (land/landing-of set 2 1)) "past the end, round to the start")
    (is (= (land/landing-of set 5 7) (land/landing-of set 5 7))
        "the same racer, the same landing")))

(deftest racers-join-a-stagger-apart
  (is (= [0 3000 27000] (map #(facts/start-at {:race/stagger-ms 3000} %) [0 1 9]))))

(deftest the-servers-tick-times-are-read-from-its-answer
  (is
   (=
    {:avg 4.0 :p50 3.6 :p95 5.0 :p99 18.7}
    (facts/tick-times
     "The game is running normallyTarget tick rate: 20.0 per second. Average time per tick: 4.0ms (Target: 50.0ms)Percentiles: P50: 3.6ms P95: 5.0ms P99: 18.7ms. Sample: 100")))
  (is (= {} (facts/tick-times nil))))

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
        s (facts/sample {:runner 3} worlds {"a" 100 "b" 100 "d" 100} 15000 {:p95 5.0} 900 1000)]
    (is (= 4 (:sample/joined s)))
    (is (= 2 (:sample/playing s)))
    (is (= 1 (:sample/cut-off s)))
    (is (= 4 (:sample/milestones s)))
    (is (= 1 (:sample/ended s)) "d reached its target: no longer playing, its loop stopped")
    (is (= 20.0 (:median (:sample/tick-rate s))) "a: 300 ticks in 15 s")
    (is (< 13.0 (:min (:sample/tick-rate s)) 14.0) "b fell behind: 200 ticks in 15 s")))

(deftest the-summary-is-a-fold-of-results
  (let [s (facts/summary [{:name "a"
                           :result {:ok true :reason :goal :plan {:plan/reached #{:wood}}}}
                          {:name "b" :result {:ok false :reason :no-site}}
                          {:name "c" :result {:ok false :reason :no-site}}])]
    (is (= 3 (:race/bots s)))
    (is (= 1 (:race/ok s)))
    (is (= {:goal 1 :no-site 2} (:race/reasons s)))
    (is (= ["a" "b" "c"] (map :name (:race/runs s))))))

(deftest a-race-plays-for-its-minutes-else-its-timeout
  (is (= 19800000 (facts/run-ms {:race/run-minutes 330 :race/timeout-ms 5})) "the churn's job")
  (is (= 5 (facts/run-ms {:race/timeout-ms 5}))))

(deftest a-bot-the-job-ends-is-cut-never-filed
  (let [r {:ok false :until "nether" :reason :timeout :plan {:plan/reached #{:wood}}}
        m (facts/marked r)]
    (is (:run/cut? m))
    (is (:run/infra? m) "bb failures files no infrastructure stop")
    (is (:run/signature m) "the stage it was on is still kept")
    (is (not (:run/cut? (facts/marked (assoc r :reason :no-site)))) "a bot's own stop is a failure")
    (is (= {:ok true :reason :goal} (facts/marked {:ok true :reason :goal}))
        "a pass is unchanged")))

(deftest a-bot-is-cut-by-its-cap-or-the-job-whichever-comes-first
  (is (= 3600000 (facts/bot-timeout-ms 20000000 1000 3600000)) "the cap, when it comes first")
  (is (= 5000 (facts/bot-timeout-ms 10000 5000 3600000)) "the job's end, when it does")
  (is (= 1000 (facts/bot-timeout-ms 100 5000 nil)) "at least a second")
  (is (= 19000 (facts/bot-timeout-ms 20000 1000 nil)) "no cap: the job's end"))

(deftest a-capped-bot-is-filed-and-the-jobs-end-is-cut
  (let [r {:ok false
           :until "pearls-12"
           :reason :timeout
           :plan {:plan/status :running :plan/reached #{:wood}}}
        cap 3600000
        own (facts/marked (facts/capped r 20000000 0 cap))
        job (facts/marked (facts/capped r 3000000 0 cap))]
    (is (= :bot-timeout (:reason own)))
    (is (not (:run/cut? own)) "its own cap is a failure of the stage")
    (is (not (:run/infra? own)) "bb failures files it")
    (is (= :bot-timeout (second (:run/signature own))))
    (is (:run/cut? job) "the job's end before the cap is still a cut")
    (is (= :timeout (:reason job)))
    (is (= :timeout (:reason (facts/capped r 20000000 0 nil))) "no cap, no change")))

(deftest a-slot-backs-off-while-its-bots-never-play
  (let [quick (dec facts/quick-ms)]
    (is (= 1000 (facts/backoff-ms 0 quick {:reason :crashed})) "the server refused: wait a second")
    (is (= 4000 (facts/backoff-ms 2000 quick {:reason :closed})) "still gone: twice as long")
    (is (= facts/backoff-max-ms (facts/backoff-ms 50000 quick {:reason :disconnected}))
        "at most a minute")
    (is (zero? (facts/backoff-ms 8000 facts/quick-ms {:reason :crashed}))
        "a bot that played long enough")
    (is (zero? (facts/backoff-ms 8000 quick {:reason :no-site}))
        "a bot's own stop is play, not a dead server")))

(def refused
  "A bot whose server refused it (race 39817745908's server 2, gone 25 s in): play-one!'s
   crashed RESULT, its plan never begun."
  {:ok false :reason :crashed :error "Connection refused (connect failed)" :until "x" :plan {}})

(deftest a-bot-that-never-got-into-play-is-infrastructure
  (let [m (facts/marked refused)]
    (is (:run/infra? m) "bb failures files it under infrastructure stops, never as an issue")
    (is (= [:wood :crashed]
           (take 2 (:run/signature (facts/marked (assoc refused :until "nether")))))
        "its signature is still kept"))
  (is (:run/infra? (facts/marked {:ok false :reason :closed :until "x"}))
      "connected, but closed before its :go: main leaves :plan out")
  (is (not (:run/infra? (facts/marked (assoc refused :plan {:plan/status :failed}))))
      "a bot that crashed once it played is a failure of its play"))

(deftest a-server-whose-bots-never-play-is-down-until-it-accepts
  (let [played {:ok false :reason :no-site :plan {:plan/status :failed}}
        after (fn [rs] (reduce facts/health facts/recovered rs))]
    (is (= {:race/unplayed 4 :race/down? false} (after (repeat 4 refused))))
    (is (= {:race/unplayed facts/dead-after :race/down? true}
           (after (repeat facts/dead-after refused)))
        "dead-after in a row: down, and its slots start none")
    (is (= {:race/unplayed 0 :race/down? false} (after (concat (repeat 4 refused) [played])))
        "a bot that played resets the count")
    (is (:race/down? (after (concat (repeat facts/dead-after refused) [played])))
        "down stays down until a probe finds it accepting (recovered), whatever ends meanwhile")
    (is (= {:race/unplayed 0 :race/down? false} (after [{:ok true :reason :goal}])))))

(deftest a-server-sees-at-most-one-new-bot-a-start-gap
  (is (= 5000 (facts/next-start 0 5000)) "long since the last start: now")
  (is (= (+ 5000 facts/start-gap-ms) (facts/next-start 5000 5000)) "just started one: a gap on")
  (is (= (+ 5000 (* 15 facts/start-gap-ms))
         (reduce (fn [last _] (facts/next-start last 5000)) 5000 (range 15)))
      "fifteen slots ending at once start one a gap apart"))

(deftest a-pad-is-cleared-of-lava-round-it
  ;; f9c021c race lane 2: 7 of 7 lava deaths 4 to 10 s after the teleport, lava in the pad
  (is (= (str "execute in minecraft:the_nether run fill 1030 51 422 1050 55 442"
              " minecraft:netherrack replace minecraft:lava")
         (land/lava-clear-command [1040 432 52]))))

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
        pads (map #(land/pad-of fortress %) (range 8))]
    (is (= 8 (count (set pads))) "eight bots, eight pads")
    (is (= (first pads) (land/pad-of fortress 8)) "the ninth comes round to the first")
    (is (every? #(= 72 (nth % 2)) pads) "all at the pad height")
    (is (= [152 -160 72] (first pads)) "the first, 48 west of the fortress, as the gym's")
    (is (= [176.5 72.0 -159.5] (land/standing-on [176 -160 72])) "x y z: the middle of the cell")))

(deftest a-spawn-is-pinned-in-the-dimension-the-bot-landed-in
  ;; spawnpoint takes the dimension of its source, the server's overworld: a bot that died at the
  ;; fortress respawned in the overworld
  (is (= "execute in minecraft:the_nether run spawnpoint Clj_r01 152 72 -160"
         (facts/spawn-command "Clj_r01" [152.5 72.0 -159.5] "minecraft:the_nether")))
  (is (= "execute in minecraft:overworld run spawnpoint Clj_r02 10 80 -4"
         (facts/spawn-command "Clj_r02" [10.5 80.0 -3.5] "minecraft:overworld"))))

(deftest a-landing-near-a-located-fortress-is-not-searched-for-again
  ;; race 41218543330: fifteen bots at once asked the server to locate a fortress, a search in one
  ;; tick each; the tick stalled for 31 s and 120 bots timed out
  (is (nil? (land/nearby-fortress [] [10 10])))
  (is (= [200 -160] (land/nearby-fortress [[200 -160] [900 900]] [260 -100])) "within 128 blocks")
  (is (nil? (land/nearby-fortress [[200 -160]] [600 -160])) "400 off: another fortress"))

(deftest a-bot-is-teleported-onto-a-pad-built-before-it-joined
  ;; race 41225389190: the first pad was built 40 s after ten bots joined, generating Nether chunks
  ;; in the server's thread, and seven of them timed out; the pads are built first (prepare!)
  (is (= "execute in minecraft:the_nether run tp Clj_r03 152.5 72 -159.5"
         (land/tp-command "Clj_r03" [152 -160 72]))
      "x.5 y z.5 of the pad cell: -160 and .5 is -159.5, the middle of the cell"))

(deftest a-replacement-bot-lands-on-its-slots-pad
  ;; a bot that ends is replaced by the next serial (r11, r12 ...): lane 3 of race 41218543330 started
  ;; 116 runs in 31 minutes; a landing by serial would generate a new Nether region each time
  (is (= [0 1 9 0 1 9 5] (map #(facts/slot-index % 10) [0 1 9 10 11 19 25])))
  (is (= 7 (facts/slot-index 7 nil)) "no slot count: as before")
  (let [fortress [200 -160]]
    (is (= (land/pad-of fortress (facts/slot-index 3 10))
           (land/pad-of fortress (facts/slot-index 13 10)))
        "the serial after a slot's bot lands on the same pad")))

(defn refills
  "Simulate slots slots refilled in an order a seeded generator picks, each new bot taking the next
   serial: [[slot serial] ...] of the bots alive after n refills, serials from 1."
  [slots n seed]
  (let [rng (java.util.Random. (long seed))]
    (loop [alive (into {} (for [s (range slots)] [s (inc s)]))
           serial slots
           left n]
      (if (zero? left)
        (vec alive)
        (let [s (.nextInt rng (int slots))]
          (recur (assoc alive s (inc serial)) (inc serial) (dec left)))))))

(deftest two-bots-alive-at-once-never-land-on-one-pad
  ;; serials are drawn by whichever slot refills next, so (serial - 1) mod slots is not the slot of
  ;; the bot that ended: two live bots shared a pad, and the second was teleported into the blocks
  ;; the first had placed
  (doseq [seed (range 20)
          :let [alive (refills 10 60 seed)]]
    (is (apply distinct? (map (fn [[slot serial]] (facts/landing-slot slot (dec serial) 10)) alive))
        "by slot: ten live bots on ten pads")
    (is (= (count alive) (count (set (map first alive)))))))

(deftest by-serial-two-live-bots-do-share-a-pad
  ;; the rule before: it collides within a few refills
  (is (some (fn [seed]
              (let [alive (refills 10 60 seed)]
                (not (apply distinct?
                            (map (fn [[_ serial]] (facts/slot-index (dec serial) 10)) alive)))))
            (range 20))))

(deftest the-first-bots-land-on-their-slots-pad
  (is (= [0 1 9] (map #(facts/landing-slot % % 10) [0 1 9])))
  (is (= 3 (facts/landing-slot 3 20 10)) "a refill takes its slot's pad, not its serial's")
  (is (= 0 (facts/landing-slot nil 20 10)) "no slot known: by serial"))

(deftest a-pad-is-pregenerated-within-one-forceload
  ;; a forceload takes at most 256 chunks; pad-view blocks each side of a pad, however the square
  ;; lies on the chunk grid, and past the server's view distance (6 chunks, 96 blocks)
  (let [span (+ 2 (quot (* 2 land/pad-view) 16))]
    (is (<= (* span span) 256) "a square of pad-view each side is at most 256 chunks")
    (is (>= land/pad-view (* 6 16)) "the bot's whole view is generated")))

(deftest a-fortress-run-ends-at-its-first-death-and-is-filed-at-its-stage
  ;; race 41224855928, Clj_r23: seven rods out of 23 deaths in 2.5 minutes, each respawn at the pad
  ;; with keep_inventory: a death used as a plan. In a fortress race the first death ends the run
  (let [failed {:ok false
                :until "pearls-12"
                :reason :timeout
                :plan {:plan/goals #{:blaze-rods :ender-pearls} :plan/reached #{}}}
        died {:stats/deaths 1}]
    (is (= :death (:reason (main/death-ended failed died true))) "died, goal not reached: a death")
    (is (= [:blaze-rods :death] (failure/signature (main/death-ended failed died true)))
        "filed at the stage it was on, blaze-rods not reached")
    (is (= [:ender-pearls :death]
           (failure/signature (-> (main/death-ended failed died true)
                                  (assoc-in [:plan :plan/reached] #{:blaze-rods}))))
        "rods reached before the death: the pearls stage")
    (is (= :timeout (:reason (main/death-ended failed died false))) "a spawn race: as before")
    (is (= :timeout (:reason (main/death-ended failed {} true))) "no death: as it was")
    (is (= :goal (:reason (main/death-ended {:ok true :reason :goal} died true)))
        "the goal reached before the death stays a success")))

(deftest the-death-line-is-the-servers
  (let [f (java.io.File/createTempFile "server" ".log")]
    (try (spit
          f
          (str
           "[17:39:33] [Server thread/INFO]: Clj_r23 was burned to a crisp while fighting Blaze\n"
           "[17:39:44] [Server thread/INFO]: Clj_r24 tried to swim in lava\n"))
         (let [died (fn [log bot] (race/with-death-message {:server-log log} bot {:reason :death}))]
           (is (= "Clj_r24 tried to swim in lava" (:death-message (died (str f) "Clj_r24")))
               "the line for that bot from the server's log")
           (is (= :death-lava (:reason (died (str f) "Clj_r24"))) "its class names the reason")
           (is (= :death-blaze (:reason (died (str f) "Clj_r23")))
               "burned to a crisp while fighting Blaze: the blaze's")
           (is (= :death-other (:reason (died "no/such/server.log" "Clj_r24"))) "no line: other"))
         (is (= {:reason :timeout}
                (race/with-death-message {:server-log (str f)} "Clj_r24" {:reason :timeout}))
             "only a run that ended in a death")
         (finally (.delete f)))))

(deftest a-death-line-has-a-class-and-the-signature-names-it
  (are [line class] (= class (facts/death-class line))
   "Clj_r23 was slain by Blaze" :blaze
   "Clj_r23 was fireballed by Blaze" :blaze
   "Clj_r23 was burned to a crisp while fighting Blaze" :blaze
   "Clj_r01 was slain by Enderman" :enderman
   "Clj_r01 was slain by Zombified Piglin" :piglin
   "Clj_r01 was slain by Piglin Brute" :piglin
   "Clj_r01 was shot by Piglin" :piglin
   "Clj_r01 was fireballed by Ghast" :ghast
   "Clj_r01 tried to swim in lava" :lava
   "Clj_r01 burned to death" :fire
   "Clj_r01 walked into fire" :fire
   "Clj_r01 went up in flames" :fire
   "Clj_r01 hit the ground too hard" :fall
   "Clj_r01 fell from a high place" :fall
   "Clj_r01 suffocated in a wall" :other
   nil :other)
  (is (= [:blaze-rods :death-blaze]
         (failure/signature {:until "pearls-12"
                             :reason :death-blaze
                             :plan {:plan/goals #{:blaze-rods :ender-pearls} :plan/reached #{}}}))
      "a blaze's death and a lava's are two signatures at one stage"))

(deftest a-pad-is-chosen-outside-the-fortress-and-clear-of-mobs
  ;; the first deaths of the bots at the fortress came 14 to 27 s after landing (race 41229255270)
  (let [fortress [200 -160]
        cands (land/pad-candidates fortress 3)]
    (is (= 24 (count (set cands))) "eight compass points at three distances")
    (is (= (land/pad-of fortress 3) (first cands)) "the first is the 48-block pad")
    (is (= [48 80 112]
           (map #(long (math/hypot (double (- (first %) 200)) (double (- (second %) -160))))
                [(first cands) (nth cands 8) (nth cands 16)]))
        "the same points at 48, 80 and 112 blocks (axis points)")
    (is (= (first cands) (land/choose-pad (constantly nil) cands)) "safe: the first")
    (is (= (second cands) (land/choose-pad #(when (= % (first cands)) :bricks) cands))
        "the first in the fortress: the next compass point")
    (is (= (nth cands 8) (land/choose-pad #(when (some #{%} (take 8 cands)) :mobs) cands))
        "all eight unsafe: the same points at 80")
    (is (= (first cands) (land/choose-pad (constantly :bricks) cands))
        "none safe: the nearest, as before")))

(deftest the-probes-for-a-pad-are-within-the-range
  (let [pad [100 -50 72]
        cells (land/brick-probes pad)]
    (is (= 75 (count cells)))
    (is (every? (fn [[x y z]]
                  (and (<= (abs (- x 100)) land/brick-range)
                       (<= (abs (- z -50)) land/brick-range)
                       (<= (abs (- y 72)) 8)))
                cells))
    (is (= "execute in minecraft:the_nether if block 100 72 -50 minecraft:nether_bricks"
           (land/brick-test [100 72 -50] "minecraft:nether_bricks")))
    (is
     (=
      "execute in minecraft:the_nether positioned 100 72 -50 if entity @e[type=minecraft:blaze,distance=..32]"
      (land/mob-test pad "minecraft:blaze")))))

(deftest a-pad-stands-on-ground-with-ground-round-it
  ;; race 9d15bd6, lane 7: two of three bots stepped off a pad built at y 72 into a cavern and fell
  ;; 37 blocks into the lava sea
  (is (= 8 (count land/ground-ring)))
  (is (every? #(= 4 (apply max (map abs %))) land/ground-ring) "four blocks off, on a square")
  (is (not-any? #{[0 0]} land/ground-ring))
  (is (= "execute in minecraft:the_nether if block 3 60 -4 #minecraft:base_stone_nether"
         (land/ground-test [3 60 -4])))
  (is (= "execute in minecraft:the_nether if block 3 61 -4 minecraft:cave_air"
         (land/air-test [3 61 -4] "minecraft:cave_air")))
  (is (< land/ground-bottom 40.01) "over the lava sea (y 31)"))

(deftest racers-are-a-team-with-friendly-fire-off
  ;; race 9d15bd6, lanes 1 to 6: 47 of 344 deaths were a bot killed by a bot, the 44 "slain by Clj_rNN"
  ;; among them; sweeps and retaliation at a shared spawner
  (is (= ["team add racers" "team modify racers friendlyFire false"] race/team-commands))
  (is (= "team join racers Clj_r07" (race/team-join-command "Clj_r07"))))

(deftest each-slot-has-a-fortress-of-its-own-while-the-pool-allows
  ;; race f8b28e3, lane 2: 41 Nether bots in two fortresses, 26 at one: shared blaze aggro, fire and
  ;; spawner refills a solo player never meets
  (let [pool
        (reduce land/pool-add [] [[100 100] [150 120] [800 100] [100 900] [820 130] [-700 -600]])]
    (is (= [[100 100] [800 100] [100 900] [-700 -600]] pool) "fortresses within 160 blocks are one")
    (is (= [[100 100] [800 100] [100 900] [-700 -600] [100 100]]
           (map #(land/fortress-for pool %) (range 5)))
        "slot i has fortress i mod n")
    (is (= 1 (land/per-fortress 10 10)) "ten fortresses, ten slots: one bot each")
    (is (= 2 (land/per-fortress 10 5)))
    (is (= 4 (land/per-fortress 10 3)) "a pool of three: four bots on one")
    (is (= 10 (land/per-fortress 10 0)) "no pool: as before"))
  (let [cs (take 9 (land/search-centres [10 20]))]
    (is (= [10 20] (first cs)) "the landing's own first")
    (is (= 9 (count (set cs))) "then rings of eight points, all different")
    (is (= #{600} (set (map (fn [[x z]] (max (abs (- x 10)) (abs (- z 20)))) (rest cs))))
        "the first ring is land/pool-step off")))

(deftest the-floor-round-a-pad-may-slope
  ;; race f8b28e3, lane 2: 525 candidates answered :no-ground and 15 of 16 pads fell back to y 72
  (is (= 3 land/ground-drop) "a floor three lower is a slope, a deeper drop is a ledge")
  (is (< land/ground-drop 8)))

(deftest the-server-is-asked-what-stood-round-an-ending
  ;; the observability plan, part E: the ground truth beside what the bot saw
  (let [[near self] (facts/near-commands "Clj_r10" [1559 66 64] "minecraft:the_nether")]
    (is (= (str
            "execute in minecraft:the_nether positioned 1559 66 64 as @e[distance=..32,type=!item,"
            "type=!experience_orb,type=!player,sort=nearest,limit=12] run data get entity @s")
           near))
    (is (= "data get entity Clj_r10" self))))

(deftest a-bots-ending-keeps-the-server-log-lines-that-bear-on-it
  (let
    [log
     (str/join
      "\n"
      ["[10:00:01] [Server thread/INFO]: Clj_r10 joined the game"
       "[10:00:02] [Server thread/INFO]: Clj_r1 joined the game"
       "[10:00:03] [Server thread/WARN]: Can't keep up! Is the server overloaded? Running 2301ms behind"
       "[10:00:04] [Server thread/INFO]: Clj_r2 was slain by Blaze"
       "[10:00:05] [Server thread/INFO]: Clj_r1 was slain by Blaze"
       "[10:00:06] [Server thread/INFO]: Clj_r1 lost connection: Disconnected"
       "[10:00:07] [Server thread/INFO]: Starting minecraft server"])]
    (is
     (=
      ["[10:00:02] [Server thread/INFO]: Clj_r1 joined the game"
       "[10:00:03] [Server thread/WARN]: Can't keep up! Is the server overloaded? Running 2301ms behind"
       "[10:00:05] [Server thread/INFO]: Clj_r1 was slain by Blaze"
       "[10:00:06] [Server thread/INFO]: Clj_r1 lost connection: Disconnected"]
      (facts/serverlog-lines log "Clj_r1"))
     "its own lines whole-word, the server's trouble, not Clj_r10's or Clj_r2's")
    (is (= [] (facts/serverlog-lines "" "Clj_r1")))
    (is (= facts/serverlog-tail
           (count (facts/serverlog-lines (str/join "\n"
                                                   (repeat (* 2 facts/serverlog-tail) "Clj_r1 x"))
                                         "Clj_r1")))
        "only the newest lines are read")
    (is (str/starts-with? (race/serverlog-text "no/such/latest.log" "Clj_r1") "no server log at"))
    (is (str/starts-with? (race/serverlog-text nil "Clj_r1") "no server log named"))))

(deftest a-long-log-is-read-by-its-tail
  (let [f (java.io.File/createTempFile "latest" ".log")]
    (try (spit f (str (str/join "\n" (repeat 100 "old line")) "\nClj_r1 was slain by Blaze\n"))
         (let [t (race/file-tail f 40)]
           (is (str/includes? t "Clj_r1 was slain by Blaze"))
           (is (< (count t) 41)))
         (is (str/includes? (race/serverlog-text (str f) "Clj_r1") "was slain by Blaze"))
         (finally (.delete f)))))

(deftest the-forest-is-located-from-the-pad-then-from-farther-off
  (let [origins (land/forest-origins [-100 200 70])]
    (is (= [-100 200] (first origins)) "the pad's own point first")
    (is (= (inc (* 4 (count land/locate-offsets))) (count origins)))
    (is (= [(+ -100 256) 200] (second origins)))
    (is (= (land/warped-origins 800 1600) (land/search-points-from 100 200))
        "the gym's origins are the same function of the Nether point"))
  (testing "the located point is read from the server's reply"
    (is (= [2420 64 2216]
           (land/located-pos
            "The nearest minecraft:warped_forest is at [2420, 64, 2216] (310 blocks away)")))
    (is (nil? (land/located-pos "Could not find a biome of type minecraft:warped_forest")))))
