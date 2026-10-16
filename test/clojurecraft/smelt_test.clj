(ns clojurecraft.smelt-test
  "The :smelt intent's pure parts: what it burns, in what order, how big a batch the held fuel
   cooks, and the clicks that lay a stack into a furnace slot; and its cook stage, a tick at a
   time against a furnace window as the server shows it."
  (:require [clojure.test :refer [deftest is testing]]
            [clojurecraft.fixtures :as fx]
            [clojurecraft.game :as game]
            [clojurecraft.intent :as intent]
            [clojurecraft.inventory :as inventory]
            [clojurecraft.recipe :as recipe]
            [clojurecraft.smelt :as smelt]))

(def iron (recipe/smelt-by-id :iron_ingot_from_smelting_raw_iron))
(def charcoal (recipe/smelt-by-id :charcoal))

(deftest coal-burns-first-then-planks-never-sticks
  (is (= #{:coal :charcoal} (first smelt/burn-order)))
  (is (contains? (last smelt/burn-order) :oak_planks))
  (is (not-any? #(contains? % :stick) smelt/burn-order) "a stick is a pickaxe's handle (issue #36)")
  (is (not-any? #(contains? % :crimson_planks) smelt/burn-order) "the game does not burn it")
  (is (not-any? #(contains? % :crafting_table) smelt/burn-order) "a table burns, but is kept")
  (is (not-any? #(contains? % :oak_log) smelt/burn-order) "a log is worth four planks")
  (is (= [[:coal 1] [:oak_planks 2]]
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
  (let [v {:view/slot-of #(inventory/player->window-slot (inventory/layouts :furnace) %)}
        in-furnace #(inventory/slot-in :furnace %)]
    (is (= [(inventory/left-click (in-furnace [:hotbar 0])) (inventory/left-click smelt/fuel-slot)]
           (smelt/lay v 0 1 smelt/fuel-slot 1))
        "all of it: pick it up, put it down")
    (is (= [(inventory/left-click (in-furnace [:main 0])) (inventory/right-click smelt/fuel-slot)
            (inventory/right-click smelt/fuel-slot) (inventory/left-click (in-furnace [:main 0]))]
           (smelt/lay v 9 5 smelt/fuel-slot 2))
        "two of five: two right clicks, then the rest back where it was")))

(defn cooking
  "A smelt of three raw iron in its :cook stage at ms now, its deadline at 35000, against an open
   furnace whose own slots are slots and whose fire has lit ticks left."
  [now slots lit]
  (-> (game/init fx/opts)
      (assoc :bot/phase :play
             :time/now now
             :window/open
             {:window/id 11
              :window/menu-type (:menu-type (inventory/layouts :furnace))
              :window/state-id 12
              :window/slots slots
              :window/data {(get-in (inventory/layouts :furnace) [:properties :lit]) lit}}
             :plan/intent
             {:intent/kind :smelt
              :intent/status :active
              :intent/smelt :iron_ingot_from_smelting_raw_iron
              :intent/count 3
              :intent/stage :cook
              :intent/since 0
              :intent/makes 3
              :intent/result (recipe/item-id :iron_ingot)
              :intent/before 0
              :intent/cook-by 35000})))

(defn cooked
  "The smelt intent after one tick of it."
  [w]
  (:plan/intent (intent/run w (:plan/intent w) {:event/kind :tick :event/now (:time/now w)})))

(deftest a-furnace-still-cooking-past-the-deadline-is-waited-on
  ;; race 38805920624 runner 5 (issues #199, #200): a server at 11 ticks a second cooked three
  ;; raw iron in 53 s; the 35 s deadline failed the smelt :not-cooked 1.2 s before the second
  ;; ingot, and the furnace it left open failed the next swaps into the hand (issues #131, #132)
  (let [raw (recipe/item-id :raw_iron)
        ingot (recipe/item-id :iron_ingot)
        half-done {smelt/input-slot {:item raw :count 2} smelt/output-slot {:item ingot :count 1}}]
    (is (= :active (:intent/status (cooked (cooking 36200 half-done 300))))
        "the fire burns and the input is in it: the cook goes on past the deadline")
    (is (= :active (:intent/status (cooked (cooking 20000 half-done 300)))) "before it, as ever")
    (testing "a furnace that stopped cooking still fails the smelt past the deadline"
      (let [i (cooked (cooking 36200 {smelt/output-slot {:item ingot :count 1}} 300))]
        (is (intent/failed? i) "the input is gone")
        (is (= :not-cooked (:intent/reason i)))))))
