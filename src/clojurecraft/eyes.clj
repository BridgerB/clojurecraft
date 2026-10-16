(ns clojurecraft.eyes
  "Eyes of ender (issue #264): the twelve the end portal's frames take, crafted in the 2x2 grid
   from what the two stages before hold: blaze powder from the rods (#262, two a rod) and an eye
   from a powder and a pearl (#263). Both recipes are generated craft rows, so the needs planner
   finds the way; no table, no trip.

   Requiring this namespace registers the :eyes planner: the needs planner (make/decide), whose
   :stuck becomes a wait that names what is short, :no-pearls or :no-rods. No recipe makes either;
   each is its own stage, so the wait says which stage to look at."
  (:require [clojurecraft.make :as make]
            [clojurecraft.plan :as plan]
            [clojurecraft.recipe :as recipe]))

(set! *warn-on-reflection* true)

(def powder-per-rod
  "Blaze powder one rod crafts into, from the game's recipe."
  (:recipe/count (recipe/by-id :blaze_powder)))

(defn lacking
  "What the held items c (make/counts) lack for n eyes of ender: :no-pearls when the pearls held do
   not cover the eyes still to make, else :no-rods when the blaze powder held and powder-per-rod a
   rod do not; nil when both do."
  [c n]
  (let [left (max 0 (- n (get c :ender_eye 0)))
        powder (- left (get c :blaze_powder 0))]
    (cond (< (get c :ender_pearl 0) left) :no-pearls
          (< (* powder-per-rod (get c :blaze_rod 0)) powder) :no-rods)))

(defmethod plan/next-intent :eyes
  ;; the needs planner, its :stuck named for what is short (lacking)
  [world goal table]
  (let [i (make/decide world goal table)
        k (lacking (make/counts world) (get-in goal [:goal/provides :item/ender_eye] 0))]
    (if (and k (= :stuck (:plan/wait i))) {:plan/wait k} i)))
