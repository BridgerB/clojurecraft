(ns clojurecraft.make
  "Goals that want items (:goal/wants [[item-name n] ...]) are satisfied by walking the recipe
   graph: every tick the next action is re-derived from the inventory and the world, so a lost
   or consumed item is simply planned for again.

   - gather: the log chain (wood/gather-next)
   - a craft that fits 2x2: in the player's own grid
   - a craft that needs 3x3: in a crafting table - craft (if its window is open), else open
     it (in reach), else walk to it (remembered), else place one (held), else get one
     (craft it, which may mean gathering)

   Requiring this namespace registers the goals."
  (:require [clojurecraft.craft]
            [clojurecraft.game :as game]
            [clojurecraft.memory :as memory]
            [clojurecraft.physics :as physics]
            [clojurecraft.place]
            [clojurecraft.plan :as plan]
            [clojurecraft.recipe :as recipe]
            [clojurecraft.wood :as wood]))

(def table-search 24)
(def open-reach 4.5)

(defn- counts [world] (recipe/counts (:player/inventory world)))

(defn action [world goal] (recipe/next-action (counts world) (:goal/wants goal) 3))

(defn- step-for
  "An intent for one recipe-graph action."
  [world {:keys [action recipe]}]
  (case action
    :gather (wood/gather-next world)
    :craft {:intent/kind :craft :intent/recipe recipe :intent/window :inventory}))

(defn- with-table [world recipe-id]
  (let [eye (game/eye world)
        table (memory/nearest world eye table-search memory/crafting-table?)]
    (cond
      (and table (= 12 (get-in world [:window/open :window/menu-type])))
      {:intent/kind :craft :intent/recipe recipe-id :intent/window :table}

      (and table (<= (physics/distance eye (mapv #(+ % 0.5) table)) open-reach))
      {:intent/kind :open-container :intent/target table}

      table {:intent/kind :walk :intent/target table}

      (pos? (get (counts world) :crafting_table 0))
      {:intent/kind :place :intent/item :crafting_table}

      :else
      (let [a (recipe/next-action (counts world) [[:crafting_table 1]] 2)]
        (if (map? a) (step-for world a) {:plan/wait :stuck})))))

(defn decide [world goal]
  (or (wood/continue-gather world)
      (let [a (action world goal)]
        (cond
          (nil? a) nil
          (= :stuck a) {:plan/wait :stuck}
          (= :gather (:action a)) (step-for world a)
          (recipe/fits? (recipe/by-id (:recipe a)) 2) (step-for world a)
          :else (with-table world (:recipe a))))))

(defmethod plan/goal-done? :kit [world goal] (nil? (action world goal)))
(defmethod plan/next-intent :kit [world goal] (decide world goal))
(defmethod plan/goal-done? :pickaxe [world goal] (nil? (action world goal)))
(defmethod plan/next-intent :pickaxe [world goal] (decide world goal))
