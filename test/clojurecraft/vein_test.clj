(ns clojurecraft.vein-test
  "Taking the whole of an iron vein while the chain still wants iron (flag #1, 2026-10-31)."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [clojurecraft.blocks :as blocks]
            [clojurecraft.fixtures :as fx]
            [clojurecraft.game :as game]
            [clojurecraft.plan :as plan]
            [clojurecraft.vein :as vein]
            [clojurecraft.world :as world]))

(use-fixtures :once fx/instrumented)

(defn row [id] (first (filter #(= id (:goal/id %)) plan/goals)))

(defn standing
  "On a bare stone floor with feet at y, holding nothing."
  [y]
  (assoc (game/init fx/opts)
         :bot/phase :play
         :player/pos [5.5 (double y) 5.5]
         :player/loaded? true
         :player/on-ground? true
         :player/inventory {}
         :world/chunks {[0 0] (world/column {})}))

(defn holding
  "w with the hotbar holding the items given as {name count}."
  [w items]
  (assoc w
         :player/inventory
         (into {} (map-indexed (fn [i [n c]] [i {:item (blocks/items n) :count c}])) items)))

(deftest the-chain-wants-all-the-iron-it-has-not-made
  ;; flag #1 (Bridger, local viewer, 2026-10-31): four raw iron held, the vein not finished
  (is (= 29 (vein/iron-want {})) "flint and steel 1, two buckets 6, sword 2, armour 20")
  (is (= 25 (vein/iron-want {:raw_iron 4})) "the four raw iron held are counted")
  (is (= 21 (vein/iron-want {:raw_iron 4 :iron_ingot 4})))
  (is (= 20 (vein/iron-want {:bucket 1 :water_bucket 1 :flint_and_steel 1 :iron_sword 1}))
      "the armour is all that is left")
  (is (= 23 (vein/iron-want {:bucket 1 :flint_and_steel 1})) "a bucket short: three more")
  (is (zero? (vein/iron-want {:bucket 2
                              :flint_and_steel 1
                              :iron_sword 1
                              :iron_helmet 1
                              :iron_chestplate 1
                              :iron_leggings 1})))
  (is (zero? (vein/iron-want {:iron_ingot 40})) "never negative"))

(deftest the-whole-vein-is-taken-before-moving-on
  ;; flag #1: at -864 41 329 with four raw iron and a stone pickaxe, ore still in sight
  (let [ore [8 64 5]
        base (assoc (standing 64)
                    :world/chunks
                    {[0 0] (world/column {ore (blocks/default-state :iron_ore)})})
        w (holding base {:stone_pickaxe 1 :raw_iron 4})]
    (is (= {:intent/kind :walk :intent/target ore :intent/for :mine-iron :intent/pickup? true}
           (vein/vein-next w))
        "ore in reach and iron still wanted: walk to it")
    (is (= ore (:intent/target (plan/next-intent w (row :ingots) plan/goals)))
        "the :ingots row's four are met by the raw iron held, and the ore is taken anyway")
    (is (nil? (vein/vein-next (holding base {:stone_pickaxe 1})))
        "no iron mined yet: the row's own walk")
    (is (nil? (vein/vein-next (holding base {:stone_pickaxe 1 :raw_iron 4 :iron_ingot 25})))
        "the chain's iron is held: move on")
    (is (nil? (vein/vein-next (assoc w :world/chunks {[0 0] (world/column {})})))
        "no ore in sight: nothing")
    (is (nil? (vein/vein-next (assoc w
                                     :world/chunks
                                     {[0 0] (world/column {[15 64 5] (blocks/default-state
                                                                      :iron_ore)})})))
        "ore beyond vein-radius is left to the row")))
