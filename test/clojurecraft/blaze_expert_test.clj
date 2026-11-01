(ns clojurecraft.blaze-expert-test
  "The expert's policy, as a pure function of a situation (issue #262). No sim is played here:
   the fortress runs are the probe."
  (:require [clojure.test :refer [deftest is testing]]
            [clojurecraft.blaze-expert :as expert]))

(defn- sit
  "A quiet hold at full health. A test overrides the keys it cares about."
  [m]
  (merge {:rods 0
          :health 20.0
          :food 20
          :holding :iron_sword
          :phase :hold
          :sealed? true
          :can-seal? true
          :wearing #{:iron_helmet :iron_chestplate :iron_leggings :golden_boots}
          :blazes []
          :fireballs? false
          :melee? false
          :break-ms 1000
          :now 0
          :approach 0
          :approaches 6}
         m))

(defn- blaze
  "One blaze the window would show."
  [m]
  (merge {:eid 1 :dist 6.6 :box 6.0 :melee? false :shot-step 0 :shot-at 0 :see? true} m))

(deftest the-policy
  (testing "seven rods at full health and quiet is the exit"
    (is (= :done (:expert/why (expert/choose (sit {:rods 7})))))
    (is (:expert/done (expert/choose (sit {:rods 7})))))
  (testing "seven rods under full health is a meal, not the exit"
    (let [act (expert/choose (sit {:rods 7 :health 15.0 :food 16}))]
      (is (not (:expert/done act)))
      (is (= :eat (:intent/kind act)))
      (is (= :cooked_beef (:intent/item act)))))
  (testing "the pick goes onto the hotbar before the sword"
    (is (= {:intent/kind :equip :intent/item :stone_pickaxe :expert/why :arm-pick}
           (expert/choose (sit {:phase :arm :holding nil})))))
  (testing "the sword goes onto the hotbar once the pick is there, even while the pick is in hand"
    (is (= {:intent/kind :equip :intent/item :iron_sword :expert/why :arm-sword}
           (expert/choose (sit {:phase :arm :holding :stone_pickaxe :pick-hotbar? true})))))
  (testing "both tools on the hotbar is not another equip"
    (is (= {:expert/why :wait}
           (expert/choose (sit {:phase :arm :pick-hotbar? true :sword-hotbar? true})))))
  (testing "armour goes on before anything else"
    (is (= {:intent/kind :wear :intent/item :iron_helmet :expert/why :wear}
           (expert/choose (sit {:phase :wear :wearing #{}})))))
  (testing "fire in an open pocket is sealed"
    (is (= :seal
           (:intent/kind (expert/choose (sit {:on-fire? true :sealed? false :phase :hold}))))))
  (testing "a blaze already firing is sealed, not opened on"
    (is (= :seal
           (:intent/kind (expert/choose (sit {:sealed? false
                                              :blazes [(blaze
                                                        {:shot-step 2 :dist 2.6 :box 2.4})]}))))))
  (testing "a sealed idle blaze that lands in the band is opened, pick in hand"
    ;; break-ms 1000 is 20 ticks; at 0.2 a tick the blaze closes 4.0, so 6.6 arrives at 2.6
    (let [act (expert/choose (sit {:holding :stone_pickaxe :blazes [(blaze {})]}))]
      (is (= :unseal (:intent/kind act)))
      (is (= :unseal (:expert/why act)))))
  (testing "an open blaze standing in the band is hit, and the bot holds its ground"
    (let [act (expert/choose
               (sit {:sealed? false :holding :iron_sword :blazes [(blaze {:dist 2.6 :box 2.4})]}))]
      (is (= :fight (:intent/kind act)))
      (is (= 1 (:intent/target act)))
      (is (:intent/hold? act))))
  (testing "a mob already in reach, through the wall, is left by the back cell"
    (is (= {:intent/kind :dig :intent/target [1 2 3] :expert/why :flee}
           (expert/choose (sit {:melee? true :back [1 2 3]})))))
  (testing "the walk in is a walk to the stand"
    (is (= {:intent/kind :walk :intent/target [4 5 6] :intent/for :fight :expert/why :approach}
           (expert/choose (sit {:phase :approach :walk-to [4 5 6]})))))
  (testing "every stand tried and no pocket is a live stop"
    (let [act (expert/choose (sit {:phase :approach :approach 6}))]
      (is (:expert/stop act))
      (is (= :no-pocket (:expert/why act)))
      (is (not (:expert/done act)))))
  (testing "an unseal whose blaze has stopped closing is aborted before the block opens"
    ;; ten ticks in, the blaze has not closed: it would still be at 6.6 when the block broke
    (is (= :seal
           (:intent/kind (expert/choose (sit {:holding :stone_pickaxe
                                              :unseal-at 0
                                              :unseal-dist 6.6
                                              :now 500
                                              :blazes [(blaze {})]})))))))
