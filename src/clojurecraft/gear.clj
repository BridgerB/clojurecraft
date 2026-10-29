(ns clojurecraft.gear
  "The gear a bot takes into the Nether (issue #262): an iron sword, iron helmet, chestplate and
   leggings, and its raw meat cooked. The gym rows that get seven blaze rods in five minutes land
   with all of it; the race bot reached the blazes with a stone sword, no armour and no food
   (sim R1/17 carried six unsmelted raw iron).

   Requiring this namespace registers four rows' rules. :gear (a step of the chain, after the
   portal kit, before the cast) and :regear (an always row, once :gear was reached: after a death
   the gear is lost) are done by :geared? / :regeared?, nothing in gear-cost left to pay, and
   planned by :gear: the iron ingots the missing pieces cost, mined and smelted through the needs
   planner (the armour and sword rows then craft each piece the moment its ingots are spare), else
   the next missing piece itself when the ingots are held and no row has crafted it. :gear gives up
   gear-ms after the portal kit, so a world without iron never holds the run. :cook (an always row,
   overworld, after the portal kit) is done by :cooked?, no raw meat of the hunt's held, planned by
   :cook into a smelt of all of it: cooked meat feeds twice the raw."
  (:require [clojurecraft.armour :as armour]
            [clojurecraft.dimension :as dimension]
            [clojurecraft.eat :as eat]
            [clojurecraft.hunt :as hunt]
            [clojurecraft.intent :as intent]
            [clojurecraft.make :as make]
            [clojurecraft.memory :as memory]
            [clojurecraft.plan :as plan]
            [clojurecraft.recipe :as recipe]))

(set! *warn-on-reflection* true)

(def gear-ms 3000000) ; ms after the portal kit the gear is worked for: fifty minutes of mining, then the run goes on

(def sword-cost "The iron ingots an iron sword costs." 2)

(defn pieces
  "The pieces the gear is, in the order they are made, as [item-name ingots]: the armour's, then
   the sword."
  []
  (conj (vec armour/made) [:iron_sword sword-cost]))

(defn missing
  "The pieces of the gear not held (armour/counts: worn, in a grid or on the cursor counts as
   held), in order."
  [world]
  (let [c (armour/counts world)] (filterv (fn [[k _]] (zero? (get c k 0))) (pieces))))

(defn gear-cost
  "The iron ingots the missing pieces cost, together."
  [world]
  (transduce (map second) + 0 (missing world)))

(defn started-at
  "The ms the gear's time began: the later of the portal kit's being reached and the last death
   (a new life makes its gear afresh, gear-ms each), or nil before either."
  [world]
  (let [since (:plan/since world (:time/now world 0))
        kit (some->> (get (:plan/reached-after world) :portal-kit)
                     (+ since))
        death (:answer/at (last (memory/answers world :death)))]
    (when-let [ts (seq (remove nil? [kit death]))] (reduce max ts))))

(defn expired?
  "Is the gear given up: gear-ms past started-at?"
  [world]
  (boolean (some-> (started-at world)
                   (->> (- (:time/now world 0)))
                   (>= gear-ms))))

(defn wanted?
  "Is the gear to be worked now: a chained run in the overworld, the portal kit reached (or its
   iron held), some piece missing, and not gear-ms past started-at?"
  [world]
  (let [reached (:plan/reached world #{})]
    (boolean (and (:plan/chain? world)
                  (= dimension/overworld (dimension/of world))
                  (or (contains? reached :portal-kit) (pos? (armour/spare-ingots world)))
                  (pos? (gear-cost world))
                  (not (expired? world))))))

(defmethod plan/done-by :geared?
  ;; not (wanted?) would be done before the portal kit, and plan/reach marks a chain target done
  ;; the moment it is, for the run (probe 40779151074: :gear was reached at t 0 and never run).
  ;; Done is the gear held, given up, or no chain; undone before, and the order of the chain keeps
  ;; it from being chosen until the portal kit is reached
  [world _]
  (or (not (:plan/chain? world)) (zero? (gear-cost world)) (expired? world)))

(defmethod plan/done-by :regeared?
  ;; the always row: only once the first gear was reached; before that :gear is the step
  [world _]
  (or (not (contains? (:plan/reached world #{}) :gear)) (not (wanted? world))))

(defn without-stick-spares
  "table with no row keeping spare sticks: the iron row spares twenty-four (twelve pickaxes'
   handles for the Nether's tunnel), and with few held the needs planner climbs to the trees to
   cut them (spare-worth?: a log can be started). Sim R1/1 (probe 40791377318) spent from 70 to 95
   minutes in the gear step on stairs up to the surface and stairs down for trees, with the iron
   not mined. The gear is mined with the sticks it has: a pickaxe worn out is still made from them."
  [table]
  (mapv (fn [row]
          (cond-> row
            (contains? (:goal/spares row) :item/stick) (update :goal/spares dissoc :item/stick)))
        table))

(defn gear-intent
  "The next intent toward the gear, or nil when none is wanted: the ingots for everything
   missing, mined through the needs planner; with them held, the next piece itself, for when no
   always row could craft it (no table at hand)."
  [world goal table]
  (when (wanted? world)
    (let [cost (gear-cost world)
          held (get (armour/counts world) :iron_ingot 0)
          [k _] (first (missing world))]
      (if (< held cost)
        (make/decide world
                     (assoc goal :goal/provides {:item/iron_ingot cost})
                     (without-stick-spares table))
        (make/decide world (assoc goal :goal/provides {(keyword "item" (name k)) 1}) table)))))

(defmethod plan/next-intent :gear [world goal table] (gear-intent world goal table))

(defmethod plan/next-intent :regear [world goal table] (gear-intent world goal table))

;; ---------------------------------------------------------------- cooking

(def cookable
  "{raw-name cooked-name} for the raw meats the hunt row brings (hunt/game-kinds) that a furnace
   cooks into food (eat/edible)."
  (let [raw (set (vals hunt/game-kinds))]
    (into {}
          (for [r recipe/smelting
                :let [in (first (:smelt/input r))]
                :when (and (= 1 (count (:smelt/input r)))
                           (contains? raw in)
                           (contains? eat/edible (:smelt/result r)))]
            [in (:smelt/result r)]))))

(defn raw-held
  "[raw-name cooked-name n] for the raw meat held in the largest number (n), or nil when none is."
  [world]
  (let [c (make/counts world)]
    (->> cookable
         (keep (fn [[raw cooked]] (let [n (get c raw 0)] (when (pos? n) [raw cooked n]))))
         (sort-by (comp - last))
         first)))

(def smelt-ticks 200) ; game ticks one cooked item takes
(def furnace-cobblestone 8) ; cobblestone a furnace is crafted from

(defn cook-at-hand?
  "Can the raw meat held most be cooked without a trip: fuel held that burns it (make/burn:
   coal, planks, logs but the reserve), and a furnace near or held, or the cobblestone and a
   table (armour/table-at-hand?) to make one? Sim R1/1 (probe 40791377318) went stairs down for
   trees and walked for an opening for half an hour with the cook row undone, and held ten coal
   by the end. A cook that needs fetching is not made."
  [world]
  (let [[_ _ n] (raw-held world)
        c (make/counts world)]
    (boolean (and n
                  (zero? (second (make/burn c (* n smelt-ticks))))
                  (or (make/near world :furnace)
                      (pos? (get c :furnace 0))
                      (and (>= (get c :cobblestone 0) furnace-cobblestone)
                           (armour/table-at-hand? world)))))))

(defn cook-wanted?
  "Is raw meat to be cooked now: a chained run in the overworld, the portal kit reached, raw meat
   held, and the cook at hand (cook-at-hand?)?"
  [world]
  (boolean (and (:plan/chain? world)
                (= dimension/overworld (dimension/of world))
                (contains? (:plan/reached world #{}) :portal-kit)
                (cook-at-hand? world))))

(defn cooking?
  "Is a smelt for the :cook row running? The raw meat is in the furnace by then and none is held,
   so the row would count itself done, and the planner would abandon the smelt as preempted
   (plan-tick: an intent serving another target than the one chosen) with the food inside. Sim
   R1/1 (probe 40795259629, :edible-drop lines): five raw items held at t 4815750 (:place, :cook),
   none at t 4816550 (:smelt, :cook), and the Nether entered at t 5078950 with none."
  [world]
  (let [i (:plan/intent world)]
    (boolean (and (= :cook (:intent/goal i))
                  (= :smelt (:intent/kind i))
                  (not (intent/done? i))
                  (not (intent/failed? i))))))

(defmethod plan/done-by :cooked? [world _] (and (not (cooking? world)) (not (cook-wanted? world))))

(defmethod plan/next-intent :cook
  ;; all the raw meat of the kind held most, smelted: the cooked stack provided is what is held
  ;; of it cooked already, plus the raw
  [world goal table]
  (when-let [[_ cooked n] (and (cook-wanted? world) (raw-held world))]
    (make/decide world
                 (assoc goal
                        :goal/provides
                        {(keyword "item" (name cooked)) (+ n (get (make/counts world) cooked 0))})
                 table)))
