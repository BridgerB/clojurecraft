(ns clojurecraft.picks
  "The spare pickaxes row (issue #263): three stone pickaxes held before leaving the fortress
   after the blaze rods, using the table carried, sticks and cobblestone; made again whenever
   safe and down to one pickaxe with the materials.

   Requiring this namespace registers the :spare-picks row's rules: an always row at the blaze
   rods' priority, so a chained run comes to it once seven rods are in hand and the bot is sealed
   and safe in its pocket before leaving the fortress, and again during the pearl hunt whenever
   safe and down to one pickaxe. Done by :spare-picks-stocked?, planned by :spare-picks through
   the needs planner into the craft and taking the table along."
  (:require [clojure.math :as math]
            [clojurecraft.blaze :as blaze]
            [clojurecraft.blocks :as blocks]
            [clojurecraft.dimension :as dimension]
            [clojurecraft.drops :as drops]
            [clojurecraft.intent :as intent]
            [clojurecraft.inventory :as inventory]
            [clojurecraft.make :as make]
            [clojurecraft.pearls :as pearls]
            [clojurecraft.plan :as plan]
            [clojurecraft.pocket :as pocket]
            [clojurecraft.recipe :as recipe]
            [clojurecraft.sword :as sword]
            [clojurecraft.window :as window]))

(set! *warn-on-reflection* true)

(def target-picks
  "The number of stone pickaxes the bot keeps stocked before leaving the fortress and in the hunt."
  3)

(defn serviceable-pick?
  "Is slot a pickaxe with window/spare-uses or more digs left (or fresh)?"
  [item]
  (boolean (and item
                (= :pickaxe (:kind (blocks/tool (:item item))))
                (let [left (window/swings-left item)]
                  (or (nil? left) (>= (long left) window/spare-uses))))))

(defn serviceable-count
  "How many serviceable pickaxes (serviceable-pick?) the bot holds in hotbar and main inventory."
  [world]
  (count (filter serviceable-pick? (vals (:player/inventory world)))))

(defn materials?
  "Can at least one more stone pickaxe be crafted from held supplies: a table (held or near),
   sticks and cobblestone?"
  [world]
  (let [c (make/counts world)]
    (and (or (pos? (long (get c :crafting_table 0))) (some? (make/near world :crafting_table)))
         (>= (long (get c :stick 0)) 2)
         (>= (long (get c :cobblestone 0)) 3))))

(defn rod-bot?
  "Has the bot collected the required blaze rods (rods-wanted) or reached stage 18?"
  [world]
  (boolean (or (>= (blaze/rods world) blaze/rods-wanted)
               (contains? (:plan/reached world) :blaze-rods)
               (pearls/pearls-stage? world))))

(defn blaze-in-sight?
  "Is any alive blaze in sight of the bot (combat/seen?) within shooter range?"
  [world]
  (boolean (or (blaze/shooter world) (seq (blaze/watchers world)))))

(defn sealed-in-fortress?
  "Is the bot sealed in its pocket inside or near the fortress?"
  [world]
  (boolean (or (and (pocket/in? world) (pocket/sealed? world (:pocket/site world)))
               (:pearls/refuge-done world))))

(defn safe?
  "Is the bot in a safe state to make pickaxes: health at or above danger health, not on fire,
   not trapped, no blaze in sight, no angry enderman near, and if in the fortress, sealed in
   its pocket?"
  [world]
  (boolean (and (>= (double (:player/health world 20.0)) (double pearls/danger-health))
                (not (:player/on-fire? world))
                (nil? (:plan/trap world))
                (not (blaze-in-sight? world))
                (nil? (pearls/blaze-refuge world))
                (not (pearls/menaced? world))
                (or (not (pearls/near-fortress? world)) (sealed-in-fortress? world)))))

(defn placed-table?
  "Is the crafting table placed near but not held in the bag?"
  [world]
  (boolean (and (zero? (long (get (make/counts world) :crafting_table 0)))
                (make/near world :crafting_table))))

(defn table-room?
  "Is there room in the bag for the table to come back: a slot free, or a junk stack to throw
   (window/reserve-slot)? With neither, a table drop is never picked up: on 0ace52e 41 of 42
   :nothing-to-do rod bots ended in `:collect-drops :for :take-table` failing :not-picked-up
   every tick, 36 of 36 slots used, until the run's end."
  [world]
  (boolean (or (pos? (long (window/free-count world))) (some? (window/reserve-slot world 1)))))

(defn table-needed?
  "Is the crafting table placed near, dropped, or being collected, not yet held in the bag, with
   room for it to come back (table-room?)? Without room the table is left: the row never waits on
   a pickup that cannot happen."
  [world]
  (boolean
   (and (zero? (long (get (make/counts world) :crafting_table 0)))
        (table-room? world)
        (or (make/near world :crafting_table)
            (= :take-table (:intent/for (:plan/intent world)))
            (= :take-table (:intent/for (:plan/last world)))
            (seq (drops/lying world (recipe/item-id :crafting_table) (intent/feet world) 8.0))))))

(defn room-needed?
  "Does the sealed bot in the fortress have no free slot for an ender pearl drop and holding junk to toss?"
  [world]
  (boolean (and (sealed-in-fortress? world)
                (zero? (long (window/free-count world)))
                (zero? (long (inventory/item-count world (recipe/item-id :ender_pearl))))
                (some? (window/reserve-slot world 1)))))

(defn drop-picks
  "A world whose pickaxes keep their slots but have their :item dissoc'd, so make/counts
   does not see any held pickaxe and crafts a fresh one from raw materials."
  [world]
  (update world
          :player/inventory
          (fn [inv]
            (into {}
                  (map (fn [[p item]] [p
                                       (if (and item
                                                (= :pickaxe (:kind (blocks/tool (:item item)))))
                                         (dissoc item :item)
                                         item)]))
                  inv))))

(defn for-decide
  "The world on which pickaxe craft needs are decided: pickaxes hidden and grid visible."
  [world]
  (drop-picks (sword/with-laid world)))

(defn pending?
  "Is the spare-picks row active: spare pickaxes wanted, table to pick up, or room needed when sealed?"
  [world]
  (boolean (and (= dimension/nether (dimension/of world))
                (or (:plan/chain? world) (contains? (:plan/goals world) :spare-picks))
                (rod-bot? world)
                (safe? world)
                (or (table-needed? world)
                    (room-needed? world)
                    (and (materials? world)
                         (let [k (serviceable-count world)
                               table-placed? (some? (make/near world :crafting_table))]
                           (or (<= k 1)
                               table-placed?
                               (and (pearls/near-fortress? world) (< k target-picks)))))))))

(defn row-intent
  "The spare-picks row's next intent for a pending world (pending?), or nil when there is none
   it can start: the toss for room, the table pickup in flight, the craft, taking the placed
   table along, or collecting a dropped one."
  [world goal table]
  (or
   ;; 1. Toss 1 junk stack if sealed and all 36 slots are used (room for pearl)
   (when (room-needed? world) (window/reserve-toss world 1))
   ;; 1b. The table is to come back and no slot is free: throw a junk stack first, so the
   ;; pickup can happen (a full bag left it lying and the collect failing every tick)
   (when (and (table-needed? world) (zero? (long (window/free-count world))))
     (window/reserve-toss world 1))
   ;; 2. In-flight table pickup
   (intent/chain-next (:plan/last world) :take-table make/table-drops {})
   ;; 3. Craft pickaxes up to target-picks (3)
   (when (and (< (serviceable-count world) target-picks) (materials? world))
     (make/decide (for-decide world)
                  (assoc goal :goal/provides {:item/stone_pickaxe 1})
                  (filterv #(not= :stone-pickaxe (:goal/id %)) table)))
   ;; 4. Take the placed table along once crafted
   (when (placed-table? world) (make/take-table-along world))
   ;; 5. Collect dropped table if lying near
   (when-let [[_ e] (drops/nearest world :crafting_table (intent/feet world))]
     {:intent/kind :collect-drops
      :intent/item :crafting_table
      :intent/target (mapv #(long (math/floor (double %))) (:entity/pos e))
      :intent/for :take-table})))

(defn stocked?
  "Is the spare-picks row done: nothing pending (pending?), or pending with nothing it can start
   (row-intent nil)? A row that is chosen and starts nothing ends the run: the planner waits on it until
   :nothing-to-do, and on a3cbcc5 10 of 29 real rod bots still ended so with this row ahead of the
   hunt. One world that does it: a table pickup last (:plan/last :for :take-table) with no table
   placed and none lying near, so table-needed? holds and row-intent is nil every tick."
  [world goal]
  (not (and (pending? world) (row-intent world goal plan/goals))))

(defmethod plan/done-by :spare-picks-stocked? [world goal] (stocked? world goal))

(defmethod plan/next-intent :spare-picks
  [world goal table]
  (when (pending? world) (row-intent world goal table)))
