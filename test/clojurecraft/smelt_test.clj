(ns clojurecraft.smelt-test
  "The :smelt intent's pure parts: what it burns, in what order, how big a batch the held fuel
   cooks, and the clicks that lay a stack into a furnace slot."
  (:require [clojure.test :refer [deftest is]]
            [clojurecraft.inventory :as inventory]
            [clojurecraft.recipe :as recipe]
            [clojurecraft.smelt :as smelt]))

(def iron (recipe/smelt-by-id :iron_ingot_from_smelting_raw_iron))
(def charcoal (recipe/smelt-by-id :charcoal))

(deftest coal-burns-first-then-planks-and-sticks
  (is (= #{:coal :charcoal} (first smelt/burn-order)))
  (is (= #{:stick} (last smelt/burn-order)))
  (is (not-any? #(contains? % :crimson_planks) smelt/burn-order) "the game does not burn it")
  (is (not-any? #(contains? % :crafting_table) smelt/burn-order) "a table burns, but is kept")
  (is (not-any? #(contains? % :oak_log) smelt/burn-order) "a log is worth four planks")
  (is (= [[:coal 1] [:oak_planks 2] [:stick 3]]
         (smelt/fuels {:stick 3 :oak_planks 2 :coal 1 :wooden_pickaxe 1} :raw_iron))))

(deftest a-batch-is-what-the-held-fuel-cooks
  (is (= 4 (smelt/batch {:raw_iron 4 :coal 1} iron)))
  (is (= 8 (smelt/batch {:raw_iron 10 :coal 1} iron)) "one coal cooks eight")
  (is (= 1 (smelt/batch {:raw_iron 3 :oak_planks 1} iron)) "a plank, one and a half")
  (is (= 0 (smelt/batch {:raw_iron 2} iron)) "no fuel")
  (is (= 0 (smelt/batch {:coal 5} iron)) "no input")
  (is (= 0 (smelt/batch {:oak_log 3} charcoal)) "the input is never its own fuel")
  (is (= 3 (smelt/batch {:oak_log 3 :oak_planks 2} charcoal))))

(deftest a-stack-is-laid-whole-or-one-at-a-time
  (let [v {:view/slot-of #(inventory/player->window-slot (inventory/layouts :furnace) %)}]
    (is (= [{:click/slot 30 :click/button 0 :click/mode 0}
            {:click/slot 1 :click/button 0 :click/mode 0}]
           (smelt/lay v 0 1 1 1))
        "all of it: pick it up, put it down")
    (is (= [{:click/slot 3 :click/button 0 :click/mode 0}
            {:click/slot 1 :click/button 1 :click/mode 0}
            {:click/slot 1 :click/button 1 :click/mode 0}
            {:click/slot 3 :click/button 0 :click/mode 0}]
           (smelt/lay v 9 5 1 2))
        "two of five: two right clicks, then the rest back where it was")))
