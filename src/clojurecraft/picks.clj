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
            [clojurecraft.entity :as entity]
            [clojurecraft.intent :as intent]
            [clojurecraft.inventory :as inventory]
            [clojurecraft.make :as make]
            [clojurecraft.memory :as memory]
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

(def take-table-gap-ms
  "ms after a table pickup intent ends that the table still counts as on its way back: the dig is
   done and the drop not yet seen, or the pickup not yet acked. Past it, with no table placed or
   lying, the pickup is over, never pending for ever."
  3000)

(defn take-table-just-ended?
  "Did an intent for the table pickup (:intent/for :take-table) end within take-table-gap-ms
   (:plan/ended, stamped by plan/mark-ended)? Without the bound a pickup that ended once held the
   row pending with nothing to start, and the planner, choosing this row ahead of the hunt, waited
   until :nothing-to-do: on a3cbcc5 (#727) 10 of 29 real rod bots still ended :nothing-to-do."
  [world]
  (let [e (:plan/ended world)]
    (boolean (and (= :take-table (:intent/for e))
                  (<= (- (long (:time/now world 0)) (long (:ended/at e 0))) take-table-gap-ms)))))

(def take-table-tries
  "Failed pickups of one table drop after which it is left where it lies: a drop the bot cannot
   take (in a hole under the feet, in a wall) failed :not-picked-up 100 times in sim-18 runs
   R2-6-1 and R3-6-1 (simrace lane 4, f9c021c), the row held ahead of the hunt the whole run."
  3)

(defn drop-cell
  "The cell a drop lies in."
  [e]
  (mapv #(long (math/floor (double %))) (:entity/pos e)))

(defn given-up-drop?
  "Has the bot failed take-table-tries pickups of the drop e at its cell (memory/failed-count-since
   on :collect-drops)? Then it is left."
  [world e]
  (>= (long (memory/failed-count-since world :collect-drops (drop-cell e) 0)) take-table-tries))

(defn table-drops-near
  "{eid entity} of the table drops lying within 8 blocks of the bot that it has not given up on.
   A drop that has not said what it holds (:entity/item nil) is no table drop: drops/lying lets
   it pass, and every block the tunnel dug was taken for one for the tick before its metadata
   came, a :collect-drops :for :take-table started and cut after 450 ms, over and over (sim
   stage-18 runs R1-3-1, R1-4-1, R1-44-1, 59e528b; issue #263)."
  [world]
  (let [table (recipe/item-id :crafting_table)]
    (into {}
          (comp (filter (fn [[_ e]] (= table (:entity/item e))))
                (remove (fn [[_ e]] (given-up-drop? world e))))
          (drops/lying world table (:player/pos world) 8.0))))

(defn placing-table?
  "Is the running intent this row's own placement of the crafting table (a :place of
   :crafting_table for :spare-picks)? Between the packet sent and the block seen the bag no longer
   holds the table and memory does not yet show it placed: with neither, the row read as done and
   the pearls row cut the placement, the pickaxe never crafted at the table left behind (sim
   stage-18 runs R1-3-1, R1-4-1 and R1-44-1 on 59e528b, the same place, then a cut 50 ms later,
   in 12 of 44 stalled runs; issue #263)."
  [world]
  (let [i (:plan/intent world)]
    (boolean (and (= :place (:intent/kind i))
                  (= :crafting_table (:intent/item i))
                  (= :spare-picks (:intent/goal i))
                  (= :active (:intent/status i))))))

(defn table-needed?
  "Is the crafting table placed near, dropped, or being collected, not yet held in the bag, with
   room for it to come back (table-room?)? Without room the table is left: the row never waits on
   a pickup that cannot happen. Its own placement in flight (placing-table?) counts: the table
   is about to stand."
  [world]
  (boolean (and (zero? (long (get (make/counts world) :crafting_table 0)))
                (table-room? world)
                (or (placing-table? world)
                    (make/near world :crafting_table)
                    (= :take-table (:intent/for (:plan/intent world)))
                    (take-table-just-ended? world)
                    (seq (table-drops-near world))))))

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

(defn spare-intent
  "The spare-picks row's next intent, or nil: nil when not pending?, and nil when pending? but
   none of its steps can start now (no toss, no pickup, no craft, no table to take). Its own
   table placement in flight (placing-table?) goes on as the running intent."
  [world goal table]
  (when (pending? world)
    (or (when (placing-table? world) (:plan/intent world))
        ;; 1. Toss 1 junk stack if sealed and all 36 slots are used (room for pearl)
        (when (room-needed? world)
          (intent/why :picks/toss-for-pearl-room (window/reserve-toss world 1)))
        ;; 1b. The table is to come back and no slot is free: throw a junk stack first, so the
        ;; pickup can happen (a full bag left it lying and the collect failing every tick)
        (when (and (table-needed? world) (zero? (long (window/free-count world))))
          (intent/why :picks/toss-for-table-pickup (window/reserve-toss world 1)))
        ;; 2. In-flight table pickup
        (intent/why :picks/table-pickup-in-flight
                    (intent/chain-next (:plan/last world) :take-table make/table-drops {}))
        ;; 3. Craft pickaxes up to target-picks (3)
        (when (and (< (serviceable-count world) target-picks) (materials? world))
          (intent/why :picks/craft-spare-pickaxe
                      (make/decide (for-decide world)
                                   (assoc goal :goal/provides {:item/stone_pickaxe 1})
                                   (filterv #(not= :stone-pickaxe (:goal/id %)) table))))
        ;; 4. Take the placed table along once crafted
        (when (placed-table? world)
          (intent/why :picks/take-table-along (make/take-table-along world)))
        ;; 5. Collect dropped table if lying near
        (when-let [[_ e] (entity/nearest (table-drops-near world) (:player/pos world))]
          (intent/why :picks/collect-dropped-table
                      {:intent/kind :collect-drops
                       :intent/item :crafting_table
                       :intent/target (drop-cell e)
                       :intent/for :take-table})))))

(defmethod plan/done-by :spare-picks-stocked?
  ;; done whenever no step of the row can start (spare-intent nil), not only when not pending?:
  ;; pending with nothing to start held the planner on this row, ahead of the hunt, until the plan
  ;; failed :nothing-to-do. Real-18 bots at landing [975 48 -176] (Clj_r08 on 6b5d6fa, Clj_r07 on
  ;; 3af52ae, probe 42264041854: choose :spare-picks, its next intent nil, the pearls row's a
  ;; tunnel to the surface) ended so 20 s after their first tunnel; #727, #728 and #739 each closed
  ;; one such case
  [world row]
  (nil? (spare-intent world row plan/goals)))

(defmethod plan/next-intent :spare-picks [world goal table] (spare-intent world goal table))
