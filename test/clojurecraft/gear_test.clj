(ns clojurecraft.gear-test
  "The gear a bot takes into the Nether (issue #262): iron sword, helmet, chestplate and leggings,
   and its raw meat cooked; after a death, again."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojurecraft.armour :as armour]
            [clojurecraft.fixtures :as fx]
            [clojurecraft.game :as game]
            [clojurecraft.gear :as gear]
            [clojurecraft.plan :as plan]
            [clojurecraft.recipe :as recipe]
            [clojurecraft.world :as world]))

(use-fixtures :once fx/instrumented)

(defn item "A stack of n of the item named k." [k n] {:item (recipe/item-id k) :count n})

(def kit {0 (item :flint_and_steel 1) 1 (item :water_bucket 1) 2 (item :bucket 1)})

(defn standing
  "A bot in a chained run in the overworld, holding inv and a table, the portal kit reached
   unless reached says otherwise."
  ([inv] (standing inv #{:portal-kit}))
  ([inv reached]
   (-> (game/init fx/opts)
       (assoc :bot/phase :play
              :player/pos [8.5 64.0 8.5]
              :player/loaded? true
              :player/on-ground? true
              :plan/chain? true
              :plan/reached reached
              :time/now 1000
              :world/chunks {[0 0] (world/column {})}
              :player/inventory (merge {8 (item :crafting_table 1)} inv)))))

(defn row "The goal row named id." [id] (first (filter #(= id (:goal/id %)) plan/goals)))

(deftest the-gear-is-a-sword-and-three-pieces-of-armour
  (is (= [[:iron_helmet 5] [:iron_chestplate 8] [:iron_leggings 7] [:iron_sword 2]] (gear/pieces)))
  (is (= 22 (gear/gear-cost (standing kit))))
  (is (= 15
         (gear/gear-cost (standing (merge kit {3 (item :iron_helmet 1) 4 (item :iron_sword 1)})))))
  (is (zero? (gear/gear-cost (standing (merge kit
                                              {3 (item :iron_helmet 1)
                                               4 (item :iron_chestplate 1)
                                               5 (item :iron_leggings 1)
                                               6 (item :iron_sword 1)}))))))

(deftest the-gear-step-is-wanted-after-the-portal-kit-in-a-chain
  (testing "the portal kit reached, nothing worn: the step is undone"
    (is (gear/wanted? (standing kit)))
    (is (not (plan/done-by (standing kit) (row :gear)))))
  (testing "before the portal kit: not yet"
    (is (not (gear/wanted? (standing {} #{:iron-kit}))))
    (is (plan/done-by (standing {} #{:iron-kit}) (row :gear))))
  (testing "all of it held: done"
    (is (plan/done-by (standing (merge kit
                                       {3 (item :iron_helmet 1)
                                        4 (item :iron_chestplate 1)
                                        5 (item :iron_leggings 1)
                                        6 (item :iron_sword 1)}))
                      (row :gear))))
  (testing "a gym stage (no chain) never makes it"
    (is (plan/done-by (assoc (standing kit) :plan/chain? false) (row :gear))))
  (testing "gear-ms after the portal kit it is given up"
    (let [w (assoc (standing kit)
                   :plan/since 0
                   :plan/reached-after {:portal-kit 100}
                   :time/now (+ 100 gear/gear-ms 1))]
      (is (not (gear/wanted? w)))
      (is (gear/wanted? (assoc w :time/now 5000))))))

(deftest the-gear-step-mines-the-iron-it-lacks-and-makes-the-piece-it-can
  (let [none (standing kit)]
    (is (some? (plan/next-intent none (row :gear) plan/goals)) "no iron held: the iron is gone for")
    (is (not= {:plan/wait :stuck} (plan/next-intent none (row :gear) plan/goals))))
  (testing "ingots enough for the armour: the armour row makes the helmet"
    (let [w (standing (merge kit {3 (item :iron_ingot 22)}))]
      (is (= :iron_helmet (armour/wanted w)))
      (is (not (plan/done-by w (row :armour)))))))

(deftest after-a-death-the-gear-is-made-again
  ;; sim R1/1 (probe 40761589784): every life after the first went back to the blazes with a
  ;; stone sword and nothing worn, the kit and the gear gone with the first death
  (let [back (standing {} #{:wood :portal-kit :gear :nether})]
    (is (not (plan/done-by back (row :regear))) "gear reached once, lost: made again")
    (is (plan/done-by (standing {} #{:portal-kit}) (row :regear))
        "the first gear is :gear's, not this row's")
    (testing "the kit is gone but the Nether was reached: every ingot is spare"
      (is (= 5 (armour/spare-ingots (standing {3 (item :iron_ingot 5)} #{:nether}))))
      (is (zero? (armour/spare-ingots (standing {3 (item :iron_ingot 5)} #{})))))))

(deftest raw-meat-is-cooked-after-the-portal-kit
  (let [w (standing (merge kit {3 (item :beef 6) 4 (item :porkchop 2)}))]
    (is (= [:beef :cooked_beef 6] (gear/raw-held w)) "the kind held most")
    (is (gear/cook-wanted? w))
    (is (not (plan/done-by w (row :cook))))
    (is (some? (plan/next-intent w (row :cook) plan/goals)))
    (testing "none raw: nothing to cook"
      (is (plan/done-by (standing (merge kit {3 (item :cooked_beef 6)})) (row :cook))))
    (testing "before the portal kit: not yet"
      (is (plan/done-by (standing {3 (item :beef 6)} #{:iron-kit}) (row :cook))))))
