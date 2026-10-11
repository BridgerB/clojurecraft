(ns clojurecraft.stone-test
  "The :mine act over the rows that name what they dig: the tool each needs, the drops a
   collect waits for, the stairs it cuts when nothing is in sight, and where it stops."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojurecraft.blocks :as blocks]
            [clojurecraft.fixtures :as fx]
            [clojurecraft.game :as game]
            [clojurecraft.plan :as plan]
            [clojurecraft.stone :as stone]
            [clojurecraft.world :as world]))

(use-fixtures :once fx/instrumented)

(defn row [id] (first (filter #(= id (:goal/id %)) plan/goals)))

(defn standing
  "On a bare stone floor (nothing to mine in sight but stone) with feet at y, holding items by
   name in the hotbar."
  [y & items]
  (assoc (game/init fx/opts)
         :bot/phase :play
         :player/pos [5.5 (double y) 5.5]
         :player/loaded? true
         :player/on-ground? true
         :player/inventory
         (into {} (map-indexed (fn [i n] [i {:item (blocks/items n) :count 1}])) items)
         :world/chunks {[0 0] (world/column {})}))

(deftest each-row-collects-what-its-blocks-drop
  (is (= #{(blocks/items :cobblestone)} (stone/drops-of-row (row :mine-stone))))
  (is (= #{(blocks/items :coal)} (stone/drops-of-row (row :mine-coal))))
  (is (= #{(blocks/items :raw_iron)} (stone/drops-of-row (row :mine-iron))))
  (is (= #{(blocks/items :gravel) (blocks/items :flint)} (stone/drops-of-row (row :dig-gravel)))
      "gravel's loot table: flint one time in ten, else gravel")
  (is (= (stone/states-of-row (row :mine-stone)) (stone/states-of-row {})) "stone by default"))

(deftest the-tool-a-row-needs
  (is (= {:plan/wait :no-pickaxe} (stone/mine-next (standing 64) (row :mine-iron))))
  (is (= {:plan/wait :no-pickaxe} (stone/mine-next (standing 64 :wooden_pickaxe) (row :mine-iron)))
      "iron ore drops only to stone or better")
  (is (= :stairs-down (:intent/kind (stone/mine-next (standing 64) (row :dig-gravel))))
      "gravel drops to a hand"))

(deftest with-nothing-in-sight-the-stairs-stop-at-the-rows-depth
  (testing "stairs: six down at a time"
    (is (= {:intent/kind :stairs-down :intent/to-y 58}
           (stone/mine-next (standing 64 :stone_pickaxe) (row :mine-iron)))))
  (testing "never below the row's depth"
    (is (= {:intent/kind :stairs-down :intent/to-y 0}
           (stone/mine-next (standing 4 :stone_pickaxe) (row :mine-iron))))
    (is (= {:plan/wait :none-in-sight}
           (stone/mine-next (standing 0 :stone_pickaxe) (row :mine-iron)))))
  (testing "stone has no depth: always down"
    (is (= {:intent/kind :stairs-down :intent/to-y -40}
           (stone/mine-next (standing -34 :wooden_pickaxe) (row :mine-stone))))))
