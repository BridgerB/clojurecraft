(ns clojurecraft.sword-test
  "The sword row (issue #259): the best sword the held items make without a trip, never from a
   log, iron only from ingots the armour leaves spare."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojurecraft.fixtures :as fx]
            [clojurecraft.game :as game]
            [clojurecraft.plan :as plan]
            [clojurecraft.recipe :as recipe]
            [clojurecraft.sword :as sword]
            [clojurecraft.world :as world]))

(use-fixtures :once fx/instrumented)

(defn item "A stack of n of the item named k." [k n] {:item (recipe/item-id k) :count n})

(defn standing
  "A bot in play on the stone floor of one column, holding inv (a table among it unless said)."
  [inv]
  (-> (game/init fx/opts)
      (assoc :bot/phase :play
             :player/pos [8.5 64.0 8.5]
             :player/loaded? true
             :player/on-ground? true
             :world/chunks {[0 0] (world/column {})}
             :player/inventory (merge {8 (item :crafting_table 1)} inv))))

(def kit {0 (item :flint_and_steel 1) 1 (item :water_bucket 1) 2 (item :bucket 1)})

(deftest the-best-sword-the-held-items-make-better-than-the-one-held
  (is (= :stone_sword (sword/wanted (standing {3 (item :cobblestone 5) 4 (item :stick 1)}))))
  (is (= :wooden_sword (sword/wanted (standing {3 (item :oak_planks 4)})))
      "two planks for the blade, two for the sticks")
  (is (nil? (sword/wanted (standing {3 (item :oak_planks 3)})))
      "three planks make no sword with no stick")
  (is (nil? (sword/wanted (standing {3 (item :oak_log 3) 4 (item :stick 2)}))) "never from a log")
  (is (nil? (sword/wanted (standing
                           {3 (item :cobblestone 5) 4 (item :stick 1) 5 (item :stone_sword 1)})))
      "a stone sword held is as good")
  (is (= :stone_sword
         (sword/wanted (standing
                        {3 (item :cobblestone 5) 4 (item :stick 1) 5 (item :wooden_sword 1)})))
      "a wooden one is not")
  (testing "no table at hand: nothing"
    (is (nil? (sword/wanted (assoc (standing {3 (item :cobblestone 5) 4 (item :stick 1)})
                                   :player/inventory
                                   {3 (item :cobblestone 5) 4 (item :stick 1)}))))))

(deftest iron-only-from-ingots-the-armour-leaves
  (testing "the armour still wants a helmet: the ingots are its"
    (is (= :stone_sword
           (sword/wanted
            (standing
             (merge kit {3 (item :iron_ingot 6) 4 (item :stick 1) 5 (item :cobblestone 2)}))))))
  (testing "helmet and chestplate held: two spare ingots make an iron sword"
    (is (= :iron_sword
           (sword/wanted (standing (merge kit
                                          {3 (item :iron_ingot 2)
                                           4 (item :stick 1)
                                           39 (item :iron_helmet 1)
                                           38 (item :iron_chestplate 1)})))))))

(deftest the-sword-row-is-a-needs-plan-for-the-piece
  (let [w (standing {3 (item :cobblestone 5) 4 (item :stick 1)})
        row (first (filter #(= :sword (:goal/id %)) plan/goals))]
    (is (not (plan/done-by w row)))
    (is (= :place (:intent/kind (plan/next-intent w row plan/goals))) "the table goes down first")))
