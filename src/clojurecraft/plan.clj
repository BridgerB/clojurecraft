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
            [clojurecraft.dimension :as dimension]
            [clojurecraft.game :as game]
            [clojurecraft.intent :as intent]
            [clojurecraft.memory :as memory]
            [clojurecraft.physics :as physics]
            [clojurecraft.terrain :as terrain]
            [clojurecraft.trap :as trap]))

(set! *warn-on-reflection* true)

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
    (loop [id id
           hops 0]
      (let [next (:goal/superseded-by (by-id id))]
        (if (and next (< hops (count table))) (recur next (inc hops)) id)))))

(defn targets-of
  "The live target rows of a table."
  [table]
  (filterv #(and (:goal/target? %) (live? %)) table))

(def targets (targets-of goals))

(defn goals-for
  "The goal ids a --until name puts in play (the live target rows' :goal/until), or nil."
  [until]
  (some (fn [g] (when (= until (:goal/until g)) [(:goal/id g)])) targets))

(defn current-until
  "The --until name a fresh run aims at. Of the live targets that name a :goal/until and are not
   :goal/always?, it is the one with the greatest :goal/priority. Escapes stay in play on any
   --until. Nil when none qualify."
  ([] (current-until goals))
  ([table]
   (->> (targets-of table)
        (remove :goal/always?)
        (filter :goal/until)
        (sort-by :goal/priority)
        last
        :goal/until)))

(defn through
  "The chain up to until: every live target with a :goal/until that is not an escape and not an
   aside (the water trial, :goal/plan :wade), whose priority is at most the named target's, in
   priority order. A fresh run plays these, so --until nether starts at wood. Nil when until
   names nothing. One gym stage still uses goals-for, which names only that target."
  [until]
  (when-let [end (first (filter #(= until (:goal/until %)) targets))]
    (->> targets
         (remove :goal/always?)
         (remove #(= :wade (:goal/plan %)))
         (filter :goal/until)
         (filter #(<= (:goal/priority %) (:goal/priority end)))
         (sort-by :goal/priority)
         (mapv :goal/id))))

(def landing-timeout 30000)           ; a :go that names a landing waits this long for the bot to be there
(def landing-reach 2.0)               ; horizontal distance from :go/at that counts as landed

(def max-attempts 3)                  ; consecutive failed intents before the plan fails
(def max-unwalkable 12)               ; consecutive walks that failed :no-path before the plan fails
(def wait-timeout 20000)              ; a goal that has nothing to do for this long has failed

(defmulti done-by
  "Is the goal satisfied in this world? A registry keyed by the row's :goal/done? (a predicate
   name), so the table stays data and another namespace can add predicates."
  {:arglists '([world goal])}
  (fn [_world goal] (:goal/done? goal)))

(defmulti act
  "The intent that satisfies a row whose needs are met, keyed by its :goal/act."
  {:arglists '([world goal])}
  (fn [_world goal] (:goal/act goal)))

(defmulti next-intent
  "The next intent toward a goal in play, planned over the goal table: an intent map,
   {:plan/wait reason}, or nil when there is nothing to do. Keyed by :goal/plan (default :needs)."
  {:arglists '([world goal table])}
  (fn [_world goal _table] (:goal/plan goal :needs)))

(defmethod done-by :default [_ _] false)
(defmethod act :default [_ goal] {:plan/wait [:no-act (:goal/act goal)]})
(defmethod next-intent :default [_ _ _] nil)

(defn in-play
  "The live targets of table in play (:plan/goals, or every target), highest priority first. A
   row marked :goal/always? is in play whatever --until asked for: the escapes are."
  [world table]
  (let [goals (:plan/goals world)]
    (->> (targets-of table)
         (filter #(or (nil? goals) (:goal/always? %) (contains? goals (:goal/id %))))
         (sort-by :goal/priority))))

(defn here?
  "Is goal worked in the dimension the bot is in: it names none, or this one (:goal/dimension)?"
  [world goal]
  (let [d (:goal/dimension goal)] (or (nil? d) (= d (dimension/of world)))))

(defn undone
  "The targets in play the world does not satisfy and the run has not already reached
   (:plan/reached, a chained run's milestones), highest priority first, wherever they are worked."
  [world table]
  (->> (in-play world table)
       (remove #(contains? (:plan/reached world #{}) (:goal/id %)))
       (remove #(done-by world %))))

(defn choose
  "The highest-priority undone target that is worked here (here?), or nil."
  [world table]
  (first (filter #(here? world %) (undone world table))))

(defn elsewhere?
  "Is a target still undone that is worked in another dimension? Then no target here is not the
   end of the plan: it waits for the dimension to change."
  [world table]
  (boolean (seq (remove #(here? world %) (undone world table)))))

(defn reach
  "A chained run's milestones: on a :plan/chain? plan, every target in play that holds now joins
   :plan/reached and stays there for the run, the escapes excepted. A run from spawn spends what
   an earlier step made (the pickaxe takes the kit's sticks, the water bucket the bucket): the
   step was reached, so it is not done again; a tool that breaks is made again by the needs of
   the step that uses it. A gym stage, one target, keeps re-deriving."
  [world table]
  (if (:plan/chain? world)
    (update world
            :plan/reached
            (fnil into #{})
            (comp (remove :goal/always?)
                  (remove #(contains? (:plan/reached world #{}) (:goal/id %))) ; reached is reached: never asked again
                  (filter #(done-by world %))
                  (map :goal/id))
            (in-play world table))
    world))

(defn begin
  "Start planning. A :go that names a landing (:go/at, from the fixture) first waits in
   :landing until the bot is there with its chunk loaded; the wait is plan state, not a sleep."
  [world {:go/keys [goals at chain?]} table]
  (-> world
      (cond-> (:plan/intent world) (memory/remember-intention (:plan/intent world) :abandoned))
      (assoc :plan/status (if at :landing :active)
             :plan/since (:time/now world)
             :plan/blacklist #{}
             :plan/trunk-failures {}
             :plan/attempts 0
             :plan/unwalkable 0)
      (cond-> goals (assoc :plan/goals (set (map #(current-id table %) goals))))
      (cond-> at (assoc :plan/go-at at))
      (cond-> chain? (assoc :plan/chain? true :plan/reached #{}))
      (dissoc :plan/intent :plan/last :plan/waiting-since)))

(defn landed?
  "Is the bot at the landing the fixture named, standing in a loaded chunk?"
  [world at]
  (let [pos (:player/pos world)]
    (boolean (and pos
                  (:player/loaded? world)
                  (<= (physics/horizontal-distance pos at) landing-reach)
                  (terrain/chunk-loaded? world pos)))))

(defn landing-tick
  "While :landing, wait for the bot to be at :plan/go-at; then plan, or fail after landing-timeout."
  [world]
  (cond (landed? world (:plan/go-at world))
        (-> world
            (assoc :plan/status :active :plan/since (:time/now world))
            (game/say "landed"))
        (> (- (:time/now world) (:plan/since world)) landing-timeout)
        (assoc world :plan/status :failed :plan/reason :no-landing)
        :else world))

(defn finish-intent
  "An intent that succeeds ends any failure streak: attempts and unwalkable walks count
   consecutive failures, so a long goal is not killed by three unrelated hiccups an hour apart."
  [world i]
  (-> world
      (memory/remember-intention i :done)
      (assoc :plan/last i :plan/attempts 0 :plan/unwalkable 0)
      (dissoc :plan/intent)))

(defn fail-intent
  "An intent failed: stop moving, blacklist its target (and count a failure against its trunk's
   column, for a log: digging the base moves the bottom up, the column stays), count the attempt.
   max-attempts failures in a row fail the plan with the intent's reason. A walk that failed
   :no-path counts apart, in :plan/unwalkable: its route was accepted and then could not be
   walked, so the target is given up and the next candidate is tried (three such walks, each a
   different place, used to end a run that was still searching), but max-unwalkable of them in
   a row fail the plan too, so a search whose places keep changing cannot run for hours. A
   staircase that died :boxed records the cell it stood on in :plan/boxed-at, so the next descent
   does not start there and a later stair will not step back onto it."
  [world i]
  (let [unwalkable? (and (= :walk (:intent/kind i)) (= :no-path (:intent/reason i)))
        attempts (cond-> (:plan/attempts world 0) (not unwalkable?) inc)
        unwalkable (if unwalkable? (inc (:plan/unwalkable world 0)) 0)]
    (-> world
        (memory/remember-intention i :failed)
        (assoc :player/controls {})
        (cond-> (:intent/target i) (update :plan/blacklist (fnil conj #{}) (:intent/target i)))
        (cond-> (and (= :stairs-down (:intent/kind i)) (= :boxed (:intent/reason i)))
                (update :plan/boxed-at (fnil conj #{}) (intent/feet world)))
        (cond-> (:intent/trunk i)
                (update-in [:plan/trunk-failures (let [[x _ z] (:intent/trunk i)] [x z])]
                           (fnil inc 0)))
        (assoc :plan/attempts attempts :plan/unwalkable unwalkable)
        (dissoc :plan/intent :plan/last)
        (game/say
         (str "intent " (:intent/kind i) " failed: " (:intent/reason i) " (attempt " attempts ")"))
        (cond-> (or (>= attempts max-attempts) (>= unwalkable max-unwalkable))
                (assoc :plan/status :failed :plan/reason (:intent/reason i))))))

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
        (game/say (str "intent " (:intent/kind i)
                       " " (or (:intent/target i) (:intent/recipe i)))))))

(defn abandon-intent
  "Drop the running intent i before it ended, for reason (:goal-met, :preempted): remember it
   as abandoned, stop moving, and close any open container so the server is not left with one.
   Only called with an empty cursor (see can-abandon?), so closing never drops an item."
  [world i reason]
  (-> world
      (memory/remember-intention (assoc i :intent/reason reason) :abandoned)
      (assoc :player/controls {})
      (dissoc :plan/last) ; the next goal's planner must not continue a chain from it
      (cond-> (:window/open world)
              (-> (game/emit {:packet/name :container-close
                              :window-id (get-in world [:window/open :window/id])})
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
  (let [world (cond-> world
                (:player/pos world) trap/trapped-tick
                true (reach table))
        i (:plan/intent world)
        active? (= :active (:plan/status world))
        goal (when active? (choose world table))
        preempt? (and i goal (:intent/goal i) (not= (:goal/id goal) (:intent/goal i)))
        next (when (and goal (nil? i)) (next-intent world goal table))]
    (cond (not active?) world
          (and i (or (nil? goal) preempt?) (not (can-abandon? world)))
          (run-intent world i event table)
          (and (nil? goal) (elsewhere? world table))
          (-> world
              (cond-> i (abandon-intent i :goal-met))
              (dissoc :plan/intent)
              (wait :other-dimension))
          (nil? goal)
          (-> world
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
    :go
    (-> world
        (begin event table)
        (game/say "go"))
    :tick
    (case (:plan/status world)
      :active (plan-tick world event table)
      :landing (landing-tick world)
      world)
    world))

(def step
  "The planner reducer over the shipped goal table (resources/clojurecraft/goals.edn)."
  (partial step-over goals))

(defn done? "Has the plan reached every target in play?" [world] (= :done (:plan/status world)))
(defn failed? "Has the plan given up (see :plan/reason)?" [world] (= :failed (:plan/status world)))

(defn summary
  "Every :plan/* attribute but the blacklist, for the RESULT line."
  [world]
  (into {} (filter (fn [[k _]] (= "plan" (namespace k))) (dissoc world :plan/blacklist))))
