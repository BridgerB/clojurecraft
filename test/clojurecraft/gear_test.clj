(ns clojurecraft.gear-test
  "The gear a bot takes into the Nether (issue #262): iron sword, helmet, chestplate and leggings,
   and its raw meat cooked; after a death, again."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojurecraft.armour :as armour]
            [clojurecraft.blocks :as blocks]
            [clojurecraft.eat :as eat]
            [clojurecraft.fixtures :as fx]
            [clojurecraft.fortress :as fortress]
            [clojurecraft.game :as game]
            [clojurecraft.gear :as gear]
            [clojurecraft.memory :as memory]
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
  (testing "before the portal kit: not wanted yet, and not done either (it would be reached)"
    (is (not (gear/wanted? (standing {} #{:iron-kit}))))
    (is (not (plan/done-by (standing {} #{:iron-kit}) (row :gear)))))
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

(deftest the-gear-step-is-not-reached-before-it-is-worked
  ;; sim R1/1 (probe 40779151074): :gear was done-by true until the portal kit, and plan/reach
  ;; marks a chain target reached the moment it is done, for the run: the step never ran, and the
  ;; bot entered the Nether with a stone sword and no armour
  (let [w (standing {} #{})]
    (is (not (contains? (:plan/reached (plan/reach w plan/goals)) :gear)))
    (is (contains? (:plan/reached (plan/reach (standing (merge kit
                                                               {3 (item :iron_helmet 1)
                                                                4 (item :iron_chestplate 1)
                                                                5 (item :iron_leggings 1)
                                                                6 (item :iron_sword 1)}))
                                              plan/goals))
                   :gear)
        "reached once it is held")))

(deftest each-life-has-its-own-gear-time
  ;; probe 40779151074: the second death came 63 minutes after the portal kit, gear-ms gone, so
  ;; the regear never began and the bot went back with a stone sword
  (let [late (+ 100 gear/gear-ms 100000)
        w (assoc (standing {} #{:portal-kit :gear :nether})
                 :plan/since 0
                 :plan/reached-after {:portal-kit 100}
                 :time/now late)]
    (is (gear/expired? w))
    (is (not (gear/wanted? w)) "the first life's time is gone")
    (let [dead (memory/remember-answer w {:answer/kind :death})]
      (is (not (gear/expired? dead)))
      (is (gear/wanted? dead) "the new life's time has just begun")
      (is (not (plan/done-by dead (row :regear))))
      (is (gear/expired? (assoc dead :time/now (+ late gear/gear-ms)))))))

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

(deftest the-way-back-waits-for-health-and-works-for-it
  ;; sim R1/1 (probe 40787504601, t 18078400): into the Nether at 7.9 health, food 14, edible 0,
  ;; dead at t 19370500. No timer ends the wait: the meal, the hunt and the rest are its work
  (let [back (fn [inv entities health food]
               (-> (standing inv #{:portal-kit :gear :nether})
                   (assoc :player/health health :player/food food :world/entities entities)))
        row-of (fn [w] (:goal/id (plan/choose w plan/goals)))]
    (is (not (fortress/ready-for-nether? (back {} {} 7.92 14))))
    (is (fortress/ready-for-nether? (back {} {} 18.5 20)))
    (testing "food held: the meal first, up to food 18"
      (let [w (back {3 (item :bread 4)} {} 8.0 14)]
        (is (eat/hungry? w))
        (is (= :eat (row-of w)))))
    (testing "no food held, an animal in sight: the hunt, not the portal"
      (let [cow {:entity/type (blocks/entity-types :cow)
                 :entity/pos [11.5 64.0 8.5]
                 :entity/seen-at 0
                 :entity/health 10.0}
            w (assoc (back {} {5 cow} 8.0 14) :time/tick 0)]
        (is (= :hunt (row-of w)))))
    (testing "fed, nothing to hunt: a rest while the food heals"
      (let [w (back {3 (item :bread 4)} {} 8.0 20)]
        (is (= {:intent/kind :rest} (plan/next-intent w (row :return-nether) plan/goals)))))
    (testing "no food, none to be had: the named wait, not the portal"
      (let [w (back {} {} 8.0 14)]
        (is (= {:plan/wait :no-food-to-return}
               (plan/next-intent w (row :return-nether) plan/goals)))))
    (testing "healed: the way back is the build's"
      (is (not= {:plan/wait :no-food-to-return}
                (plan/next-intent (back {} {} 19.0 20) (row :return-nether) plan/goals))))))

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
