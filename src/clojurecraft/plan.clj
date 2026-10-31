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
            [clojure.math :as math]
            [clojurecraft.blocks :as blocks]
            [clojurecraft.dimension :as dimension]
            [clojurecraft.game :as game]
            [clojurecraft.intent :as intent]
            [clojurecraft.inventory :as inventory]
            [clojurecraft.memory :as memory]
            [clojurecraft.physics :as physics]
            [clojurecraft.recipe :as recipe]
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

(def target-scoped-failures
  "[kind reason] pairs of failures that concern one target and no more: a fight that cannot reach
   its mob, a pickup that found nothing. fail-intent blacklists the target, which bounds the retry
   (the choice skips a blacklisted target), and counts no strike against the plan, for any goal.
   Blaze-race-p job 6 ended at 6m24s with 16 hp, fifteen beef and full iron on two
   :collect-drops :not-picked-up and a :fight :unreachable."
  #{[:fight :unreachable] [:collect-drops :not-picked-up]})

(def max-attempts 3)                  ; consecutive failed intents before the plan fails
(def stall-ms 900000)                 ; ms with no intent done (progress?) after which a run out of attempts is a dead end: 15 min
(def recover-cooldown-ms 10000)        ; ms between two recoveries: a recovery that fails again at once waits, it does not start over every 100 ms
(def stuck-ms 600000)                 ; ms without progress (progress-key changed, or a move of stuck-extent) after which the bot is made to go elsewhere: 10 min
(def fact-rows
  "The :goal/plan of the rows whose progress is items and facts only (progress-key): a walk or a
   tunnel does not count for them, however far. Blaze-race-z job 1 (run 41173997255) tunnelled 106 min
   round a ring 67 blocks across."
  #{:pearls :blaze-rods})
(def stuck-extent 16.0)               ; blocks the box of the feet's places since progress was last seen must span (x or z) to count as a move: a tunnel loop inside 12 blocks is not one
(def watch-every-ms 1000)             ; ms between two looks at progress
(def explore-distance 48)             ; blocks the walk away from a stuck spot goes
(def anchors
  "{:goal/plan #{attribute}}: the places a row holds on to (the pearl hunt's burrow, a blaze pocket),
   dropped when it has made no progress for stuck-ms, so it does not walk back to them."
  {:pearls #{:pearls/shelter :pearls/mouth :pearls/relocate :plan/boxed-at :plan/given-up}
   :blaze-rods #{:pocket/site :pocket/prepared-at :pocket/seal-failed-at}})
(def resets
  "{:goal/plan {attribute value}}: what a stuck row starts over with (the pearl hunt's blacklist)."
  {:pearls {:plan/blacklist #{}}})
(def clocks
  "{:goal/plan [path]}: the clocks (ms) inside a row's attributes restarted at now when it is stuck."
  {:pearls [[:pearls/gain :at]]})
(def stuck-after
  "{:goal/plan ms}: how long a row may go without progress before it is stuck, where it is not
   stuck-ms: a blaze camp can wait 14 to 16 min for its first blaze to be hurt."
  {:blaze-rods 1200000})
(def regroup-distance 8)              ; blocks from the feet a regroup walk goes back along the trail
(def non-progress
  "What finishes without moving the run on: intents of these kinds or for these purposes are done
   but are not progress, so a loop of rests and retreats never keeps the stall clock fresh."
  {:kind #{:rest :back-off :dodge :pocket-hold :lookout :eat}
   :for #{:regroup :retreat :cover :explore}})
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

(defmulti holds?
  "Is the running intent i a defence that another row's choice must not cut: one that runs to its
   own end, whatever row the planner chooses meanwhile? Keyed by :intent/kind; every intent of
   the :defend row by default, a fight excepted (it ends by its own break-off), and refuge kinds
   by a defmethod. Only an escape row (:goal/trap) preempts it. Blaze-race-n jobs 4 and 6 (run
   41105083204) lost a :pocket and a :seal to :defend within 50 ms of a new attacker, and
   pearls-travel-b job 1 (41134469333) flipped :eat and :back-off every 50 ms as a hoglin crossed
   the defend radius, no intent ever finishing."
  {:arglists '([i])}
  :intent/kind)

(defmethod holds? :default [i] (and (= :defend (:intent/goal i)) (not= :fight (:intent/kind i))))

(defn held?
  "Is running intent i kept against goal, the row chosen now: i serves another row, i is a
   defence (holds?), and goal is no escape (:goal/trap)? False for nil i or goal."
  [i goal]
  (boolean (and i
                goal
                (:intent/goal i)
                (not= (:goal/id goal) (:intent/goal i))
                (not (:goal/trap goal))
                (holds? i))))

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
      (dissoc :plan/intent :plan/last :plan/waiting :plan/waiting-since :plan/waiting-ticks)))

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

(def churn-log-every "Repeats of one intent between two `churn` log lines." 20)

(defn identity-of
  "The kind, and the target and :intent/for when the intent has them, of intent i: what makes two
   intents the same one for churn detection. Absent keys stay absent (an attribute is never nil)."
  [i]
  (select-keys i [:intent/kind :intent/target :intent/for]))

(defn mark-ended
  "The world with :plan/ended set to the identity of intent i (kind, target, purpose) and the
   ms it ended at: what start-intent compares the next intent with (churn-count)."
  [world i]
  (assoc world :plan/ended (assoc (identity-of i) :ended/at (:time/now world 0))))

(defn churn-count
  "How many times in a row the planner has started the same intent (kind, target and :intent/for)
   the tick after it ended, with i about to be the next one: 0 when i is not that repeat, else
   one more than :plan/churn counted for the same intent. CHURN is the bug where the planner's
   choice and the intent's own end check disagree about one state: the intent starts, ends in its
   first tick, and the planner picks it again every 50 ms while the bot stands still (WALK-RESTART,
   issue #615). Observation only: nothing reads it back."
  [world i]
  (let [e (:plan/ended world)
        same? (fn [m]
                (and (= (:intent/kind m) (:intent/kind i))
                     (= (:intent/target m) (:intent/target i))
                     (= (:intent/for m) (:intent/for i))))
        repeat? (and e (same? e) (<= (- (:time/now world 0) (:ended/at e)) physics/tick-ms))]
    (cond (not repeat?) 0
          (same? (:plan/churn world)) (inc (:churn/n (:plan/churn world)))
          :else 1)))

(defn note-churn
  "The world with :plan/churn updated for the intent i about to start (churn-count), and, once
   every churn-log-every repeats, a `churn <kind> <target> xN` line."
  [world i]
  (let [n (churn-count world i)]
    (cond-> (assoc world :plan/churn (assoc (identity-of i) :churn/n n))
      (and (pos? n) (zero? (mod n churn-log-every)))
      (game/say (str "churn " (:intent/kind i) " " (:intent/target i) " x" n)))))

(defn progress?
  "Does finishing intent i move the run on: not of a kind or for a purpose in non-progress?"
  [i]
  (not (or (contains? (:kind non-progress) (:intent/kind i))
           (contains? (:for non-progress) (:intent/for i)))))

(defn stalled?
  "Has no intent that moved the run on (progress?) finished for stall-ms: since :plan/done-at,
   else since the plan began (:plan/since)? Only then does a plan out of attempts end."
  [world]
  (let [now (:time/now world 0)
        since (:plan/done-at world (:plan/since world now))]
    (> (- now since) stall-ms)))

(defn regroup-cell
  "The latest cell of the trail (feet of recently started intents) at least regroup-distance
   from the feet on the plane, not blacklisted nor boxed-at, or nil: the new spot to plan from."
  [world]
  (let [feet (when (:player/pos world) (intent/feet world))
        ok? (fn [c]
              (and feet
                   (>= (physics/horizontal-distance c feet) regroup-distance)
                   (not (contains? (:plan/blacklist world #{}) c))
                   (not (contains? (:plan/boxed-at world #{}) c))))]
    (some #(when (ok? %) %) (rseq (:plan/trail world [])))))

(defn recover
  "A run out of attempts that is not stalled (stalled?) tries again instead of ending: the failure
   counts start over, the last failure is forgotten (so the same intent is not refused as an
   identical retry), and the trail's cell regroup-distance back (regroup-cell) becomes the next
   thing to walk to, planning again from a new spot. :plan/recoveries counts them. Within
   recover-cooldown-ms of the last one (:plan/recover-at) it changes nothing but the wait: pearl-fight-i
   job 3 (run 41178660163) recovered 34 times in a second, the :defend row's :place and :pillar
   failing three times in 300 ms each round, and was killed by the enderman it stood facing. Blaze-race-u
   (run 41156125534) ended at 4 health after three :place :no-support in 300 ms, and at 16 after a
   camp that found no blaze twice, with hours left."
  [world]
  (let [now (:time/now world 0)
        at (:plan/recover-at world)
        cell (regroup-cell world)]
    (if (and at (< (- now at) recover-cooldown-ms))
      (dissoc world :plan/waiting :plan/waiting-since :plan/waiting-ticks)
      (-> world
          (assoc :plan/attempts 0 :plan/unwalkable 0 :plan/recover-at now)
          (update :plan/recoveries (fnil inc 0))
          (dissoc :plan/failed :plan/waiting :plan/waiting-since :plan/waiting-ticks :plan/regroup)
          (cond-> cell (assoc :plan/regroup cell))
          (game/say (str "recover " (:plan/recoveries world 0) " regroup " (pr-str cell)))))))

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
      (mark-ended i)
      (assoc :plan/last i :plan/attempts 0 :plan/unwalkable 0)
      (cond-> (progress? i) (assoc :plan/done-at (:time/now world 0)))
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
        uncounted? (and (some? (:intent/target i))
                        (contains? target-scoped-failures [(:intent/kind i) (:intent/reason i)]))
        attempts (cond-> (:plan/attempts world 0) (not (or unwalkable? uncounted?)) inc)
        unwalkable (if unwalkable? (inc (:plan/unwalkable world 0)) 0)
        feet (when (:player/pos world) (intent/feet world))]
    (-> world
        (memory/remember-intention i :failed)
        (mark-ended i)
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
        (as-> w
          (cond (not (or (>= attempts max-attempts) (>= unwalkable max-unwalkable))) w
                (stalled? w) (assoc w :plan/status :failed :plan/reason (:intent/reason i))
                :else (recover w))))))

(declare plan-tick abandon-intent can-abandon?)

(defmulti progress-key
  "A value that changes when the row the goal serves makes progress: what the stuck watchdog
   compares from one look to the next. Keyed by :goal/plan; by default the items held by kind and
   the stages reached (:plan/reached). A row narrows it with a defmethod."
  {:arglists '([world goal])}
  (fn [_world goal] (:goal/plan goal :needs)))

(defn held-counts
  "{item count} of the stacks in the player's inventory, by item id."
  [world]
  (reduce (fn [m {:keys [item count]}] (update m item (fnil + 0) (or count 0)))
          {}
          (vals (:player/inventory world))))

(defmethod progress-key :pearls
  ;; the pearls held, the fights ended and the stages reached: a hunt that holds a pearl more, ended a
  ;; fight or reached a stage has made progress; tunnelling and walking do not
  [world _]
  [(inventory/item-count world (recipe/item-id :ender_pearl)) (memory/done-count world :provoke)
   (memory/done-count world :fight) (count (:plan/reached world #{}))])

(defmethod progress-key :blaze-rods
  ;; the rods held, the fights ended and the spawners on record: a new spawner found is a stand
  ;; reached; the netherrack a tunnel digs every leg is not progress (blaze-race-z job 1 tunnelled
  ;; 106 min inside 12 blocks)
  [world _]
  [(inventory/item-count world (recipe/item-id :blaze_rod)) (memory/done-count world :fight)
   (count (memory/positions-now world [(blocks/default-state :spawner)]))])

(defmethod progress-key :default [world _] [(held-counts world) (count (:plan/reached world #{}))])

(defn watch-stamp
  "The watch as of now: the progress key k, where the bot stands, when progress was last seen
   (:at) and when it was last looked at (:checked)."
  [world k at now]
  {:key k :lo (:player/pos world) :hi (:player/pos world) :at at :checked now})

(defn widened
  "The watch w with the box of the feet's places (:lo, :hi) widened to hold the feet now."
  [w world]
  (let [p (:player/pos world)] (assoc w :lo (mapv min (:lo w) p) :hi (mapv max (:hi w) p))))

(defn extent
  "The span, on the larger of x and z, of the box of the feet's places in watch w."
  [w]
  (let [[lx _ lz] (:lo w) [hx _ hz] (:hi w)] (max (- hx lx) (- hz lz))))

(defn explore-cell
  "The cell explore-distance from the feet at the feet's height: toward the farthest cell of the
   trail the bot came by (away from where it is stuck, never further into the room), else in the
   direction r (0 to 1 of a turn)."
  [world r]
  (let [[fx fy fz] (intent/feet world)
        far (apply max-key
                   #(physics/horizontal-distance % [fx fy fz])
                   [fx fy fz]
                   (:plan/trail world []))
        d (physics/horizontal-distance far [fx fy fz])
        a (* 2.0 math/PI r)
        [ux uz] (if (pos? d)
                  [(/ (- (first far) fx) d) (/ (- (nth far 2) fz) d)]
                  [(math/cos a) (math/sin a)])]
    [(+ fx (long (math/round (* explore-distance ux)))) fy
     (+ fz (long (math/round (* explore-distance uz))))]))

(defn unstick
  "The world of a bot that has made no progress for stuck-ms: the running intent dropped, the
   row's anchors (anchors) forgotten, a walk away planned (:plan/explore) and the watch restarted.
   Pearl-fight-i jobs 1 and 2 (run 41178660163) stood at one block for 2 h 20 min, 1631 lookouts,
   and blaze-race-z job 3 camped 119 min. Never a fight: only a walk."
  [world goal i r now]
  (-> world
      (cond-> i (abandon-intent i :stuck))
      (as-> w (apply dissoc w (get anchors (:goal/plan goal) #{})))
      (into (get resets (:goal/plan goal) {}))
      (as-> w
        (reduce (fn [w path] (cond-> w (get-in w path) (assoc-in path now)))
                w
                (get clocks (:goal/plan goal) [])))
      (assoc :plan/explore (explore-cell world r))
      (assoc :plan/watch (watch-stamp world (progress-key world goal) now now))
      (update :plan/unstuck (fnil inc 0))
      (game/say (str "stuck " (:plan/unstuck world 0) " " (:goal/id goal) " explore"))))

(defn watch
  "The stuck watchdog, one look a watch-every-ms: the world with its progress seen (the goal's
   progress-key changed, or the feet moved stuck-move) restamped, or, with none for stuck-ms,
   unstuck (unstick). Nothing while the plan is not active, no goal is chosen, or the running
   intent cannot be dropped yet (can-abandon?); event supplies :event/rand for the direction."
  [world table event]
  (let [now (:time/now world 0)
        w (:plan/watch world)]
    (cond (or (not= :active (:plan/status world)) (nil? (:player/pos world))) world
          (nil? w) (assoc world :plan/watch (watch-stamp world (progress-key world nil) now now))
          (< (- now (:checked w)) watch-every-ms) world
          :else
          (let [goal (choose world table)
                k (progress-key world goal)
                w (assoc (widened w world) :checked now)]
            (cond (nil? goal) world
                  (or (and (not (contains? fact-rows (:goal/plan goal)))
                           (>= (extent w) stuck-extent))
                      (not= k (:key w)))
                  (assoc world :plan/watch (watch-stamp world k now now))
                  (or (< (- now (:at w)) (get stuck-after (:goal/plan goal) stuck-ms))
                      (not (can-abandon? world))
                      (some-> (:plan/intent world)
                              holds?))
                  (assoc world :plan/watch w)
                  :else (unstick world goal (:plan/intent world) (:event/rand event 0.5) now))))))

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
        (note-churn i)
        (cond-> (:intent/dead-end i) (update :plan/boxed-at (fnil conj #{}) (:intent/dead-end i)))
        (dissoc :plan/waiting :plan/waiting-since :plan/waiting-ticks :plan/failed)
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
      (mark-ended i)
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
    (cond (and (> ticks wait-ticks) (= :retrying reason) (not (stalled? world))) (recover world)
          (> ticks wait-ticks) (assoc world :plan/status :failed :plan/reason reason)
          :else
          (assoc world :plan/waiting-since since :plan/waiting-ticks ticks :plan/waiting reason))))

(defn same-choice?
  "Would the planner start intent next again as the running intent i: the same kind, target and
   purpose (:intent/for)? False when next is nil or a wait. A cut that restarts what it cut ends
   the intent's controls and its route each tick and moves nothing (WALK-RESTART: a walk to
   [2345 58 2283] re-issued every 50 ms while a blaze stood at hand, the bot burning where it stood)."
  [i next]
  (boolean (and i
                next
                (not (:plan/wait next))
                (= (:intent/kind i) (:intent/kind next))
                (= (:intent/target i) (:intent/target next))
                (= (:intent/for i) (:intent/for next)))))

(defn plan-tick
  "One planning tick while :active. The goals are re-derived from the world every tick, even
   while an intent runs (docs/RICH-HICKEY.md: \"the planner runs every tick against the current
   value, so done is re-derived, regressions re-plan automatically\"): no target left means
   :done, whatever is running; a running intent that serves another target than the one chosen
   now is preempted, as is one the chosen row's interrupts? cuts short, unless the row, planning again
   without it, would start the same intent (same-choice?): that one keeps running. A defence
   (holds?) is neither preempted nor cut by another row but an escape: it runs to its own end.
   Either waits
   while the cursor holds an item. Otherwise the intent runs, or
   the chosen target's next intent starts."
  [world event table]
  (let [world (cond-> world
                (:player/pos world) trap/trapped-tick
                true (reach table))
        i (:plan/intent world)
        active? (= :active (:plan/status world))
        goal (when active? (choose world table))
        held (held? i goal)
        preempt? (and i goal (:intent/goal i) (not= (:goal/id goal) (:intent/goal i)) (not held))
        cut-world (when (and i goal (not preempt?) (not held) (interrupts? world goal i))
                    (abandon-intent world i :interrupted))
        cut? (and cut-world (not (same-choice? i (next-intent cut-world goal table))))
        next (when (and goal (nil? i)) (next-intent world goal table))
        feet (when (:player/pos world) (intent/feet world))
        failed (:plan/failed world)
        retrying? (and next (identical-retry? next failed feet))
        retreat-cell (when retrying?
                       (some #(when-not (or (= % feet) (contains? (:plan/blacklist world #{}) %)) %)
                             (rseq (:plan/trail world []))))]
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
          cut? (plan-tick cut-world event table)
          i (run-intent world i event table)
          (and (:plan/explore world) (not (:goal/trap goal)))
          (start-intent (dissoc world :plan/explore)
                        {:intent/kind :walk
                         :intent/target (:plan/explore world)
                         :intent/for :explore
                         :intent/goal (:goal/id goal)})
          (and (:plan/regroup world) (not (:goal/trap goal)))
          (start-intent (dissoc world :plan/regroup)
                        {:intent/kind :walk
                         :intent/target (:plan/regroup world)
                         :intent/for :regroup
                         :intent/goal (:goal/id goal)})
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
      :active (steered (plan-tick (watch world table event) event table) table)
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
