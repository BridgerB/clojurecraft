(ns clojurecraft.churn-guard
  "The invariant that catches CHURN once, for every intent, instead of one call site at a time
   (issue #615): in any state of the bot, the intent the shipped goal table chooses is not
   already over after one tick of running. The planner's choice and an intent's own end check
   read the same world; when they disagree (a perch where the feet already are, a walk to a cell
   within reach, a back-off from a dragon already far enough), the intent starts, ends in its
   first tick, and the planner picks the identical one again every 50 ms while the bot stands
   still and dies.

     clojure -M -m clojurecraft.churn-guard snap1.edn.gz [snap2.edn.gz ...]

   Each recorded snapshot (test/issues/<n>/*.edn.gz, the corpus the judge replays) is played on
   from its moment and folded to a few points: the moment and every sample-every-ms after it,
   up to the window (simrace/window-of) or max-ms. At each point `violation` is asked of the
   bot's world, and one CHURN line is printed for each state that breaks the invariant, then a
   CHURN-SUMMARY line. Snapshots need their captured worlds (probe.yml -f worlds=R1,R2,R3,R4)."
  (:require [clojurecraft.capture :as capture]
            [clojurecraft.intent :as intent]
            [clojurecraft.physics :as physics]
            [clojurecraft.plan :as plan]
            [clojurecraft.sim :as sim]
            [clojurecraft.simrace :as simrace]))


(set! *warn-on-reflection* true)

(def instantaneous-kinds
  "The intent kinds that may end in the first tick they run, because they are one action that
   is over when it is made: placing one block (:place), moving an item to the hand (:equip),
   using or pouring an item on a target (:use-on, :pour), eating (:eat), dropping (:toss),
   putting armour on (:wear), throwing an eye of ender (:throw-eye), one craft click (:craft)
   and opening a container (:open-container). Every other kind (a walk, a fight, a perch, a
   hold, a back-off, a dig, a stair) takes time, so one that is over in its first tick was
   chosen in a state its own end check already calls done: CHURN."
  #{:place :equip :use-on :pour :eat :toss :wear :throw-eye :craft :open-container})

(def sample-every-ms "Simulated ms between two points a snapshot's play is checked at." 10000)
(def max-ms
  "Simulated ms a snapshot is played on at most: the checks need the states, not the run."
  120000)

(defn violation
  "Does the planner, over table, churn in world: choose an intent that is done or failed after one
   tick of running, and, once it has ended (plan/finish-intent, plan/fail-intent), choose the same
   one again (plan/same-choice?: same kind, target and :intent/for)? Returns nil when not, or
   {:intent/kind :intent/target :intent/for :churn/status :churn/reason :goal/id} of that intent.
   A walk already in reach of its target ends at once and is no churn when the next choice is the
   dig; an instantaneous kind (instantaneous-kinds) is never reported. The world is looked at as
   the planner would at its next tick: a running intent is dropped (plan/abandon-intent) when the
   cursor allows, the goals re-derived (plan/reach), the first intent of the row chosen
   (plan/next-intent), started (plan/start-intent) and run once (intent/run) one tick on. Nil too
   for a plan not :active, a state with no goal, a wait, or a cursor holding an item."
  [world table]
  (let [i (:plan/intent world)
        free? (plan/can-abandon? world)
        world (cond-> world (and i free?) (plan/abandon-intent i :interrupted))]
    (when (and (= :active (:plan/status world)) (or free? (nil? i)) (:player/pos world))
      (let [world (plan/reach world table)
            goal (plan/choose world table)
            next (when goal (plan/next-intent world goal table))]
        (when (and next (not (:plan/wait next)))
          (let [started (plan/start-intent world (assoc next :intent/goal (:goal/id goal)))
                now (+ (:time/now world 0) physics/tick-ms)
                i (:plan/intent started)
                ran (intent/run (assoc started :time/now now)
                                i
                                {:event/kind :tick :event/now now :event/rand 0.5})
                end (:plan/intent ran)
                ended? (or (intent/done? end) (intent/failed? end))
                after
                (when ended?
                  (if (intent/done? end) (plan/finish-intent ran end) (plan/fail-intent ran end)))
                goal2 (when after (plan/choose after table))
                again (when goal2 (plan/next-intent after goal2 table))]
            (when (and ended?
                       (plan/same-choice? end again)
                       (not (contains? instantaneous-kinds (:intent/kind i))))
              {:goal/id (:goal/id goal)
               :intent/kind (:intent/kind i)
               :intent/target (:intent/target i)
               :intent/for (:intent/for i)
               :churn/status (:intent/status end)
               :churn/reason (:intent/reason end)})))))))

(defn state-summary
  "A world in a few words, for a CHURN line: simulated ms, feet, health and the intent running."
  [world]
  {:t (:time/now world)
   :feet (when (:player/pos world) (intent/feet world))
   :health (:player/health world)
   :running (select-keys (:plan/intent world) [:intent/kind :intent/target :intent/for])})

(defn check-snapshot!
  "Play the snapshot at path on from its moment and check it (violation) at the moment and every
   sample-every-ms of play. Prints a CHURN line for each violating point and returns
   {:path :samples :violations}."
  [path]
  (let [snap (capture/read-world path)
        w (simrace/world-for! snap)
        m (simrace/moment-of snap w)
        table plan/goals
        last-at (volatile! nil)
        found (volatile! [])
        samples (volatile! 0)
        check! (fn [world]
                 (vswap! samples inc)
                 (when-let [v (try (violation world table)
                                   (catch Exception e {:churn/error (str e)}))]
                   (let [row (assoc v :path path :state (state-summary world))]
                     (vswap! found conj row)
                     (println "CHURN" (pr-str row)))))
        stop? (fn [world]
                (let [now (:time/now world)]
                  (when (or (nil? @last-at) (>= (- now @last-at) sample-every-ms))
                    (vreset! last-at now)
                    (check! world))
                  (or (plan/done? world) (plan/failed? world))))]
    (check! (:world m))
    (vreset! last-at (:time/now (:world m)))
    (sim/play-from simrace/step
                   m
                   stop?
                   (min max-ms (simrace/window-of snap))
                   (simrace/go-of w (:snapshot/until snap))
                   nil)
    {:path path :samples @samples :violations @found}))

(defn -main
  "Check each snapshot path given (check-snapshot!), then print one CHURN-SUMMARY line: snapshots
   and points checked, violating points, and the distinct kinds among them. Exits 0 either way:
   the list is the finding."
  [& paths]
  (let [rs (mapv (fn [p]
                   (try (check-snapshot! p)
                        (catch Exception e
                          (println "CHURN-SKIP" p (str e))
                          {:path p :samples 0 :violations []})))
                 paths)
        vs (mapcat :violations rs)]
    (println "CHURN-SUMMARY"
             (pr-str {:snapshots (count rs)
                      :samples (reduce + (map :samples rs))
                      :violations (count vs)
                      :kinds (frequencies (map :intent/kind vs))}))
    (shutdown-agents)))
