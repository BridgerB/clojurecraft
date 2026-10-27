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
            [clojurecraft.trap :as trap]
            [clojurecraft.window :as window]))

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

(defn aside?
  "Is a target row a trial of its own rather than a step of the run: the water trial (:goal/plan
   :wade, from before the flag) or any row marked :goal/aside? (the gym's entities trial, issue
   #258)? A run from spawn never plays one, and none is the current stage."
  [row]
  (boolean (or (:goal/aside? row) (= :wade (:goal/plan row)))))

(defn current-until
  "The --until name a fresh run aims at. Of the live targets that name a :goal/until and are not
   :goal/always? and are not asides (aside?), it is the one with the greatest :goal/priority.
   Escapes stay in play on any --until. Nil when none qualify."
  ([] (current-until goals))
  ([table]
   (->> (targets-of table)
        (remove :goal/always?)
        (remove aside?)
        (filter :goal/until)
        (sort-by :goal/priority)
        last
        :goal/until)))

(defn through
  "The chain up to until: every live target with a :goal/until that is not an escape and not an
   aside (aside?), whose priority is at most the named target's, in priority order. A fresh run
   plays these, so --until nether starts at wood. Nil when until names nothing. One gym stage
   still uses goals-for, which names only that target."
  [until]
  (when-let [end (first (filter #(= until (:goal/until %)) targets))]
    (->> targets
         (remove :goal/always?)
         (remove aside?)
         (filter :goal/until)
         (filter #(<= (:goal/priority %) (:goal/priority end)))
         (sort-by :goal/priority)
         (mapv :goal/id))))

(def landing-timeout 30000)           ; a :go that names a landing waits this long for the bot to be there
(def landing-reach 2.0)               ; horizontal distance from :go/at that counts as landed

(def max-attempts 3)                  ; consecutive failed intents before the plan fails
(def max-unwalkable 12)               ; consecutive walks that failed :no-path before the plan fails
(def wait-ticks 400)                  ; a goal that has nothing to do for this many planner ticks has failed: 20 s at 20 ticks a second

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

(defmulti interrupts?
  "Should the running intent i of goal (the goal chosen now) end before it is done, so the row's
   act plans again? Nothing else ends a running intent but itself. Keyed by :goal/plan; never by
   default."
  {:arglists '([world goal i])}
  (fn [_world goal _i] (:goal/plan goal :needs)))

(defmulti steer
  "The world with the controls its running intent set this tick as goal, the row the intent
   serves, would have them: a row's own rule over every look its intents make, whatever their
   kind. Keyed by :goal/plan; the controls as they are by default."
  {:arglists '([world goal])}
  (fn [_world goal] (:goal/plan goal :needs)))

(defmethod interrupts? :default [_ _ _] false)
(defmethod steer :default [world _] world)

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
   the step that uses it. A gym stage, one target, keeps re-deriving. Each milestone's time is
   kept beside it, ms since the plan began (:plan/reached-after): how fast a stage goes."
  [world table]
  (if (:plan/chain? world)
    (let [new (into []
                    (comp (remove :goal/always?)
                          (remove #(contains? (:plan/reached world #{}) (:goal/id %))) ; reached is reached: never asked again
                          (filter #(done-by world %))
                          (map :goal/id))
                    (in-play world table))
          after (- (:time/now world 0) (:plan/since world (:time/now world 0)))]
      (cond-> (update world :plan/reached (fnil into #{}) new)
        (seq new) (update :plan/reached-after (fnil into {}) (map (fn [g] [g after])) new)))
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
      (dissoc :plan/intent :plan/last :plan/waiting-since :plan/waiting-ticks)))

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
   consecutive failures, so a long goal is not killed by three unrelated hiccups an hour apart.
   A log picked up (a :collect for :log) ends :plan/surfaced: the climb out it came after found
   a tree, so a later climb from near there for a spare's wood is worth it again
   (stairs/surfaced-near?, make/spare-worth?). Without it, sim world R4/30 seed 1 (the full run
   behind issue #161's second round, 74fa27b against 2736e27) came out of the ground for its
   first logs near the landing, took them, and two minutes later, its stone pickaxe made at y 60,
   declined the short climb back to those trees for the spare's sticks; it went down without
   them, climbed out again from y 14 at seven minutes, made its ingots at 22 minutes instead of 9,
   and timed out at the water bucket."
  [world i]
  (-> world
      (memory/remember-intention i :done)
      (assoc :plan/last i :plan/attempts 0 :plan/unwalkable 0)
      (cond-> (and (= :collect (:intent/kind i)) (= :log (:intent/for i))) (dissoc :plan/surfaced))
      (dissoc :plan/intent)))

(def placing-kinds #{:place :redig})  ; intents whose target is a cell they chose to put a block in, not a place they failed to reach
(def boxing-kinds #{:stairs-down :stairs-up :tunnel}) ; intents that die :boxed standing in a pocket, or under an overhang no stair up is cut through, which :plan/boxed-at remembers
(def crossing-kinds #{:cross})         ; intents whose target is a trunk the way to it failed, not a place to give up

(defn fail-intent
  "An intent failed: stop moving, blacklist its target (and count a failure against its trunk's
   column, for a log: digging the base moves the bottom up, the column stays), count the attempt.
   A :place or a :redig (placing-kinds) blacklists nothing: its target is the cell it chose, and
   the table placed there next would not count as near (make/near skips the blacklist): race
   38477665442 bot Clj_r04 failed a redig :not-picked-up at the cell before its feet, placed
   the table for a spare pickaxe in that cell, could not see it, and went for the wood of a new
   one with its last pickaxe (issue #43).
   max-unwalkable walks in a row that failed :no-path fail the plan with that reason, so a search
   whose places keep changing cannot run for hours. A staircase (down or up) or a tunnel that
   died :boxed (boxing-kinds) records the cell it stood on in :plan/boxed-at, so the next descent,
   climb or tunnel does not start there and a later stair will not step back onto it (the pearl
   hunt's climb toward an enderman on a ledge, issue #263, is refused under the ledge's overhang
   and must not be cut again from the same cell). Its visited cells join :plan/boxed-route: a
   retreat back along the failed search is not new rock (issue #161, R1/29). A walk for a log
   that failed :no-path adds its target to :plan/unreached, the places a choice of logs passes
   over together with the trees round them (issue #75). A :cross (crossing-kinds) blacklists
   nothing and counts against its trunk's column as a log's walk does, so the trunk is skipped
   after max-trunk-failures of them and no sooner: its target is the trunk across the water,
   which the bridge up the cliff at the far shore reaches (wood/trees-bridge) when the swim
   does not. Sim worlds R3/45 (issues #87 and #108) crossed a lake toward the only trunk in
   reach, met the foot of a cliff with the trunk on its top, and the failed cross blacklisted
   it: the staircase up the cliff then found no trunk to climb toward and the plan waited :no-log."
  [world i]
  (let [unwalkable? (and (contains? #{:walk :end-portal} (:intent/kind i))
                         (= :no-path (:intent/reason i)))
        attempts (cond-> (:plan/attempts world 0) (not unwalkable?) inc)
        unwalkable (if unwalkable? (inc (:plan/unwalkable world 0)) 0)
        feet (when (:player/pos world) (intent/feet world))]
    (-> world
        (memory/remember-intention i :failed)
        (assoc :player/controls {})
        (assoc :plan/failed (cond-> i feet (assoc :intent/from-feet feet)))
        (cond-> (and (:intent/target i)
                     (not (contains? placing-kinds (:intent/kind i)))
                     (not (contains? crossing-kinds (:intent/kind i))))
                (update :plan/blacklist (fnil conj #{}) (:intent/target i)))
        (cond-> (and (contains? boxing-kinds (:intent/kind i)) (= :boxed (:intent/reason i)))
                (-> (cond-> feet (update :plan/boxed-at (fnil conj #{}) feet))
                    (update :plan/boxed-route (fnil into #{}) (:intent/visited i))))
        (cond-> (and unwalkable? (= :log (:intent/for i)))
                (update :plan/unreached (fnil conj #{}) (:intent/target i)))
        (cond-> (or (:intent/trunk i)
                    (when (contains? crossing-kinds (:intent/kind i)) (:intent/target i)))
                (update-in [:plan/trunk-failures
                            (let [[x _ z] (or (:intent/trunk i) (:intent/target i))] [x z])]
                           (fnil inc 0)))
        (cond-> (and feet (>= attempts max-attempts)) (update :plan/boxed-at (fnil conj #{}) feet))
        (assoc :plan/attempts attempts :plan/unwalkable unwalkable)
        (dissoc :plan/intent :plan/last)
        (game/say (str "intent "
                       (:intent/kind i)
                       " failed: "
                       (:intent/reason i)
                       " (attempt "
                       attempts
                       " of "
                       max-attempts
                       ")"))
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
   waiting. An intent that names a dead end (:intent/dead-end) puts that cell in :plan/boxed-at."
  [world i]
  (let [n (inc (:plan/intents world 0))
        feet (when (:player/pos world) (intent/feet world))
        trail (:plan/trail world [])
        i (assoc i :intent/status :active :intent/id n)]
    (-> world
        (assoc :plan/intents n :plan/intent i)
        (cond-> (and feet (not= feet (peek trail)))
                (assoc :plan/trail (vec (take-last 128 (conj trail feet)))))
        (memory/remember-intention i :started)
        (cond-> (:intent/dead-end i) (update :plan/boxed-at (fnil conj #{}) (:intent/dead-end i)))
        (dissoc :plan/waiting-since :plan/waiting-ticks :plan/failed)
        (game/say (str "intent " (:intent/kind i)
                       " " (or (:intent/target i) (:intent/recipe i)))))))

(defn identical-retry?
  "Is next an identical repeat of the intent that just failed from feet: same kind, target, height or recipe?"
  [next failed feet]
  (and next
       failed
       feet
       (= feet (:intent/from-feet failed))
       (= (:intent/kind next) (:intent/kind failed))
       (= (:intent/target next) (:intent/target failed))
       (= (:intent/to-y next) (:intent/to-y failed))
       (= (:intent/recipe next) (:intent/recipe failed))
       (= (:intent/item next) (:intent/item failed))))

(defn abandon-intent
  "Drop the running intent i before it ended, for reason (:goal-met, :preempted, :interrupted): remember it
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
   puts it down first, and the planner looks again next tick. Nor while a click of the running
   intent is unanswered (window/waiting): a click forgets the cursor as it sends (window/click),
   so what it holds is unknown until the server answers. The armour row's first run against the
   model (issue #259) preempted a :wear between its pick-up and the answer, the helmet on a cursor
   the bot believed empty; the row that came next crafted another helmet from ingots the bot no
   longer knew where they were, and the run ended with eight of them unseen and no chestplate."
  [world]
  (and (nil? (:window/cursor world))
       (nil? (some->> (:plan/intent world)
                      (window/waiting world)))))

(defn wait
  "Nothing to do yet for reason; waiting longer than wait-ticks (400 planner ticks, 20 s at
   20 ticks a second) fails the plan with it. The deadline is counted in planner ticks, not
   wall ms: a runner keeping 25 bots and two servers to a machine starved this bot's loop to
   9 ticks in 21 s (race 38680745815 runner 5, issue #57's Clj_r05 and Clj_r10), and the wall
   clock ran the wait out before tick-counted logic — the water trap's 20 ticks, the hunt's
   next routable hop — had the ticks to act. The bot stood in a lake and on a peak waiting
   :no-log and failed while its recovery was a tick or two away."
  [world reason]
  (let [since (or (:plan/waiting-since world) (:time/now world))
        ticks (inc (:plan/waiting-ticks world 0))]
    (if (> ticks wait-ticks)
      (assoc world :plan/status :failed :plan/reason reason)
      (assoc world :plan/waiting-since since :plan/waiting-ticks ticks :plan/waiting reason))))

(defn plan-tick
  "One planning tick while :active. The goals are re-derived from the world every tick, even
   while an intent runs (docs/RICH-HICKEY.md: \"the planner runs every tick against the current
   value, so done is re-derived, regressions re-plan automatically\"): no target left means
   :done, whatever is running; a running intent that serves another target than the one chosen
   now is preempted, as is one the chosen row's interrupts? cuts short; either waits while the
   cursor holds an item. Otherwise the intent runs, or
   the chosen target's next intent starts."
  [world event table]
  (let [world (cond-> world
                (:player/pos world) trap/trapped-tick
                true (reach table))
        i (:plan/intent world)
        active? (= :active (:plan/status world))
        goal (when active? (choose world table))
        preempt? (and i goal (:intent/goal i) (not= (:goal/id goal) (:intent/goal i)))
        cut? (and i goal (not preempt?) (interrupts? world goal i))
        next (when (and goal (nil? i)) (next-intent world goal table))
        feet (when (:player/pos world) (intent/feet world))
        failed (:plan/failed world)
        retrying? (and next (identical-retry? next failed feet))
        retreat-cell (when retrying? (some #(when (not= % feet) %) (rseq (:plan/trail world []))))]
    (cond (not active?) world
          (and i (or (nil? goal) preempt? cut?) (not (can-abandon? world)))
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
          cut? (plan-tick (abandon-intent world i :interrupted) event table)
          i (run-intent world i event table)
          (nil? next) (wait world :nothing-to-do)
          (:plan/wait next) (wait world (:plan/wait next))
          (and retrying? retreat-cell)
          (start-intent (update world :plan/boxed-at (fnil conj #{}) feet)
                        {:intent/kind :walk
                         :intent/target retreat-cell
                         :intent/for :retreat
                         :intent/goal (:goal/id goal)})
          retrying? (wait world :retrying)
          :else (start-intent world (assoc next :intent/goal (:goal/id goal))))))

(defn steered
  "The world after steer by the row of table the running intent serves (:intent/goal), or as it
   is with no intent running."
  [world table]
  (let [id (:intent/goal (:plan/intent world))
        goal (when id (some #(when (= id (:goal/id %)) %) table))]
    (if goal (steer world goal) world)))

(defn step-over
  "The planner over a goal table (docs/RICH-HICKEY.md: \"(plan world goals) → intent\"), as a reducer
   composed after game/step: :go begins a plan, :tick advances it. Nothing here reads a table
   from anywhere but its argument. Each tick's controls are steered by the running intent's row
   (steered)."
  [table world {:event/keys [kind] :as event}]
  (case kind
    :go
    (-> world
        (begin event table)
        (game/say "go"))
    :tick
    (case (:plan/status world)
      :active (steered (plan-tick world event table) table)
      :landing (landing-tick world)
      world)
    world))

(def step
  "The planner reducer over the shipped goal table (resources/clojurecraft/goals.edn)."
  (partial step-over goals))

(defn done? "Has the plan reached every target in play?" [world] (= :done (:plan/status world)))
(defn failed? "Has the plan given up (see :plan/reason)?" [world] (= :failed (:plan/status world)))

(defn summary
  "Every :plan/* attribute but the blacklist, the creepers backed away from and the mobs a
   back-off was cornered by, for the RESULT line."
  [world]
  (into {}
        (filter (fn [[k _]] (= "plan" (namespace k))))
        (dissoc world :plan/blacklist :plan/backed-off :plan/cornered :plan/given-up)))
