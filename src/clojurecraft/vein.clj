(ns clojurecraft.vein
  "The whole of an iron vein (flag #1, 2026-10-31): while iron is wanted for the chain, the ore in
   sight is taken before the planner goes on. The needs planner nets the :ingots row's four
   ingots against the four raw iron held and moves to the smelt, leaving ore beside the bot; the
   chain wants twenty-nine in all. Requiring this namespace registers make/vein."
  (:require [clojurecraft.armour :as armour]
            [clojurecraft.game :as game]
            [clojurecraft.intent :as intent]
            [clojurecraft.make :as make]
            [clojurecraft.physics :as physics]
            [clojurecraft.plan :as plan]
            [clojurecraft.stone :as stone]))

(set! *warn-on-reflection* true)

(def vein-radius "Blocks from the eye an ore of the vein is still taken, before moving on." 8)

(def chain-iron
  "The iron ingots each piece of the chain's iron costs, as data (the game's recipes): the flint and
   steel one, the two buckets (the water bucket and the lava one) three each, the sword two, the
   armour five, eight and seven. A bucket held in any state (empty, water, lava) is one of the two."
  {:flint_and_steel 1 :iron_sword 2 :iron_helmet 5 :iron_chestplate 8 :iron_leggings 7})

(def bucket-cost "Ingots in one bucket." 3)
(def buckets-wanted "Buckets the chain carries: the water one and the one for lava." 2)

(defn iron-want
  "The raw iron still to be mined for the chain, from the held counts (armour/counts: worn and
   gridded items too): the ingots every piece not yet made costs (chain-iron, and the buckets
   short of buckets-wanted), less the ingots and raw iron already held. Never negative. A pure
   function of what is held: flag #1, 2026-10-31, the bot dug three ores of a vein, held four raw
   iron (the :ingots row's four), and left the ore it could see for a staircase, with twenty-nine
   ingots wanted by the chain."
  [counts]
  (let [made (reduce + (for [[k n] counts :when (#{:bucket :water_bucket :lava_bucket} k)] n))
        short-buckets (max 0 (- buckets-wanted made))
        pieces (reduce + (for [[k cost] chain-iron :when (zero? (get counts k 0))] cost))]
    (max 0
         (- (+ pieces (* bucket-cost short-buckets))
            (get counts :iron_ingot 0)
            (get counts :raw_iron 0)))))

(defn vein-next
  "The next step of taking the whole vein, or nil: once iron has been mined (raw iron held) and
   the chain still wants more (iron-want), the nearest iron ore exposed within vein-radius of the
   eye, a chain in flight continued first. The needs planner nets the :ingots row's four against
   the four raw iron held and goes on to the smelt, so without this the ore beside the bot is
   left; a player mines the vein. Only what is in sight: no stairs, no tunnel (the row does
   those)."
  [world]
  (let [row (first (filter #(= :mine-iron (:goal/id %)) plan/goals))
        c (armour/counts world)]
    (when (and row
               (pos? (get c :raw_iron 0))
               (pos? (iron-want c))
               (nil? (stone/tool-missing world row)))
      (or (stone/continue-mine world row)
          (let [eye (game/eye world)]
            (->> (stone/exposed-of-states world (stone/states-of-row row))
                 (filter #(<= (physics/distance eye (physics/centre %)) vein-radius))
                 (take stone/max-candidates)
                 (map #(stone/walk-to world % (:goal/id row)))
                 (filter #(intent/routable? world %))
                 first))))))

(defmethod make/vein :vein [world] (vein-next world))
