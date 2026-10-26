(ns clojurecraft.armour
  "Armour (issue #259): an iron helmet and chestplate, made from the ingots left once the portal
   kit's iron is in hand, and anything wearable held put on.

   Requiring this namespace registers two rows' rules. :wear-armour (an always row at priority 0,
   after the meal) is done by :dressed?, no piece held unworn over an empty armour slot, and
   planned by :wear into a :wear intent: two clicks, so it preempts like the escapes. :armour (an
   always row at the portal kit's priority, after it: the moment the iron the portal needs is in
   hand) is done by :armoured?, no piece the spare ingots pay for still to make, and planned by
   :armour through the needs planner into the craft of the next piece. The :wear executor is a
   defmethod of intent/run.

   :wear is two clicks in window 0, as a hand does it: pick the piece up from its slot, put it
   down in its armour slot (inventory/slot [:armour n], head to feet). Each click predicts
   nothing (window/click) and waits for the server's slots; the piece is worn when the server
   shows it in the armour slot, which the inventory keeps at player slots 36-39 (feet to head),
   so a worn piece still counts as held."
  (:require [clojure.string :as str]
            [clojurecraft.game :as game]
            [clojurecraft.intent :as intent]
            [clojurecraft.inventory :as inventory]
            [clojurecraft.make :as make]
            [clojurecraft.plan :as plan]
            [clojurecraft.recipe :as recipe]
            [clojurecraft.window :as window]))

(set! *warn-on-reflection* true)

(def parts
  "Which armour slot (inventory/slot-in's [:armour n], head to feet) a piece goes in, by the end
   of its name. A carved pumpkin is no armour but is worn on the head (its equippable
   component), the dragon row's hood against an enderman's stare."
  [["_helmet" 0] ["_chestplate" 1] ["_leggings" 2] ["_boots" 3] ["carved_pumpkin" 0]])

(defn part
  "The armour slot n (0 head .. 3 feet) item name k is worn in, or nil when it is no armour."
  [k]
  (when k (some (fn [[suffix n]] (when (str/ends-with? (name k) suffix) n)) parts)))

(defn worn-slot
  "The player-inventory slot that shows armour slot n (inventory/head-slot down: 39 the head, 36
   the feet)."
  [n]
  (- inventory/head-slot n))

(defn worn
  "The item name worn in armour slot n, or nil."
  [world n]
  (some-> (get-in world [:player/inventory (worn-slot n)])
          :item
          recipe/item-name))

(def store-slots 36)                  ; player slots 0-35, the hotbar and the main inventory

(defn unworn
  "[player-slot name] of a piece of armour held in the hotbar or the main inventory whose armour
   slot is empty, the lowest slot first; nil when there is none. A carved pumpkin is never
   one: it is the dragon row's to put on in the End, not dress for the whole run."
  [world]
  (->> (:player/inventory world)
       (sort-by key)
       (some (fn [[p {:keys [item]}]]
               (let [k (recipe/item-name item)
                     n (part k)]
                 (when (and (< -1 p store-slots) n (not= :carved_pumpkin k) (nil? (worn world n)))
                   [p k]))))))

(def made
  "The pieces made from spare ingots, in order, with their cost in iron ingots (the game's
   recipes: five for a helmet, eight for a chestplate)."
  [[:iron_helmet 5] [:iron_chestplate 8]])

(def kit-iron
  "What the portal needs of the iron, held (the iron kit and the portal kit): until all of it is,
   no ingot is spare."
  [:flint_and_steel :water_bucket :bucket])

(defn counts
  "Held items by name, wherever the bot's windows have them: the inventory, a crafting grid's
   cells (window 0's, or an open crafting table's, never the result) and the cursor.
   A craft moves its ingredients out of the inventory into the grid, and a :wear lifts its piece
   onto the cursor: counted from the inventory alone, the armour row's first run against the
   model (issue #259) saw its chestplate's eight ingots leave as they were laid, found nothing
   wanted, and abandoned the craft :goal-met with the grid full."
  [world]
  (let [grid (dissoc (:window/grid world) inventory/result-slot)
        {:window/keys [menu-type slots]} (:window/open world)
        own (when (some-> (inventory/layout-of-menu menu-type)
                          inventory/crafting?)
              (dissoc slots inventory/result-slot))]
    (recipe/counts (zipmap (range)
                           (concat (vals (:player/inventory world))
                                   (vals grid)
                                   (vals own)
                                   (some-> (:window/cursor world)
                                           vector))))))

(defn spare-ingots
  "The iron ingots held (counts) once every item of kit-iron is, else 0: armour never takes the
   iron the portal still needs."
  [world]
  (let [c (counts world)] (if (every? #(pos? (get c % 0)) kit-iron) (get c :iron_ingot 0) 0)))

(def table-planks 4)                  ; planks a crafting table takes

(defn table-at-hand?
  "Can a piece be crafted without a trip: a crafting table remembered near (make/near) or held,
   or the planks or a log to make one? An always row whose needs send the bot looking for a tree
   would wait :no-log in the Nether and fail the run there."
  [world]
  (let [c (make/counts world)]
    (boolean (or (make/near world :crafting_table)
                 (pos? (get c :crafting_table 0))
                 (>= (recipe/have c (recipe/tags :planks)) table-planks)
                 (pos? (recipe/have c (recipe/tags :logs)))))))

(defn wanted
  "The name of the next piece of made the spare ingots pay for and the bot does not hold (counts:
   worn, in a grid or on the cursor counts as held), when a table is at hand (table-at-hand?); nil otherwise."
  [world]
  (let [c (counts world)
        spare (spare-ingots world)
        k (some (fn [[k cost]] (when (and (zero? (get c k 0)) (>= spare cost)) k)) made)]
    (when (and k (table-at-hand? world)) k)))

(defmethod plan/done-by :dressed? [world _] (nil? (unworn world)))

(defmethod plan/next-intent :wear
  [world _ _]
  (when-let [[_ k] (unworn world)] {:intent/kind :wear :intent/item k}))

(defmethod plan/done-by :armoured? [world _] (nil? (wanted world)))

(defmethod plan/next-intent :armour
  ;; the next piece made through the needs planner, as if the row provided it
  [world goal table]
  (when-let [k (wanted world)]
    (make/decide world (assoc goal :goal/provides {(keyword "item" (name k)) 1}) table)))

;; ---------------------------------------------------------------- :wear

(defmulti wear-stage
  "Advance a :wear intent one tick in its :intent/stage (:pick :put :check :drop). Called only
   when no window-0 click is unanswered."
  {:arglists '([world intent])}
  (fn [_world i] (:intent/stage i)))

(defn worn? "Is item name k worn in its armour slot?" [world k] (= k (worn world (part k))))

(defn cursor-holds?
  "Is item name k on the cursor, as the server last said?"
  [world k]
  (= (recipe/item-id k) (get-in world [:window/cursor :item])))

(defmethod wear-stage :pick
  ;; a container open first closed (the server ignores window-0 clicks while one is its menu);
  ;; then the piece picked up from its slot, unless an earlier attempt left it on the cursor
  ;; (clicking its slot then would only put it back)
  [world {:intent/keys [item]}]
  (let [v (window/view world :inventory)
        p (some (fn [[p {i :item}]] (when (and (< -1 p store-slots) (= i (recipe/item-id item))) p))
                (sort-by key (:player/inventory world)))]
    (cond (worn? world item) (intent/done world)
          (:window/open world)
          (-> world
              (game/emit {:packet/name :container-close
                          :window-id (get-in world [:window/open :window/id])})
              (dissoc :window/open))
          (cursor-holds? world item)
          (intent/assoc-intent world :intent/stage :put :intent/since (:time/now world))
          (nil? p) (intent/fail world :not-held)
          :else
          (-> world
              (window/click v (inventory/left-click ((:view/slot-of v) p)))
              (intent/assoc-intent :intent/stage :put :intent/since (:time/now world))))))

(defmethod wear-stage :put
  ;; the click's answer can move the window's state id before its set-cursor packet lands:
  ;; wait for the cursor to show the piece, a stage's answer-timeout at the most, rather
  ;; than fail with the piece on its way to the cursor (gym chain-w1's dragon run 3: the
  ;; pumpkin wear failed :not-picked-up, the pumpkin stayed off the head and an enderman
  ;; slew the bot at the perch)
  [world {:intent/keys [item since]}]
  (cond (cursor-holds? world item)
        (-> world
            (window/click (window/view world :inventory)
                          (inventory/left-click (inventory/slot [:armour (part item)])))
            (intent/assoc-intent :intent/stage :check))
        (> (- (:time/now world) (or since 0)) window/answer-timeout)
        (intent/fail world :not-picked-up)
        :else world))

(defmethod wear-stage :check
  ;; worn with the cursor empty, done; anything on the cursor (the piece the slot refused,
  ;; or the piece swapped off a full slot: a carved pumpkin over an iron helmet) goes back
  ;; into a free slot first, so nothing is left on the cursor either way
  [world {:intent/keys [item]}]
  (cond (:window/cursor world) (intent/assoc-intent world :intent/stage :drop)
        (worn? world item) (intent/done world)
        :else (intent/fail world :not-worn)))

(defmethod wear-stage :drop
  [world _]
  (let [v (window/view world :inventory)
        s (window/free-slot world v)]
    (cond (nil? (:window/cursor world)) (intent/fail world :not-worn)
          s
          (-> world
              (window/click v (inventory/left-click s))
              (intent/assoc-intent :intent/stage :check))
          :else (intent/fail world :inventory-full))))

(defmethod intent/run :wear
  ;; {:intent/kind :wear :intent/item :iron_helmet}: pick it up, put it in its armour slot, done
  ;; when the server shows it there
  [world i _]
  (let [i (cond-> i (nil? (:intent/stage i)) (assoc :intent/stage :pick))
        world (assoc world :plan/intent i :player/controls {})]
    (case (window/waiting world i)
      :overdue (window/lost world i :pick)
      :waiting world
      (if (:intent/resync i)
        (window/resync world (window/view world :inventory))
        (wear-stage world i)))))
