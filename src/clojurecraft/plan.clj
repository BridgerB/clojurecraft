(ns clojurecraft.plan
  "Goals are data; planning is a function; executing is open.

   `goals` is a table. Each goal answers two multimethods keyed on :goal/id: `goal-done?` (is the
   world already the way this goal wants it) and `next-intent` (given the world and the intent
   that just finished, what to do now: an intent map, {:plan/wait reason} for not yet, or nil
   for nothing). Every tick the planner re-derives the current goal from the world, so a goal
   that regresses (the log was lost) is simply chosen again. Plan state lives under :plan/*."
  (:require [clojurecraft.game :as game]
            [clojurecraft.intent :as intent]))

(def goals
  [{:goal/id :wood :goal/priority 1 :goal/doc "hold one log"}
   {:goal/id :kit :goal/priority 2 :goal/doc "a crafting table and four sticks, from logs"
    :goal/wants [[:crafting_table 1] [:stick 4]]}])

(def max-attempts 3)
(def wait-timeout 20000)              ; a goal that has nothing to do for this long has failed

(defmulti goal-done? (fn [_world goal] (:goal/id goal)))
(defmulti next-intent (fn [_world goal] (:goal/id goal)))

(defmethod goal-done? :default [_ _] true)
(defmethod next-intent :default [_ _] nil)

(defn choose
  "The highest-priority goal in play (:plan/goals, or every goal) the world does not satisfy."
  [world]
  (let [in-play (:plan/goals world)]
    (->> goals
         (filter #(or (nil? in-play) (contains? in-play (:goal/id %))))
         (sort-by :goal/priority)
         (remove #(goal-done? world %))
         first)))

(defn- begin [world {:go/keys [goals]}]
  (-> world
      (assoc :plan/status :active :plan/since (:time/now world) :plan/blacklist #{} :plan/attempts 0)
      (cond-> goals (assoc :plan/goals (set goals)))
      (dissoc :plan/intent :plan/last :plan/waiting-since)))

(defn- finish-intent [world i]
  (-> world (assoc :plan/last i) (dissoc :plan/intent)))

(defn- fail-intent [world i]
  (let [attempts (inc (:plan/attempts world 0))]
    (-> world
        (assoc :player/controls {})
        (cond-> (:intent/target i) (update :plan/blacklist (fnil conj #{}) (:intent/target i)))
        (assoc :plan/attempts attempts)
        (dissoc :plan/intent :plan/last)
        (game/say (str "intent " (:intent/kind i) " failed: " (:intent/reason i) " (attempt " attempts ")"))
        (cond-> (>= attempts max-attempts) (assoc :plan/status :failed :plan/reason (:intent/reason i))))))

(declare plan-tick)

(defn- run-intent
  "Advance the intent; when it ends, plan again in the same tick so the next intent or the
   goal's completion is derived immediately."
  [world i event]
  (let [world (intent/run world i event)
        i (:plan/intent world)]
    (cond (intent/done? i) (plan-tick (finish-intent world i) event)
          (intent/failed? i) (plan-tick (fail-intent world i) event)
          :else world)))

(defn- start-intent [world i]
  (-> world
      (assoc :plan/intent (assoc i :intent/status :active))
      (dissoc :plan/waiting-since)
      (game/say (str "intent " (:intent/kind i) " " (or (:intent/target i) (:intent/recipe i))))))

(defn- wait [world reason]
  (let [since (or (:plan/waiting-since world) (:time/now world))]
    (if (> (- (:time/now world) since) wait-timeout)
      (assoc world :plan/status :failed :plan/reason reason)
      (assoc world :plan/waiting-since since :plan/waiting reason))))

(defn- plan-tick [world event]
  (if-let [i (:plan/intent world)]
    (run-intent world i event)
    (if (not= :active (:plan/status world))
      world
      (if-let [goal (choose world)]
        (let [next (next-intent world goal)]
          (cond (nil? next) (wait world :nothing-to-do)
                (:plan/wait next) (wait world (:plan/wait next))
                :else (start-intent world next)))
        (-> world (assoc :plan/status :done :player/controls {}) (dissoc :plan/intent))))))

(defn step [world {:event/keys [kind] :as event}]
  (case kind
    :go (-> world (begin event) (game/say "go"))
    :tick (if (= :active (:plan/status world)) (plan-tick world event) world)
    world))

(defn done? [world] (= :done (:plan/status world)))
(defn failed? [world] (= :failed (:plan/status world)))

(defn summary [world]
  (into {} (filter (fn [[k _]] (= "plan" (namespace k))) (dissoc world :plan/blacklist))))
