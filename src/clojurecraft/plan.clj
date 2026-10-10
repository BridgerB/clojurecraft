(ns clojurecraft.plan
  "Goals are data; planning is a function; executing is open.

   `goals` is the table in resources/clojurecraft/goals.edn: rows of {:goal/id :goal/priority
   :goal/needs :goal/provides :goal/done? :goal/act}. Two open registries, keyed by data in the
   row, give it meaning: `done-by` (keyed by :goal/done?, the name of a predicate) and `act`
   (keyed by :goal/act, what to do once the row's needs are met). `next-intent` (keyed by
   :goal/plan, default :needs) turns the goal in play into the next intent; clojurecraft.make
   registers the needs planner. Every tick the planner re-derives the current goal from the
   world, so a goal that regresses (the log was lost) is simply chosen again. Plan state lives
   under :plan/*."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojurecraft.game :as game]
            [clojurecraft.terrain :as terrain]
            [clojurecraft.intent :as intent]
            [clojurecraft.memory :as memory]
            [clojurecraft.physics :as physics]))

(def goals
  "The goal table, loaded from resources/clojurecraft/goals.edn."
  (edn/read-string (slurp (io/resource "clojurecraft/goals.edn"))))

(defn live?
  "Is a row in force? A row that turned out wrong is not edited or deleted: it stays in the table
   with :goal/superseded-by naming its replacement, so recordings that name it still replay."
  [row]
  (nil? (:goal/superseded-by row)))

(defn current-id
  "The id a goal id stands for today: itself, or the end of its :goal/superseded-by chain in
   table (at most a table's length of hops, so a cycle cannot hang it)."
  [table id]
  (let [by-id (into {} (map (juxt :goal/id identity)) table)]
    (loop [id id hops 0]
      (let [next (:goal/superseded-by (by-id id))]
        (if (and next (< hops (count table))) (recur next (inc hops)) id)))))

(defn targets-of "The live target rows of a table." [table] (filterv #(and (:goal/target? %) (live? %)) table))

(def targets (targets-of goals))

(defn goals-for
  "The goal ids a --until name puts in play (the live target rows' :goal/until), or nil."
  [until]
  (some (fn [g] (when (= until (:goal/until g)) [(:goal/id g)])) targets))

(def landing-timeout 30000)           ; a :go that names a landing waits this long for the bot to be there
(def landing-reach 2.0)               ; horizontal distance from :go/at that counts as landed

(def max-attempts 3)                  ; consecutive failed intents before the plan fails
(def wait-timeout 20000)              ; a goal that has nothing to do for this long has failed

(defmulti done-by
  "Is the goal satisfied in this world? A registry keyed by the row's :goal/done? (a predicate
   name), so the table stays data and another namespace can add predicates."
  (fn [_world goal] (:goal/done? goal)))

(defmulti act
  "The intent that satisfies a row whose needs are met, keyed by its :goal/act."
  (fn [_world goal] (:goal/act goal)))

(defmulti next-intent
  "The next intent toward a goal in play, planned over the goal table: an intent map,
   {:plan/wait reason}, or nil when there is nothing to do. Keyed by :goal/plan (default :needs)."
  (fn [_world goal _table] (:goal/plan goal :needs)))

(defmethod done-by :default [_ _] false)
(defmethod act :default [_ goal] {:plan/wait [:no-act (:goal/act goal)]})
(defmethod next-intent :default [_ _ _] nil)

(defn choose
  "The highest-priority live target of table in play (:plan/goals, or every target) the world
   does not satisfy."
  [world table]
  (let [in-play (:plan/goals world)]
    (->> (targets-of table)
         (filter #(or (nil? in-play) (contains? in-play (:goal/id %))))
         (sort-by :goal/priority)
         (remove #(done-by world %))
         first)))

(defn begin
  "Start planning. A :go that names a landing (:go/at, from the fixture) first waits in
   :landing until the bot is there with its chunk loaded; the wait is plan state, not a sleep."
  [world {:go/keys [goals at]} table]
  (-> world
      (cond-> (:plan/intent world) (memory/remember-intention (:plan/intent world) :abandoned))
      (assoc :plan/status (if at :landing :active) :plan/since (:time/now world) :plan/blacklist #{} :plan/attempts 0)
      (cond-> goals (assoc :plan/goals (set (map #(current-id table %) goals))))
      (cond-> at (assoc :plan/go-at at))
      (dissoc :plan/intent :plan/last :plan/waiting-since)))

(defn landed?
  "Is the bot at the landing the fixture named, standing in a loaded chunk?"
  [world at]
  (let [pos (:player/pos world)]
    (boolean (and pos (:player/loaded? world)
                  (<= (physics/horizontal-distance pos at) landing-reach)
                  (terrain/chunk-loaded? world pos)))))

(defn landing-tick
  "While :landing, wait for the bot to be at :plan/go-at; then plan, or fail after landing-timeout."
  [world]
  (cond
    (landed? world (:plan/go-at world))
    (-> world (assoc :plan/status :active :plan/since (:time/now world)) (game/say "landed"))
    (> (- (:time/now world) (:plan/since world)) landing-timeout)
    (assoc world :plan/status :failed :plan/reason :no-landing)
    :else world))

(defn finish-intent
  "An intent that succeeds ends any failure streak: attempts count consecutive failures, so a
   long goal is not killed by three unrelated hiccups an hour apart."
  [world i]
  (-> world (memory/remember-intention i :done) (assoc :plan/last i :plan/attempts 0) (dissoc :plan/intent)))

(defn fail-intent
  "An intent failed: stop moving, blacklist its target, count the attempt; max-attempts
   failures in a row fail the plan with the intent's reason."
  [world i]
  (let [attempts (inc (:plan/attempts world 0))]
    (-> world
        (memory/remember-intention i :failed)
        (assoc :player/controls {})
        (cond-> (:intent/target i) (update :plan/blacklist (fnil conj #{}) (:intent/target i)))
        (assoc :plan/attempts attempts)
        (dissoc :plan/intent :plan/last)
        (game/say (str "intent " (:intent/kind i) " failed: " (:intent/reason i) " (attempt " attempts ")"))
        (cond-> (>= attempts max-attempts) (assoc :plan/status :failed :plan/reason (:intent/reason i))))))

(declare plan-tick)

(defn run-intent
  "Advance the intent; when it ends, plan again in the same tick so the next intent or the
   goal's completion is derived immediately."
  [world i event table]
  (let [world (intent/run world i event)
        i (:plan/intent world)]
    (cond (intent/done? i) (plan-tick (finish-intent world i) event table)
          (intent/failed? i) (plan-tick (fail-intent world i) event table)
          :else world)))

(defn start-intent
  "Make i the active intent, numbered from :plan/intents, remember that it started, and stop
   waiting."
  [world i]
  (let [n (inc (:plan/intents world 0))
        i (assoc i :intent/status :active :intent/id n)]
    (-> world
        (assoc :plan/intents n :plan/intent i)
        (memory/remember-intention i :started)
        (dissoc :plan/waiting-since)
        (game/say (str "intent " (:intent/kind i) " " (or (:intent/target i) (:intent/recipe i)))))))

(defn abandon-intent
  "Drop the running intent i before it ended, for reason (:goal-met, :preempted): remember it
   as abandoned, stop moving, and close any open container so the server is not left with one.
   Only called with an empty cursor (see can-abandon?), so closing never drops an item."
  [world i reason]
  (-> world
      (memory/remember-intention (assoc i :intent/reason reason) :abandoned)
      (assoc :player/controls {})
      (cond-> (:window/open world)
        (-> (game/emit {:packet/name :container-close :window-id (get-in world [:window/open :window/id])})
            (dissoc :window/open)))
      (dissoc :plan/intent)))

(defn can-abandon?
  "May the planner drop the running work now? Not while the cursor holds an item: the intent
   puts it down first, and the planner looks again next tick."
  [world]
  (nil? (:window/cursor world)))

(defn wait
  "Nothing to do yet for reason; waiting longer than wait-timeout fails the plan with it."
  [world reason]
  (let [since (or (:plan/waiting-since world) (:time/now world))]
    (if (> (- (:time/now world) since) wait-timeout)
      (assoc world :plan/status :failed :plan/reason reason)
      (assoc world :plan/waiting-since since :plan/waiting reason))))

(defn plan-tick
  "One planning tick while :active. The goals are re-derived from the world every tick, even
   while an intent runs (docs/hickey.md: \"the planner runs every tick against the current
   value, so done is re-derived, regressions re-plan automatically\"): no target left means
   :done, whatever is running; a running intent that serves another target than the one chosen
   now is preempted; either waits while the cursor holds an item. Otherwise the intent runs, or
   the chosen target's next intent starts."
  [world event table]
  (let [i (:plan/intent world)
        active? (= :active (:plan/status world))
        goal (when active? (choose world table))
        preempt? (and i goal (:intent/goal i) (not= (:goal/id goal) (:intent/goal i)))
        next (when (and goal (nil? i)) (next-intent world goal table))]
    (cond
      (not active?) world
      (and i (or (nil? goal) preempt?) (not (can-abandon? world))) (run-intent world i event table)
      (nil? goal) (-> world
                      (cond-> i (abandon-intent i :goal-met))
                      (assoc :plan/status :done :player/controls {})
                      (dissoc :plan/intent))
      preempt? (plan-tick (abandon-intent world i :preempted) event table)
      i (run-intent world i event table)
      (nil? next) (wait world :nothing-to-do)
      (:plan/wait next) (wait world (:plan/wait next))
      :else (start-intent world (assoc next :intent/goal (:goal/id goal))))))

(defn step-over
  "The planner over a goal table (docs/hickey.md: \"(plan world goals) → intent\"), as a reducer
   composed after game/step: :go begins a plan, :tick advances it. Nothing here reads a table
   from anywhere but its argument."
  [table world {:event/keys [kind] :as event}]
  (case kind
    :go (-> world (begin event table) (game/say "go"))
    :tick (case (:plan/status world)
            :active (plan-tick world event table)
            :landing (landing-tick world)
            world)
    world))

(def step
  "The planner reducer over the shipped goal table (resources/clojurecraft/goals.edn)."
  (partial step-over goals))

(defn done? "Has the plan reached every target in play?" [world] (= :done (:plan/status world)))
(defn failed? "Has the plan given up (see :plan/reason)?" [world] (= :failed (:plan/status world)))

(defn summary "Every :plan/* attribute but the blacklist, for the RESULT line." [world]
  (into {} (filter (fn [[k _]] (= "plan" (namespace k))) (dissoc world :plan/blacklist))))
