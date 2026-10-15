(ns clojurecraft.simrace-test
  "The sim race: a run over a world of many columns crosses from one to the next as the game's
   do, a run is the same run every time, and failures group by what they were doing and why."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [clojurecraft.capture :as capture]
            [clojurecraft.failure :as failure]
            [clojurecraft.fixtures :as fx]
            [clojurecraft.game :as game]
            [clojurecraft.plan :as plan]
            [clojurecraft.sim :as sim]
            [clojurecraft.simrace :as simrace]
            [clojurecraft.wood]
            [clojurecraft.world :as world]))

(use-fixtures :once fx/instrumented)

(def oak-log 137)
(def oak-leaves 252)

(def two-columns
  "A world of two columns: bare stone floor where the bot lands (chunk [0 0]), and the only tree
   one column east (chunk [1 0], its trunk at x 20)."
  (simrace/prepared {:capture/set "test"
                     :capture/landing [8 8 64]
                     :capture/radius 1
                     :capture/columns {[0 0] (world/column-bytes {})
                                       [1 0] (world/column-bytes (merge (into {}
                                                                              (for [y (range 64 68)]
                                                                                [[4 y 8] oak-log]))
                                                                        {[4 68 8] oak-leaves}))}}))

(deftest a-run-walks-into-the-next-column-for-its-log
  (let [r (simrace/run two-columns 1 "wood" 120000)]
    (is (:ok r) (pr-str (dissoc r :plan :stats)))
    (is (= 1 (get-in r [:held :oak_log])))
    (is (= 2 (:chunks r)) "the whole world, both columns, sent at login and kept")
    (is (empty? (:run/violations r)))))

(deftest a-run-is-the-same-run-every-time
  (is (= (simrace/run two-columns 7 "wood" 120000) (simrace/run two-columns 7 "wood" 120000))))

(deftest a-failure-is-signed-by-its-target-and-its-reason
  ;; one bug met in two intents (the pickaxe gone while mining or tunnelling) is one signature
  (let [r {:until "nether"
           :reason :no-pickaxe
           :plan {:plan/reached #{:wood :kit :pickaxe :cobblestone :stone-pickaxe :furnace}
                  :plan/last {:intent/kind :mine}}}
        tunnelling (assoc-in r [:plan :plan/last :intent/kind] :tunnel)]
    (is (= :ingots (failure/stage "nether" (get-in r [:plan :plan/reached]))))
    (is (= [:ingots :no-pickaxe] (failure/signature r) (failure/signature tunnelling)))
    (is (= #:simrace{:runs 3 :ok 1 :failures [[[:ingots :no-pickaxe] 2]]}
           (simrace/tally [r tunnelling {:ok true}])))))

(deftest a-failure-the-server-caused-is-marked-not-the-bots
  (is (:run/infra? (failure/mark {:ok false :until "nether" :reason :disconnected})))
  (is (false? (:run/infra? (failure/mark {:ok false :until "nether" :reason :no-path}))))
  (is (= {:ok true} (failure/mark {:ok true})) "a pass is unchanged"))

(deftest a-run-can-be-played-on-from-the-moment-before-it-stopped
  ;; issue #77: run+ keeps the pair from the tick before the stop; playing on from it with the same
  ;; code is the same stop, and run's answer is run+'s :world and :sim
  (let [opts {:host "sim" :port 0 :name "Clj_sim"}
        w (game/init opts)
        s (sim/init {:columns (:capture/columns two-columns)
                     :decoded (:capture/decoded two-columns)
                     :spawn (simrace/spawn-of two-columns)
                     :seed 1
                     :tick-seed 1})
        stop? #(or (plan/done? %) (plan/failed? %))
        go {:event/kind :go :go/goals (plan/through "wood") :go/chain? true}
        {:keys [world before] :as full} (sim/run+ simrace/step w s stop? 120000 go)
        [bw bs] before
        again (sim/play-on simrace/step bw bs stop? 120000 (:time/now bw) [] nil)]
    (is (= [world (:sim full)] (sim/run simrace/step w s stop? 120000 go)) "run is run+'s pair")
    (is (= :done (:plan/status world)))
    (is (= :active (:plan/status bw)) "the moment before: the plan still running")
    (is (= (:plan/status world) (:plan/status (:world again))) "played on, it ends the same")
    (is (= (:player/inventory world) (:player/inventory (:world again))))))

(deftest a-sim-run-knows-its-landing-as-a-real-one-does
  ;; issue #37: race/land-bot! puts :go/at on a real bot's :go; a sim run's plan has the same
  ;; landing, so its landing-relative choices are the real race's
  (let [w (simrace/prepared {:capture/set "test"
                             :capture/landing [8 8 64]
                             :capture/radius 0
                             :capture/columns {[0 0] (world/column-bytes {})}})
        r (simrace/run w 1 "wood" 2000)]
    (is (= (simrace/spawn-of w) (get-in r [:plan :plan/go-at])))))

(deftest a-failed-runs-moment-round-trips-and-plays-on-to-the-same-stop
  ;; issue #77: the snapshot of a failed run is a value that prints, reads back and, played on
  ;; with the same code, fails with the same signature; the moment before the stop still has the
  ;; plan running, so a fix has its say
  (let [w (assoc (simrace/prepared {:capture/set "test"
                                    :capture/landing [8 8 64]
                                    :capture/radius 0
                                    :capture/columns {[0 0] (world/column-bytes {})}}) ; bare stone, no tree
                 :capture/file
                 "bare.edn.gz")
        r (simrace/run w 3 "nether" 300000)
        _ (is (= [:wood :no-log] (failure/signature r)) (pr-str (:reason r)))
        snap (simrace/snapshot w 3 "nether" "abc" (failure/signature r) (:run/before r))
        path (str (System/getProperty "java.io.tmpdir") "/clojurecraft-snapshot-test.edn.gz")
        _ (capture/write-world! path snap)
        back (capture/read-world path)
        played (simrace/play-snapshot back w simrace/snapshot-budget-ms)]
    (is (= (dissoc snap :snapshot/world :snapshot/sim) (dissoc back :snapshot/world :snapshot/sim)))
    (is (= (first (simrace/moment snap w)) (first (simrace/moment back w)))
        "the world reads back whole, facts too")
    (is (= :active (get-in snap [:snapshot/world :plan/status])) "the moment before the stop")
    (is (simrace/recurs? back played) (pr-str (:run/signature played)))
    (is (<= (:run/ms played) (+ (get-in snap [:snapshot/world :time/now]) 1000))
        "it fails again at once, not after the budget")))

(deftest a-timeout-is-judged-by-its-whole-run
  ;; a :timeout snapshot's moment is the tick before the limit, long after what slowed it: the
  ;; issue check replays its run from the landing instead of playing on (simrace/judged)
  (is (simrace/timeout-signature? {:snapshot/signature [:ingots :timeout]}))
  (is (not (simrace/timeout-signature? {:snapshot/signature [:ingots :boxed]})))
  (is (not (simrace/timeout-signature? {}))))

(deftest a-timeout-replay-stops-at-its-stage
  ;; a :timeout snapshot is replayed only until the run gets past the stage it timed out on
  ;; (simrace/replay): PR #173's issue check replayed whole 90-minute runs and ran out of time
  (is (= "ingots" (simrace/stage-until :ingots)))
  (is (= "table" (simrace/stage-until :kit)) "a target's --until name, not its id")
  (is (nil? (simrace/stage-until :no-such-stage))))
