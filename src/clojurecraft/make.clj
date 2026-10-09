(ns clojurecraft.make
  "Goals that want items (:goal/wants [[item-name n] ...]) are satisfied by walking the recipe
   graph: every tick the next action is re-derived from the inventory, so a lost or consumed
   item is simply planned for again. Gathering reuses the wood chain; crafting is the :craft
   intent. Requiring this namespace registers the goals."
  (:require [clojurecraft.craft]
            [clojurecraft.plan :as plan]
            [clojurecraft.recipe :as recipe]
            [clojurecraft.wood :as wood]))

(def grid-size 2)

(defn action [world goal]
  (recipe/next-action (recipe/counts (:player/inventory world)) (:goal/wants goal) grid-size))

(defn- in-a-chain? [world] (#{:walk :dig} (:intent/kind (:plan/last world))))

(defmethod plan/goal-done? :kit [world goal] (nil? (action world goal)))

(defmethod plan/next-intent :kit [world goal]
  (if (in-a-chain? world)
    (wood/gather-next world)
    (let [a (action world goal)]
      (cond
        (= :stuck a) {:plan/wait :stuck}
        (= :gather (:action a)) (wood/gather-next world)
        (= :craft (:action a)) {:intent/kind :craft :intent/recipe (:recipe a)}
        :else nil))))
