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
            [clojurecraft.make :as make]
            [clojurecraft.plan :as plan]
            [clojurecraft.recipe :as recipe]))

(set! *warn-on-reflection* true)

(def made
  "The swords made, best first, with what the blade takes (the handle is a stick): iron from
   ingots the armour leaves spare, stone from cobblestone, wood from planks."
  [[:iron_sword :iron_ingot 2] [:stone_sword :cobblestone 2] [:wooden_sword :planks 2]])

(def stick-planks 2)                  ; planks that make the sticks when no stick is held

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

(defn wanted
  "The name of the best sword of made that the held items pay for and that is better than the
   best held (combat/sword), with a handle and a table at hand (armour/table-at-hand?); nil
   otherwise."
  [world]
  (let [c (armour/counts world)
        held (second (combat/sword world))
        k (some (fn [[k mat n]]
                  (when (and (or (nil? held) (< (rank k) (rank held))) (blade? world c mat n)) k))
                made)]
    (when (and k (handle? c) (armour/table-at-hand? world)) k)))

(defmethod plan/done-by :armed?
  ;; only a chained run makes a sword: a gym stage measures its own target, and with the row in
  ;; play the kit stage (sim_test crafts-a-table-and-sticks-from-a-tree on a9a9b98) placed its
  ;; held table for a wooden sword, which undid the kit, which dug the table back, until the
  ;; run timed out
  [world _]
  (or (not (:plan/chain? world)) (nil? (wanted world))))

(defmethod plan/next-intent :sword
  [world goal table]
  (when-let [k (wanted world)]
    (make/decide world (assoc goal :goal/provides {(keyword "item" (name k)) 1}) table)))
