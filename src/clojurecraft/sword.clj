(ns clojurecraft.sword
  "The sword (issue #259): the best one the bot can make without a trip, when it is better than
   the best it holds.

   Requiring this namespace registers the :sword row's rules: an always row at the portal kit's
   priority, after the armour in the table, so a chained run comes to it once the iron the portal
   needs is in hand and the wood-hungry mining is behind it, and only in a chained run. Done by
   :armed? (nothing wanted, or no chain),
   planned by :sword through the needs planner into the craft. Until then a fight is fought with
   the hand: :defend never stops to make a sword with a zombie at hand."
  (:require [clojurecraft.armour :as armour]
            [clojurecraft.combat :as combat]
            [clojurecraft.inventory :as inventory]
            [clojurecraft.make :as make]
            [clojurecraft.plan :as plan]
            [clojurecraft.recipe :as recipe]
            [clojurecraft.window :as window]))

(set! *warn-on-reflection* true)

(def made
  "The swords made, best first, with what the blade takes (the handle is a stick): iron from
   ingots the armour leaves spare, stone from cobblestone, wood from planks."
  [[:iron_sword :iron_ingot 2] [:stone_sword :cobblestone 2] [:wooden_sword :planks 2]])

(def stick-planks 2)                  ; planks that make the sticks when no stick is held

(def wooden-spare-uses
  "Swings left a wooden sword keeps to be serviceable: an enderman takes 10 hits at 4 damage
   (40 health), so 10 swings finishes one kill. A fresh wooden sword has 59 durability; window/spare-uses
   (32) would drop it after a single kill."
  10)

(defn rank
  "A sword's place in combat/swords, 0 the best."
  [k]
  (.indexOf ^java.util.List combat/swords k))

(defn blade?
  "Do the held items c (armour/counts) pay for a blade of n of material mat, the handle
   (a stick, or stick-planks more planks) aside? Iron only from ingots spare once the armour
   wants nothing (armour/wanted, armour/spare-ingots); never a log: the wood is the pickaxes'."
  [world c mat n]
  (let [planks (recipe/have c (recipe/tags :planks))
        stick? (pos? (get c :stick 0))]
    (case mat
      :iron_ingot (and (nil? (armour/wanted world)) (>= (armour/spare-ingots world) n))
      :planks (>= planks (+ n (if stick? 0 stick-planks)))
      (>= (get c mat 0) n))))

(defn handle?
  "Is a sword's handle at hand in c: a stick, or the planks for sticks?"
  [c]
  (or (pos? (get c :stick 0)) (>= (recipe/have c (recipe/tags :planks)) stick-planks)))

(defn serviceable
  "The name of the best sword in the hotbar or the main inventory that still has window/spare-uses
   swings left (window/swings-left), or nil when every one is worn or none is held. A worn sword
   breaks in the next fight, so payable does not count it as the one already held."
  [world]
  (reduce (fn [best [p item]]
            (let [n (recipe/item-name (:item item))
                  left (window/swings-left item)]
              (if (and item
                       (< -1 p combat/store-slots)
                       (some #{n} combat/swords)
                       (or (nil? left)
                           (>= left (if (= :wooden_sword n) wooden-spare-uses window/spare-uses)))
                       (or (nil? best) (< (rank n) (rank best))))
                n
                best)))
          nil
          (:player/inventory world)))

(defn payable
  "The name of the best sword of made that the held items pay for and that is better than the
   best serviceable sword, with a handle (handle?). Nil when none is. A worn sword is not
   serviceable, so another of its rank is paid for before it breaks. A table is not asked:
   wanted adds one, and a hunt whose sword is worn or gone asks the needs planner for this one."
  [world]
  (let [c (armour/counts world)
        held (serviceable world)]
    (some (fn [[k mat n]]
            (when (and (or (nil? held) (< (rank k) (rank held))) (blade? world c mat n) (handle? c))
              k))
          made)))

(defn pieces?
  "Are a crafting table, the planks one takes (armour/table-planks), or a log already counted
   (armour/counts: the inventory, the crafting grid and the cursor)? make/counts sees the
   inventory alone, so a log laid into the grid is still here."
  [world]
  (let [c (armour/counts world)]
    (boolean (or (pos? (get c :crafting_table 0))
                 (>= (recipe/have c (recipe/tags :planks)) armour/table-planks)
                 (pos? (recipe/have c (recipe/tags :logs)))))))

(defn table-ready?
  "Can the sword be crafted without a trip for a tree: a table remembered near (make/near), or
   pieces? True while a log sits in the grid, so the sword row stays on the craft."
  [world]
  (boolean (or (make/near world :crafting_table) (pieces? world))))

(defn worn-sword?
  "Is this hotbar or main-inventory stack a sword under window/spare-uses swings
   (window/swings-left)?"
  [p item]
  (let [n (recipe/item-name (:item item))
        left (window/swings-left item)]
    (boolean
     (and (< -1 p combat/store-slots) (some #{n} combat/swords) left (< left window/spare-uses)))))

(defn drop-worn
  "A world whose worn swords (worn-sword?) keep their slots but are not swords. make/counts
   treats any stone sword as the one a row provides, worn or fresh, and would make nothing;
   clearing the slot would look like room in a full inventory. The stack stays, without an item."
  [world]
  (update
   world
   :player/inventory
   (fn [inv]
     (into {} (map (fn [[p item]] [p (if (worn-sword? p item) (dissoc item :item) item)])) inv))))

(defn with-laid
  "A world whose empty inventory slots also hold the crafting grid and the cursor (never the
   result), so make/counts sees a log laid down mid-craft. The intent decided from it names a
   recipe, not a slot."
  [world]
  (let [inv (:player/inventory world)
        extra (->> (concat (vals (dissoc (:window/grid world) inventory/result-slot))
                           (some-> (:window/cursor world)
                                   vector))
                   (filter :item))
        free (remove #(contains? inv %) (range combat/store-slots))]
    (assoc world :player/inventory (into inv (map vector free extra)))))

(defn for-decide
  "The world a sword's needs are decided on: worn swords hidden (drop-worn), the grid visible
   (with-laid), and a finished log chain not continued once pieces? holds, so the craft starts
   instead of the next trunk."
  [world]
  (cond-> (with-laid (drop-worn world))
    (and (pieces? world) (= :log (:intent/for (:plan/last world)))) (dissoc :plan/last)))

(defn wanted
  "The name payable returns when the sword can be crafted without a trip for a tree
   (table-ready?); nil otherwise, so the always row never goes looking for a tree."
  [world]
  (when (table-ready? world) (payable world)))

(defmethod plan/done-by :armed?
  ;; only a chained run makes a sword: a gym stage measures its own target, and with the row in
  ;; play the kit stage (sim_test crafts-a-table-and-sticks-from-a-tree on a9a9b98) placed its
  ;; held table for a wooden sword, which undid the kit, which dug the table back, until the
  ;; run timed out
  [world _]
  (or (not (:plan/chain? world)) (nil? (wanted world))))

(defmethod plan/next-intent :sword
  ;; nothing outside a chained run, as :armed? says. for-decide so a log in the grid is crafted
  ;; from, not walked away from to another trunk
  [world goal table]
  (when-let [k (and (:plan/chain? world) (wanted world))]
    (make/decide (for-decide world)
                 (assoc goal :goal/provides {(keyword "item" (name k)) 1})
                 table)))
