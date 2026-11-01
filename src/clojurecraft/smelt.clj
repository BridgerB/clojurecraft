(ns clojurecraft.smelt
  "The :smelt intent: cook a held input in an open furnace and take the result.

     {:intent/kind :smelt :intent/smelt :iron_ingot_from_smelting_raw_iron :intent/count 4}

   Stages, each advanced one tick at a time against the furnace window's view:
     :settle  put a loaded cursor down, throw a junk stack out when no slot is free for the
              output (window/tossable), and shift-click anything left in the furnace back into
              the inventory, then plan the loading clicks: the fuel first (only as much as
              the batch burns, coal before wood), then the input
     :load    one click per round trip: send it, then wait until the window's state id moves
     :cook    wait for the output to hold the batch, by an absolute deadline, and past it for as
              long as the furnace still cooks; when the fire goes out with input left, load more
              fuel and go on
     :take    shift-click the output until the inventory shows the result
     :close   with nothing on the cursor and nothing left in the furnace, close it: done

   The rules are the siblings' furnace lessons (brain sib-smelt-fuel-deadlock): coal first,
   never more fuel than the batch needs (wood burned here is wood the tools lack), one click per
   round trip, the slots checked before the wait, never a close with a loaded cursor. A
   furnace's own ticks move the window's state id too, so a load is judged by the slots it
   should have filled, not by the state id alone.

   A batch loaded and not yet taken is remembered (:furnace/batch, set at the end of :load,
   cleared once the furnace is emptied at :settle or closed), so a smelt interrupted after the
   load is gone back to (make/collect-batch) and not mined for again: the planner counts the
   inventory only (issue #686).

   A lost click is recovered here rather than by failing: what a failed smelt leaves on the
   cursor or in the furnace is not in the inventory, so the planner, which plans from the
   inventory, could not plan for it again (lost)."
  (:require [clojure.math :as math]
            [clojurecraft.blocks :as blocks]
            [clojurecraft.game :as game]
            [clojurecraft.intent :as intent]
            [clojurecraft.inventory :as inventory]
            [clojurecraft.physics :as physics]
            [clojurecraft.recipe :as recipe]
            [clojurecraft.window :as window]))

(set! *warn-on-reflection* true)

(def stage-timeout 5000)              ; ms a smelt stage waits on the server before it fails
(def cook-slack 5000)                 ; ms past the batch's cook time before the cook stage fails
(def max-batch 64)                    ; the input slot holds one stack
(def input-slot (inventory/slot-in :furnace [:input]))   ; the furnace window's own slots
(def fuel-slot (inventory/slot-in :furnace [:fuel]))
(def output-slot (inventory/slot-in :furnace [:output]))

(defn- now [world] (:time/now world))

;; ---------------------------------------------------------------- fuel

(defn burn-ticks
  "Game ticks one of item name burns for (the game's fuel table), or nil when it does not burn."
  [n]
  (get blocks/fuel-by-name n))

(def burn-order
  "What the bot burns, in the order it burns it: coal and charcoal, then planks, each only where
   the game burns it (crimson planks do not). Never a log (it burns 300 ticks, the four planks it
   makes 1200), a stick, a tool, a table or anything else the fuel table lists. A stick is a
   pickaxe's handle and burns 100 ticks, half one smelt: counted as fuel, the ingots' fuel took
   the two sticks a new pickaxe needed deep in the mine, the planner sent the bot up for wood, and
   it dug coal with its last pickaxe until it broke (issue #36)."
  (mapv (fn [s] (into (sorted-set) (filter burn-ticks) s))
        [(recipe/tags :coals) (recipe/tags :planks)]))

(defn fuels
  "The fuel in counts ({item-name n}), in burn-order: [[item-name n] ...]. except (an item
   name, the input being smelted) is never fuel."
  [counts except]
  (for [group burn-order
        n group
        :let [c (get counts n 0)]
        :when (and (pos? c) (not= n except))]
    [n c]))

(defn fuel-ticks
  "Game ticks all the fuel in counts burns for, except the item name except."
  [counts except]
  (reduce + 0 (for [[n c] (fuels counts except)] (* c (burn-ticks n)))))

;; ---------------------------------------------------------------- planning a batch

(defn input-of
  "The item name of a recipe's input the counts hold most of, or nil when none."
  [counts {:smelt/keys [input]}]
  (let [held (filter #(pos? (get counts % 0)) (sort input))]
    (when (seq held) (apply max-key #(get counts % 0) held))))

(defn batch
  "How many of a recipe's input to smelt with what counts hold: all of the input, up to a
   stack, as far as the fuel (never the input itself) cooks; 0 when either is missing."
  [counts r]
  (if-let [in (input-of counts r)]
    (min (get counts in) max-batch (quot (fuel-ticks counts in) (:smelt/ticks r)))
    0))

(defn slot-holding
  "[player-slot count] of the lowest player slot holding item name, or nil."
  [world n]
  (let [id (recipe/item-id n)]
    (some (fn [[p {:keys [item count]}]] (when (= item id) [p count]))
          (sort-by key (:player/inventory world)))))

(defn lay
  "Clicks that move k of the stack in player slot p (have items) into window slot to of view
   v: pick the stack up, then put it all down, or put k down one at a time with right clicks
   and put the rest back."
  [{:view/keys [slot-of]} p have to k]
  (let [pick (inventory/left-click (slot-of p))]
    (if (= k have)
      [pick (inventory/left-click to)]
      (-> [pick]
          (into (repeat k (inventory/right-click to)))
          (conj pick)))))

(defn fuel-clicks
  "The clicks that load enough fuel for ticks of cooking from one held stack (the first of
   burn-order held, never the input except): all of it when it is not enough, the top-up
   covers the rest. nil when no fuel is held."
  [world v ticks except]
  (when-let [[n _] (first (fuels (recipe/counts (:player/inventory world)) except))]
    (let [[p have] (slot-holding world n)
          k (min have (long (math/ceil (/ ticks (burn-ticks n)))))]
      (lay v p have fuel-slot k))))

(defn load-plan
  "{:clicks :count} to cook up to n of recipe r: the input stack's count caps n; the fuel is
   laid first, for that many, then the input. nil when the input or the fuel is not held."
  [world v r n]
  (let [counts (recipe/counts (:player/inventory world))
        in (input-of counts r)
        [p have] (when in (slot-holding world in))
        n (min n (or have 0))
        fuel (when (pos? n) (fuel-clicks world v (* n (:smelt/ticks r)) in))]
    (when fuel {:clicks (into fuel (lay v p have input-slot n)) :count n})))

;; ---------------------------------------------------------------- the stages

(defn own-slot-holding
  "The first of the furnace's own slots (input, fuel, output) that holds an item, or nil."
  [{:view/keys [grid]}]
  (first (filter #(get grid %) (inventory/own-slots (inventory/layouts :furnace)))))

(defn put-down
  "Put the cursor's stack into a free inventory slot of view v, or fail :inventory-full."
  [world v]
  (if-let [s (window/free-slot world v)]
    (window/click world v (inventory/left-click s))
    (intent/fail world :inventory-full)))

(defn lit
  "Game ticks of fire the open furnace has left (its :lit property), 0 before the server says."
  [world]
  (get-in world
          [:window/open :window/data (get-in (inventory/layouts :furnace) [:properties :lit])]
          0))

(defmulti stage
  "Advance a :smelt intent one tick in its current :intent/stage (:settle :load :cook :take
   :close) against view v of the furnace window. Called only when no click is unanswered."
  {:arglists '([world intent view])}
  (fn [_world i _view] (:intent/stage i)))

(defmethod stage :settle
  [world {:intent/keys [smelt count since]} v]
  (let [r (recipe/smelt-by-id smelt)]
    (cond (nil? r) (intent/fail world :unknown-recipe)
          (nil? (:view/state-id v))
          (if (> (- (now world) since) stage-timeout) (intent/fail world :no-window) world)
          (:window/cursor world) (put-down world v)
          (window/tossable world v) (window/toss world v (window/tossable world v))
          (own-slot-holding v) (window/click world v (inventory/shift-click (own-slot-holding v)))
          :else
          ;; the furnace is empty: whatever a batch left in it is in the inventory (:furnace/batch)
          (if-let [{:keys [clicks] n :count} (load-plan (dissoc world :furnace/batch) v r count)]
            (let [world (dissoc world :furnace/batch)
                  result (recipe/item-id (:smelt/result r))]
              (intent/assoc-intent world
                                   :intent/stage :load
                                   :intent/clicks clicks
                                   :intent/since (now world)
                                   :intent/count n
                                   :intent/result result
                                   :intent/makes (* n (:smelt/count r))
                                   :intent/before (inventory/item-count world result)))
            (intent/fail (dissoc world :furnace/batch) :no-ingredient)))))

(defmethod stage :load
  [world {:intent/keys [clicks smelt makes]} v]
  (if (seq clicks)
    (-> world
        (window/click v (first clicks))
        (intent/assoc-intent :intent/clicks (vec (rest clicks))))
    (-> world
        (assoc :furnace/batch {:smelt smelt :makes makes :at (now world)})
        (intent/assoc-intent :intent/stage :cook :intent/since (now world))
        (intent/dissoc-intent :intent/clicks :intent/cook-by))))

(defn loaded?
  "Does the furnace hold the input and something to burn it with (fuel, or a fire already
   going)?"
  [world {:view/keys [grid]} r]
  (and (contains? (:smelt/input r)
                  (some-> (get grid input-slot)
                          :item
                          recipe/item-name))
       (or (some? (get grid fuel-slot)) (pos? (lit world)))))

(defn cooking?
  "Is the open furnace cooking recipe r now: a fire going (lit) and r's input in its input slot.
   The batch's deadline (:intent/cook-by) is the bot's clock, the cook the server's ticks, and a
   loaded server's ticks are longer: race 38805920624 runner 5 (issues #199, #200) ran both
   servers behind (over 200 \"Can't keep up\" each), bot Clj_r17's at about 11 ticks a second,
   three raw iron took 53 s against a 35 s deadline, and the bot failed :not-cooked 1.2 s
   before the second ingot came out; 20 more of the round's bots failed :not-cooked and then
   :stale-window (issues #131, #132), since the failure left the furnace open (place/equip).
   Past the deadline the cook waits while this holds: a furnace that cooks gives an item within
   its cook total of the server's ticks, and one that stops (the input gone, the fire out with
   no fuel) fails."
  [world {:view/keys [grid]} r]
  (and (pos? (lit world))
       (contains? (:smelt/input r)
                  (some-> (get grid input-slot)
                          :item
                          recipe/item-name))))

(defmethod stage :cook
  [world {:intent/keys [smelt makes since cook-by]} {:view/keys [grid] :as v}]
  (let [r (recipe/smelt-by-id smelt)
        input (get grid input-slot)]
    (cond (>= (get-in grid [output-slot :count] 0) makes)
          (intent/assoc-intent world :intent/stage :take :intent/since (now world))
          (nil? cook-by)
          (cond (loaded? world v r)
                (intent/assoc-intent
                 world
                 :intent/cook-by
                 (+ (now world) (* (:count input) (:smelt/ticks r) physics/tick-ms) cook-slack))
                (> (- (now world) since) stage-timeout) (intent/fail world :not-loaded)
                :else world)
          (and input (nil? (get grid fuel-slot)) (zero? (lit world)))
          (if-let [clicks (fuel-clicks world
                                       v
                                       (* (:count input) (:smelt/ticks r))
                                       (recipe/item-name (:item input)))]
            (intent/assoc-intent world :intent/stage :load :intent/clicks clicks)
            (intent/fail world :out-of-fuel))
          (and (> (now world) cook-by) (not (cooking? world v r))) (intent/fail world :not-cooked)
          :else world)))

(defmethod stage :take
  [world {:intent/keys [result makes before since]} {:view/keys [grid] :as v}]
  (cond (>= (inventory/item-count world result) (+ before makes))
        (intent/assoc-intent world :intent/stage :close)
        (> (- (now world) since) stage-timeout) (intent/fail world :not-taken)
        (get grid output-slot) (window/click world v (inventory/shift-click output-slot))
        :else world))

(defmethod stage :close
  [world _ v]
  (cond (:window/cursor world) (put-down world v)
        (own-slot-holding v) (window/click world v (inventory/shift-click (own-slot-holding v)))
        :else
        (-> world
            (game/emit {:packet/name :container-close :window-id (:view/id v)})
            (dissoc :window/open :furnace/batch)
            intent/done)))

(defn lost
  "A click went unanswered past window/answer-timeout (window/lost): a lost take or close goes on
   where it was (both are safe to repeat) and a lost settle or load click starts over at :settle,
   which puts the cursor down and empties the furnace before laying it again."
  [world {:intent/keys [stage] :as i}]
  (window/lost world i (if (#{:take :close} stage) stage :settle)))

(defmethod intent/run :smelt
  [world i _event]
  (let [i (cond-> i
            (nil? (:intent/stage i)) (assoc :intent/stage :settle :intent/since (now world)))
        world (assoc world :plan/intent i :player/controls {})
        v (window/view world :furnace)]
    (cond (nil? v) (intent/fail world :no-furnace-window)
          :else
          (case (window/waiting world i)
            :overdue (lost world i)
            :waiting world
            (if (:intent/resync i) (window/resync world v) (stage world i v))))))
