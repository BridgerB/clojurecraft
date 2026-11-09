(ns clojurecraft.simrace-test
  "The sim race: a run over a world of many columns crosses from one to the next as the game's
   do, a run is the same run every time, and failures group by what they were doing and why."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [clojurecraft.blocks :as blocks]
            [clojurecraft.capture :as capture]
            [clojurecraft.chunk :as chunk]
            [clojurecraft.dimension :as dimension]
            [clojurecraft.failure :as failure]
            [clojurecraft.fixtures :as fx]
            [clojurecraft.game :as game]
            [clojurecraft.memory :as memory]
            [clojurecraft.mob :as mob]
            [clojurecraft.plan :as plan]
            [clojurecraft.recipe :as recipe]
            [clojurecraft.sim :as sim]
            [clojurecraft.simrace :as simrace]
            [clojurecraft.start :as start]
            [clojurecraft.wood]
            [clojurecraft.world :as world]
            [datascript.core :as d]))

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

(deftest a-goal-newly-reached-is-traced
  ;; the cost of the gear step is a number: the time :gear and :portal-kit were reached, and the
  ;; Nether entered (:dimension), read off the TRACE lines
  (let [base {:world/entities {} :player/pos [0.5 64.0 0.5] :player/inventory {}}
        before (assoc base :time/now 1000 :plan/reached #{:portal-kit})
        after (assoc base :time/now 1050 :plan/reached #{:portal-kit :gear})
        note (simrace/trace-note before after "R1" 1)]
    (is (= :reached (:what note)))
    (is (= [:gear] (:reached note)))
    (is (= 1050 (:t note)))
    (is (nil? (simrace/trace-note after (assoc after :time/now 1100) "R1" 1)))))

(deftest a-run-walks-into-the-next-column-for-its-log
  (let [r (simrace/run two-columns 1 "wood" 120000)]
    (is (:ok r) (pr-str (dissoc r :plan :stats)))
    (is (= 1 (get-in r [:held :oak_log])))
    (is (= 2 (:chunks r)) "the whole world, both columns, sent at login and kept")
    (is (empty? (:run/violations r)))))

(deftest a-run-is-the-same-run-every-time
  ;; every part of a run is the same run again but the senses' tick-ms: the wall time a reducer
  ;; step took, a measurement of the machine (senses/of), not of the run
  (let [unclocked (fn [r] (update r :run/senses (fn [ls] (mapv #(dissoc % :tick-ms) ls))))
        a (simrace/run two-columns 7 "wood" 120000)
        b (simrace/run two-columns 7 "wood" 120000)]
    (is (seq (:run/senses a)) "a line a second of model time")
    (is (= (unclocked a) (unclocked b)))))

(deftest a-failure-is-signed-by-its-target-and-its-reason
  ;; one bug met in two intents (the pickaxe gone while mining or tunnelling) is one signature
  (let [r {:until "nether"
           :reason :no-pickaxe
           :plan {:plan/reached #{:wood :kit :pickaxe :cobblestone :stone-pickaxe :furnace}
                  :plan/last {:intent/kind :mine}}}
        tunnelling (assoc-in r [:plan :plan/last :intent/kind] :tunnel)]
    (is (= :ingots (failure/stage "nether" (get-in r [:plan :plan/reached]))))
    (is (= [:ingots :no-pickaxe] (failure/signature r) (failure/signature tunnelling)))
    (is (= #:simrace{:runs 3 :ok 1 :respawns 0 :eaten 0 :failures [[[:ingots :no-pickaxe] 2]]}
           (simrace/tally [r tunnelling {:ok true}])))
    (is (= #:simrace{:respawns 3 :eaten 2}
           (select-keys (simrace/tally [{:ok true :run/respawns 1 :run/eaten 2}
                                        (assoc r :run/respawns 2)])
                        [:simrace/respawns :simrace/eaten]))
        "the lanes show deaths and eating as totals too (issue #260)")))

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

(deftest a-run-may-start-hungry
  ;; issue #260: a lane from a full bar seldom reaches the hunger rows (21 runs of up to 330
  ;; simulated minutes on 8f50709a ended at food 19 or 20 but one), so --food starts the bar low
  ;; and the model tells the client so at login
  (let [hungry (simrace/run two-columns 1 "wood" 2000 4)
        fed (simrace/run two-columns 1 "wood" 2000)]
    (is (= 4 (get-in hungry [:run/end :player/food])))
    (is (= 20 (get-in fed [:run/end :player/food])))
    (is (= 0 (:run/eaten hungry)) "hungry with nothing held, nothing is eaten")))

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
        path (fx/temp-path "clojurecraft-snapshot-test" ".edn.gz")
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

(deftest a-snapshot-from-a-nether-death-keeps-the-overworld-aside-without-its-columns
  ;; issue #504: the Nether's columns stood over the overworld's after a respawn; a moment in the
  ;; Nether keeps the overworld set aside (its placed blocks) and gets its columns back from w
  (let [columns {[0 0] (world/column-bytes {} dimension/nether)}
        nether {:columns columns :decoded (update-vals columns chunk/decode)}
        w (simrace/with-nether bare nether "cache/sim-nether/test.edn.gz")
        placed {[3 70 3] 1}
        sim {:sim/phase :play
             :sim/now 0
             :sim/out []
             :sim/dimension dimension/nether
             :sim/nether nether
             :sim/columns columns
             :sim/decoded (:decoded nether)
             :sim/overworld {:sim/columns (:capture/columns bare)
                             :sim/decoded (:capture/decoded bare)
                             :sim/placed placed}}
        world {:time/now 0
               :world/chunks (:decoded nether)
               :world/facts (memory/empty-facts)
               :plan/go-at (simrace/spawn-of bare)}
        snap
        (simrace/snapshot w 1 "fortress" "abc" [:fortress :blocked] {:world world :sim sim :t 0})
        played (simrace/moment-of snap w)]
    (is (= {:sim/placed placed} (get-in snap [:snapshot/sim :sim/overworld])) "no columns copied")
    (is (= (:capture/columns bare) (get-in played [:sim :sim/overworld :sim/columns])))
    (is (= placed (get-in played [:sim :sim/overworld :sim/placed])))))

(deftest a-snapshot-after-an-old-nether-respawn-streams-the-overworld-again
  ;; issue #504: respawned over the Nether's columns the bot held none and its view stayed put
  (let [sim {:sim/dimension dimension/overworld
             :sim/view #{}
             :sim/view-at [237 123]
             :sim/player-pos [8.5 64.0 8.5]
             :sim/out []}
        w {:time/now 0
           :stats/deaths 1
           :world/chunks {}
           :world/facts (d/serializable (memory/empty-facts))}
        snap {:snapshot/chunks #{} :snapshot/world w :snapshot/sim sim}
        played (simrace/moment-of snap bare)]
    (is (simrace/stranded? #{} w sim))
    (is (not (simrace/stranded? #{[0 0]} w sim)) "a bot holding chunks is not stranded")
    (is (not (simrace/stranded? #{} w (assoc sim :sim/dimension dimension/nether))))
    (is (not (simrace/stranded? #{} (dissoc w :stats/deaths) sim)) "one that never died is not")
    (is (some #(= :level-chunk-with-light (:packet/name %)) (:sim/out (:sim played)))
        "so the model sends its columns again")
    (is (= (:capture/columns bare) (get-in played [:sim :sim/columns])))))

;; ---------------------------------------------------------------- a start at a fortress

(def spawner-column
  "The Nether chunk [0 0] with a spawner block floating at [8 70 8], over the bare stone floor
   (y 63 and under) every other column of the test has."
  (world/column-bytes {[8 70 8] mob/spawner-block} dimension/nether))

(def nether-decoded
  "Twenty-five Nether chunks round [0 0], decoded, the middle one holding the spawner."
  (into {}
        (for [cx (range -2 3)
              cz (range -2 3)]
          [[cx cz]
           (chunk/decode
            (if (= [0 0] [cx cz]) spawner-column (world/column-bytes {} dimension/nether)))])))

(deftest a-kit-becomes-an-inventory
  (let [inv (simrace/kit->inventory {:iron_pickaxe 1 :cobblestone 130 :iron_helmet 1})]
    (is (= [64 64 2] (map #(:count (get inv [:hotbar %])) [0 1 2])) "a count over a stack is split")
    (is (= (recipe/item-id :iron_helmet) (:item (get inv [:hotbar 3])))
        "armour is carried, not worn")
    (is (= 36 (count (simrace/kit->inventory (start/kit-map (start/start-of :fortress)))))
        "every slot full, as the real bots arrive")
    (is (= inv (simrace/kit->inventory {:iron_helmet 1 :iron_pickaxe 1 :cobblestone 130}))
        "the same kit, the same inventory")
    (is (every? (comp pos? :count)
                (vals (simrace/kit->inventory (start/kit-map (start/start-of :fortress))))))))

(deftest a-run-may-start-at-the-fortress-spawner-nearest-its-landing
  ;; the model's fortress spawners (issue #495) are the cells holding a spawner block; the start is
  ;; ground on the far side of them, thirty-two blocks off when there is some
  (let [start (simrace/fortress-start nether-decoded [64 64 70])]
    (is (= [8 70 8] (:spawner start)))
    (is (= [-23.5 64.0 -23.5] (:spawn start)) "the first ground at the farthest ring")
    (is (nil? (simrace/fortress-start {[0 0] (chunk/decode (world/column-bytes {}
                                                                               dimension/nether))}
                                      [64 64 70]))
        "no spawner: no start")))

(deftest a-fortress-start-plays-from-the-blaze-rods
  ;; failures before the rods are not the run's to file: its goals are the stages from them up
  (is (= [:blaze-rods :ender-pearls] (start/goals-from :blaze-rods "pearls-12")))
  (let [w (simrace/with-fortress
           (assoc two-columns :capture/nether {:decoded nether-decoded :columns {}})
           {:iron_sword 1})
        go (simrace/go-of w "pearls-12")]
    (is (= [-23.5 64.0 -23.5] (simrace/spawn-of w)))
    (is (= [:blaze-rods :ender-pearls] (:go/goals go)))
    (is (= [-23.5 64.0 -23.5] (:go/at go)))
    (is (= (plan/through "pearls-12")
           (:go/goals (simrace/go-of (dissoc w :capture/fortress) "pearls-12")))
        "no fortress: the whole chain, as before")))

(deftest a-rods-start-plays-only-the-pearls
  ;; LOOP.md section 1: stage 18's checkpoint in the sim, seven rods in hand at the fortress start;
  ;; no sim run had reached a warped forest from the blaze rods (0 of 52)
  (is (= [:ender-pearls] (start/goals-from :ender-pearls "pearls-12")))
  (is (= {:blaze_rod 7} (select-keys (start/kit-map (start/start-of :rods)) [:blaze_rod])))
  (let [w (-> (simrace/with-fortress
               (assoc two-columns :capture/nether {:decoded nether-decoded :columns {}})
               (merge-with + {:iron_sword 1} {:blaze_rod 7}))
              (assoc-in [:capture/fortress :checkpoint/goal] :ender-pearls))]
    (is (= [:ender-pearls] (:go/goals (simrace/go-of w "pearls-12"))) "the pearls alone")
    (is (some #(= (recipe/item-id :blaze_rod) (:item %))
              (vals (get-in w [:capture/fortress :inventory])))
        "the rods are in the bag")))

(deftest a-fortress-run-ends-at-its-first-death-filed-by-its-class
  ;; sim race lanes of 9d15bd6: the model respawned the bot at the start for ever (861 and 1722
  ;; deaths in a run), so nothing was measured; the real race ends such a run at the first death
  (let [died {:stats/deaths 1 :plan/status :running}
        r {:ok false :until "blaze-rods" :reason :timeout}]
    (is (simrace/stopped? died true) "the first death stops a fortress run")
    (is (not (simrace/stopped? died false)) "a run from spawn plays on after a death")
    (is (not (simrace/stopped? {:plan/status :running} true)))
    (is (= :death-blaze (:reason (simrace/death-ended r died true :blaze))))
    (is (= :death-piglin (:reason (simrace/death-ended r died true :zombified_piglin))))
    (is (= :death-other (:reason (simrace/death-ended r died true :starve))) "no class for it")
    (is (= r (simrace/death-ended r {:stats/deaths 0} true :blaze)) "no death, no change")
    (is (= r (simrace/death-ended r died false :blaze)) "from spawn, no change")
    (is (= {:ok true :reason :goal} (simrace/death-ended {:ok true :reason :goal} died true :blaze))
        "a goal reached before the death is a success")))

(deftest the-worlds-of-a-set-start-at-different-fortress-places
  ;; sim race 41256365892: five worlds of R1 died at two places, one Nether and the nearest spawner
  ;; to each landing being the same few; a salt picks another spawner and turns the ring
  (let [starts (map #(simrace/fortress-start nether-decoded [64 64 70] %) (range 8))]
    (is (= [-23.5 64.0 -23.5] (:spawn (first starts))) "salt 0 is the nearest spawner's, as before")
    (is (every? some? starts))
    (is (< 1 (count (distinct (map :spawn starts)))) "the ring's turn alone gives another place")
    (is (= (nth starts 3) (simrace/fortress-start nether-decoded [64 64 70] 3))
        "the same salt, the same start")
    (is (= 0 (simrace/world-number {})) "no file, no number")
    (is (= 41 (simrace/world-number {:capture/file "cache/sim-worlds/R1/41.edn.gz"})))))

(deftest a-run-ends-with-the-models-truth-round-the-player
  ;; the observability plan, part E: what the model knew that the bot might not have seen
  (let [t (simrace/truth {:sim/player-pos [0.0 64.0 0.0]
                          :sim/health 18.0
                          :sim/mobs {5 {:type :blaze :pos [3.0 64.0 0.0] :health 20.0 :angry? true}
                                     6 {:type :ghast :pos [100.0 64.0 0.0] :health 10.0}}
                          :sim/death-causes [:blaze]})]
    (is (= [5] (mapv :eid (:mobs t))) "the far ghast is left out")
    (is (= 3.0 (:d (first (:mobs t)))))
    (is (:angry? (first (:mobs t))) "every fact the model keeps")
    (is (= 18.0 (get-in t [:player :health])))
    (is (= [:blaze] (:death-causes t)))))

(deftest the-truth-says-who-targets-the-player-and-what-each-dealt
  ;; truth always: per mob, whether it has the player as its target and what it dealt this life,
  ;; and the model's own cause for each blow
  (let [hit-1 {:t 1000 :life 0 :cause :blaze :by 5 :dealt 5.0 :health 15.0}
        hit-2 {:t 2000 :life 0 :cause :lava :dealt 4.0 :health 11.0}
        hit-3 {:t 3000 :life 1 :cause :blaze :by 5 :dealt 2.0 :health 18.0}
        s {:sim/now 4000
           :sim/player-pos [0.0 64.0 0.0]
           :sim/health 18.0
           :sim/respawns 1
           :sim/hit-log [hit-1 hit-2 hit-3]
           :sim/mobs {5 {:type :blaze :pos [3.0 64.0 0.0] :health 20.0}
                      6 {:type :enderman :pos [4.0 64.0 0.0] :health 40.0}}}
        t (simrace/truth s)
        by-eid (into {} (map (juxt :eid identity)) (:mobs t))]
    (is (= 4000 (:t t)))
    (is (true? (:targets? (by-eid 5))) "a blaze in its follow range targets the player")
    (is (false? (:targets? (by-eid 6))) "a neutral enderman not yet angered does not")
    (is (= 2.0 (:dealt (by-eid 5))) "this life's damage only: the blow of life 0 is not counted")
    (is (= 0.0 (:dealt (by-eid 6))))
    (is (= [hit-1 hit-2 hit-3] (:hits t)) "the model's own cause for each blow")
    (is (= {5 2.0} (simrace/dealt-by s)))))

(deftest the-truth-ring-is-bounded-and-sampled-every-few-seconds
  (let [ring (reduce (fn [r i] (simrace/truth-ring-add r {:t i :hits (vec (range 20))}))
                     []
                     (range (+ simrace/truth-ring-keep 5)))]
    (is (= simrace/truth-ring-keep (count ring)))
    (is (= 5 (:t (first ring))) "the newest are kept")
    (is (= 8 (count (:hits (first ring)))) "a sample keeps only the newest hits"))
  (is (simrace/truth-due? nil 0) "the first sample is due at once")
  (is (not (simrace/truth-due? 1000 (+ 1000 (dec simrace/truth-every-ms)))))
  (is (simrace/truth-due? 1000 (+ 1000 simrace/truth-every-ms))))

(deftest every-ended-run-writes-its-truth-even-one-that-threw
  ;; 47 of 136 sim runs had a truth file: it was written only beside a moment, which a run that
  ;; threw, or that had no position, never had
  (let [dir (.toFile (java.nio.file.Files/createTempDirectory
                      "truth"
                      (make-array java.nio.file.attribute.FileAttribute 0)))]
    (try (let [threw {:ok false :reason :crashed :error "boom"}
               ended
               {:ok true :run/truth {:t 1 :player {} :mobs [] :hits []} :run/truth-ring [{:t 1}]}]
           (is (= {:unavailable "boom" :reason :crashed}
                  (read-string (get (simrace/truth-texts threw) ".truth.edn")))
               "no model at the end: it says so, rather than no file")
           (is (= "" (get (simrace/truth-texts threw) ".truth-ring.edn")))
           (is (= (:run/truth ended) (read-string (get (simrace/truth-texts ended) ".truth.edn"))))
           (let [path (simrace/write-truth! (io/file dir "senses") "T-1-9" threw)]
             (is (.exists (io/file path)))
             (is (.exists (io/file dir "senses" "T-1-9.truth-ring.edn"))))
           (is (= "{:t 1}" (str/trim (get (simrace/truth-texts ended) ".truth-ring.edn")))))
         (finally (run! #(.delete ^java.io.File %) (reverse (file-seq dir)))))))
(deftest a-checkpoint-run-has-a-cap-of-its-own
  (let [rods (start/start-of :rods)
        horizon (* 330 60000)]
    (is (= [3600000 true] (simrace/cap-max-ms rods horizon)))
    (is (= [horizon false] (simrace/cap-max-ms (start/start-of :fortress) horizon))
        "a start with no cap keeps the horizon")
    (is (= [horizon false] (simrace/cap-max-ms nil horizon)) "no start, no cap")
    (is (= [60000 false] (simrace/cap-max-ms rods 60000)) "never beyond the horizon")
    (is (= :bot-timeout (:reason (simrace/capped {:ok false :reason :timeout} true))))
    (is (= :timeout (:reason (simrace/capped {:ok false :reason :timeout} false))))
    (is (= :crashed (:reason (simrace/capped {:ok false :reason :crashed} true))))
    (let [r (failure/mark
             {:ok false :until "pearls-12" :reason :bot-timeout :plan {:plan/reached #{}}})]
      (is (not (:run/infra? r)) "filed like any failure")
      (is (simrace/timeout-signature? {:snapshot/signature (:run/signature r)})
          "a capped run is a stall, its moment kept"))))

(deftest a-forest-start-stands-on-the-nearest-warped-nylium
  ;; stage 18 split as stage 17 was: the hunt measured alone, landed in the set's warped forest
  (let [nylium (blocks/default-state :warped_nylium)
        with-forest (assoc nether-decoded
                           [1 1]
                           (chunk/decode (world/column-bytes {[4 63 4] nylium} dimension/nether)))
        start (simrace/fortress-start with-forest [64 64 70])]
    (is (= {:spawn [20.5 64.0 20.5]} (simrace/forest-start with-forest (mapv long (:spawn start)))))
    (is (nil? (simrace/forest-start nether-decoded (mapv long (:spawn start))))
        "no nylium: no forest start")
    (let [w (simrace/with-fortress
             (assoc two-columns :capture/nether {:decoded with-forest :columns {}})
             {:iron_sword 1}
             :forest)]
      (is (= [20.5 64.0 20.5] (simrace/spawn-of w)))
      (is (= [8 70 8] (get-in w [:capture/fortress :spawner])) "the fortress is still known"))
    (is (nil? (:capture/fortress
               (simrace/with-fortress
                (assoc two-columns :capture/nether {:decoded nether-decoded :columns {}})
                {:iron_sword 1}
                :forest)))
        "a Nether with no forest: the world is out of a forest race")))

(deftest an-overworld-start-plays-the-chain-from-its-goal
  ;; the :gear start (starts.edn): its run goes on from the gear step, not from the wood
  (is (= :gear
         (first (:go/goals (simrace/go-of (assoc two-columns :capture/goal :gear) "nether")))))
  (is (= (plan/through "nether") (:go/goals (simrace/go-of two-columns "nether")))
      "no goal named: the whole chain"))
