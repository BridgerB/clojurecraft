(ns clojurecraft.picks
  "The spare pickaxes row (issue #263): three stone pickaxes held before leaving the fortress
   after the blaze rods, using the table carried, sticks and cobblestone; made again whenever
   safe and down to one pickaxe with the materials.

   Requiring this namespace registers the :spare-picks row's rules: an always row at the blaze
   rods' priority, so a chained run comes to it once seven rods are in hand and the bot is sealed
   and safe in its pocket before leaving the fortress, and again during the pearl hunt whenever
   safe and down to one pickaxe. Done by :spare-picks-stocked?, planned by :spare-picks through
   the needs planner into the craft and taking the table along."
  (:require [clojurecraft.blaze :as blaze]
            [clojurecraft.blocks :as blocks]
            [clojurecraft.dimension :as dimension]
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
  (boolean (and (pocket/in? world) (pocket/sealed? world (:pocket/site world)))))

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
                (or (placed-table? world)
                    (room-needed? world)
                    (and (materials? world)
                         (let [k (serviceable-count world)
                               table-placed? (some? (make/near world :crafting_table))]
                           (or (<= k 1)
                               table-placed?
                               (and (pearls/near-fortress? world) (< k target-picks)))))))))

(defmethod plan/done-by :spare-picks-stocked? [world _] (not (pending? world)))

(defmethod plan/next-intent :spare-picks
  [world goal table]
  (when (pending? world)
    (or
     ;; 1. Toss 1 junk stack if sealed and all 36 slots are used (room for pearl)
     (when (room-needed? world) (window/reserve-toss world 1))
     ;; 2. In-flight table pickup
     (intent/chain-next (:plan/last world) :take-table make/table-drops {})
     ;; 3. Craft pickaxes up to target-picks (3)
     (when (and (< (serviceable-count world) target-picks) (materials? world))
       (make/decide (for-decide world)
                    (assoc goal :goal/provides {:item/stone_pickaxe 1})
                    (filterv #(not= :stone-pickaxe (:goal/id %)) table)))
     ;; 4. Take the placed table along once crafted
     (when (placed-table? world) (make/take-table-along world)))))
