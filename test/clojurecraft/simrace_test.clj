(ns clojurecraft.simrace-test
  "The sim race: a run over a world of many columns crosses from one to the next as the game's
   do, a run is the same run every time, and failures group by what they were doing and why."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojurecraft.capture :as capture]
            [clojurecraft.chunk :as chunk]
            [clojurecraft.dimension :as dimension]
            [clojurecraft.failure :as failure]
            [clojurecraft.fixtures :as fx]
            [clojurecraft.game :as game]
            [clojurecraft.memory :as memory]
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

(def bare
  "One column of bare stone with no tree: a run for wood waits :no-log and fails."
  (assoc (simrace/prepared {:capture/set "test"
                            :capture/landing [8 8 64]
                            :capture/radius 0
                            :capture/columns {[0 0] (world/column-bytes {})}})
         :capture/file
         "bare.edn.gz"))

(defn kept-snapshot
  "The snapshot the sim race keeps of run r of world w (simrace/play!'s choice)."
  [w r]
  (let [sig (failure/signature r)]
    (simrace/snapshot w
                      3
                      "nether"
                      "abc"
                      sig
                      (simrace/chosen-moment (:run/moments r)
                                             (:run/last r)
                                             (simrace/decision (:run/end r) sig)
                                             (:run/ms r)))))

(deftest a-moment-plays-on-exactly-as-the-run-did
  ;; a kept moment carries what the next tick depends on (pending packets, the tick seed, go
  ;; sent, packets in flight), so playing it on is the run itself, not a run like it; a moment
  ;; every simulated second here (the races keep one every half minute)
  (let [opts {:host "sim" :port 0 :name "Clj_sim"}
        s (sim/init {:columns (:capture/columns two-columns)
                     :decoded (:capture/decoded two-columns)
                     :spawn (simrace/spawn-of two-columns)
                     :seed 1
                     :tick-seed 1})
        stop? #(or (plan/done? %) (plan/failed? %))
        full (sim/play-from simrace/step
                            (sim/start-moment simrace/step (game/init opts) s)
                            stop?
                            120000
                            (simrace/go-of two-columns "wood")
                            1000)
        moments (:moments full)
        mid (nth moments (quot (count moments) 2))
        again (sim/play-from simrace/step mid stop? 120000 nil nil)]
    (is (< 2 (count moments)) "moments kept along the run")
    (is (< 0 (:t mid) (:time/now (:world full))) "a moment from the middle of the run")
    (is (= :done (:plan/status (:world full))))
    (is (= (dissoc (:world full) :world/facts) (dissoc (:world again) :world/facts))
        "played on from the middle, the same world at the same tick")
    (is (= (memory/intentions (:world full)) (memory/intentions (:world again))))
    (is (= (:sim full) (:sim again)) "and the same model")))

(deftest the-moment-is-kept-from-before-the-decision
  ;; a failure is kept from at least decision-lead-ms before the choice that failed, not from
  ;; the tick before the stop: judging needs only the window from there
  (let [moments (mapv (fn [t] {:t t}) [0 30000 60000 90000 120000])
        stopping {:t 150000}]
    (is (= 60000
           (:t (simrace/chosen-moment moments stopping {:why :intent-failed :t 100000} 150000))))
    (is (= {:why :intent-failed :t 60000 :decision-t 100000 :stop-t 150000 :window-ms 150000}
           (:moment
            (simrace/chosen-moment moments stopping {:why :intent-failed :t 100000} 150000)))
        "judged from the moment to a minute past the stop")
    (is (= 0 (:t (simrace/chosen-moment moments stopping {:why :wait :t 10000} 150000)))
        "a decision older than every moment: the oldest kept")
    (is (= simrace/stall-judge-ms
           (get-in (simrace/chosen-moment moments stopping {:why :stall :t 0} 150000)
                   [:moment :window-ms]))
        "a stall is judged over fifteen minutes (issue #62: a stage can take longer than five)")
    (is (= simrace/stall-judge-ms
           (simrace/window-of {:snapshot/moment {:why :stall :window-ms 300000}}))
        "and a stall kept with five minutes is judged over fifteen")
    (is (= 120000 (simrace/window-of {:snapshot/moment {:why :intent-failed :window-ms 120000}}))
        "any other moment over its own window"))
  (let [facts (memory/empty-facts)
        w {:time/now 500000 :world/facts facts}]
    (is (= {:why :stall :t 200000} (simrace/decision w [:ingots :timeout])))
    (is (= {:why :wait :t 480000}
           (simrace/decision
            (assoc w :plan/waiting-since 480000 :plan/waiting :no-log :plan/reason :no-log)
            [:wood :no-log])))
    (let [failed (reduce (fn [w [t id event]]
                           (memory/remember-intention
                            (assoc w :time/now t)
                            {:intent/id id :intent/kind :dig :intent/reason :rejected}
                            event))
                         w
                         [[100 1 :started] [200 1 :done] [300 2 :started] [400 2 :failed]
                          [500 3 :started] [600 3 :failed] [700 4 :started] [800 4 :failed]])]
      (is (= {:why :intent-failed :t 300}
             (simrace/decision (assoc failed :time/now 800 :plan/reason :rejected)
                               [:cobblestone :rejected]))
          "the first of the failures in a row started at 300"))))

(deftest a-failed-runs-moment-round-trips-and-is-judged-in-its-window
  ;; issue #77: the snapshot of a failed run is a value that prints, reads back and, judged with
  ;; the same code, fails with the same signature, inside its window and never from the landing
  (let [r (simrace/run bare 3 "nether" 300000)
        _ (is (= [:wood :no-log] (failure/signature r)) (pr-str (:reason r)))
        snap (kept-snapshot bare r)
        path (str (System/getProperty "java.io.tmpdir") "/clojurecraft-snapshot-test.edn.gz")
        _ (capture/write-world! path snap)
        back (capture/read-world path)
        played (simrace/judged back bare)]
    (is (= (dissoc snap :snapshot/world :snapshot/sim) (dissoc back :snapshot/world :snapshot/sim)))
    (is (= (first (simrace/moment snap bare)) (first (simrace/moment back bare)))
        "the world reads back whole, facts too")
    (is (= :wait (get-in snap [:snapshot/moment :why])) "a wait for a log that never comes")
    (is (<= (get-in snap [:snapshot/moment :t]) (get-in snap [:snapshot/moment :decision-t])))
    (is (simrace/recurs? back played) (pr-str (:run/signature played)))
    (is (<= (- (:run/ms played) (:run/from played)) (simrace/window-of back)) "inside its window")
    (is (vector? (:run/story played))
        "what the bot did over the window (nothing: no log to go for)")))

(deftest a-stall-recurs-until-its-stage-is-reached
  ;; a :timeout is judged as a stall over its window: it recurs when the stage is still not
  ;; reached and the plan not done, never by replaying the run from its landing
  (let [snap {:snapshot/signature [:ingots :timeout]}]
    (is (simrace/timeout-signature? snap))
    (is (simrace/recurs? snap {:ok false :plan {:plan/status :active :plan/reached #{:wood :kit}}}))
    (is (not (simrace/recurs? snap
                              {:ok false
                               :plan {:plan/status :active :plan/reached #{:wood :ingots}}}))
        "the stage reached in the window: past the stall")
    (is (not (simrace/recurs? snap {:ok true :plan {:plan/status :done}})))
    (is (not (simrace/timeout-signature? {:snapshot/signature [:ingots :boxed]})))))

(deftest a-stage-names-its-until
  ;; simrace/replay (a probe's whole road to a failure) runs to the stage the signature names
  (is (= "ingots" (simrace/stage-until :ingots)))
  (is (= "table" (simrace/stage-until :kit)) "a target's --until name, not its id")
  (is (nil? (simrace/stage-until :no-such-stage))))

(deftest a-crash-is-kept-as-a-moment-with-its-stage
  ;; issue #152: a tick that threw used to leave no moment and file the run under :wood, the
  ;; first stage of a RESULT with no plan; now the play ends there, :crashed, with the moment
  ;; before it, and the crash is judged like any other failure
  (let [opts {:host "sim" :port 0 :name "Clj_sim"}
        s (sim/init {:columns (:capture/columns two-columns)
                     :decoded (:capture/decoded two-columns)
                     :spawn (simrace/spawn-of two-columns)
                     :seed 1
                     :tick-seed 1})
        boom (fn [w e]
               (if (and (= :tick (:event/kind e)) (>= (:event/now e) 3000))
                 (throw (ex-info "boom" {}))
                 (simrace/step w e)))
        r (sim/play-from boom
                         (sim/start-moment simrace/step (game/init opts) s)
                         #(or (plan/done? %) (plan/failed? %))
                         120000
                         (simrace/go-of two-columns "wood")
                         1000)]
    (is (= "boom" (ex-message (:crashed r))))
    (is (= 3000 (:t (:last r))) "the moment of the tick that threw")
    (is (seq (:moments r)) "the moments before it, to keep one from")
    (is (= {:why :crash :t 5000}
           (simrace/decision {:time/now 5000 :world/facts (memory/empty-facts)}
                             [:portal :crashed])))))

(deftest a-judge-out-of-wall-time-counts-as-recurring
  ;; a judge has a wall-clock budget (judge-wall-ms) so a slow base cannot outlast issue.yml's
  ;; job (PR #197's base planner took 862 s on one moment): a play it halts did not get past
  ;; the moment, so it recurs
  (let [r (simrace/run bare 3 "nether" 300000)
        snap (kept-snapshot bare r)
        played (simrace/judged snap bare (constantly true))]
    (is (:run/halted? played))
    (is (simrace/recurs? snap played))
    (is (not (:run/halted? (simrace/judged snap bare))) "without a budget, never halted")))

(deftest a-sets-nether-is-found-beside-its-worlds
  ;; issue #353: capture-nether writes one file a set, beside the set's captured worlds
  (is (= "cache/sim-nether/R1.edn.gz" (simrace/nether-file "cache/sim-worlds/R1/7.edn.gz"))))

(deftest a-moment-past-the-portal-holds-the-captured-nether
  ;; issue #353: a snapshot copies neither the world's columns nor the Nether's (it names their
  ;; files); played on, a moment in the Nether gets the Nether's columns back, the bot's chunks
  ;; among them, and one in the overworld the overworld's, its portal still leading there
  (let [columns {[0 0] (world/column-bytes {} dimension/nether)}
        nether {:columns columns :decoded (update-vals columns chunk/decode) :view-distance 6}
        w (simrace/with-nether bare nether "cache/sim-nether/test.edn.gz")
        sim {:sim/phase :play
             :sim/now 0
             :sim/out []
             :sim/dimension dimension/nether
             :sim/nether nether
             :sim/columns columns
             :sim/decoded (:decoded nether)}
        world {:time/now 0
               :world/chunks (:decoded nether)
               :world/facts (memory/empty-facts)
               :plan/go-at (simrace/spawn-of bare)}
        snap
        (simrace/snapshot w 1 "fortress" "abc" [:fortress :blocked] {:world world :sim sim :t 0})
        played (simrace/moment-of snap w)]
    (is (= "cache/sim-nether/test.edn.gz" (:snapshot/nether-file snap)))
    (is (= {:view-distance 6} (get-in snap [:snapshot/sim :sim/nether])) "no columns copied")
    (is (= columns (get-in played [:sim :sim/columns])) "the Nether's columns")
    (is (identical? (get-in nether [:decoded [0 0]]) (get-in played [:world :world/chunks [0 0]]))
        "the bot's chunks the Nether's, decoded once")
    (is (= nether (get-in played [:sim :sim/nether])))
    (testing "a moment before the portal holds the overworld's"
      (let [before (simrace/moment-of
                    (assoc-in snap [:snapshot/sim :sim/dimension] dimension/overworld)
                    w)]
        (is (= (:capture/columns bare) (get-in before [:sim :sim/columns])))
        (is (identical? (get-in bare [:capture/decoded [0 0]])
                        (get-in before [:world :world/chunks [0 0]])))
        (is (= nether (get-in before [:sim :sim/nether])) "its portal leading to the Nether")))))
