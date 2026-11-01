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
  (testing "a stand no walk reaches is tunnelled to"
    (is (= {:intent/kind :tunnel-to :intent/target [4 5 6] :expert/why :approach}
           (expert/choose (sit {:phase :approach
                                :tunnel {:intent/kind :tunnel-to :intent/target [4 5 6]}})))))
  (testing "every stand tried and no pocket is a live stop"
    (let [act (expert/choose (sit {:phase :approach :approach 6}))]
      (is (:expert/stop act))
      (is (= :no-pocket (:expert/why act)))
      (is (not (:expert/done act)))))
  (testing "two idle blazes open on the nearest, and the open window hits that one"
    (is (= :unseal
           (:intent/kind (expert/choose (sit {:holding :stone_pickaxe
                                              :blazes [(blaze {}) (blaze {:eid 2 :dist 8.0})]})))))
    (let [act (expert/choose (sit {:sealed? false
                                   :blazes [(blaze {:eid 4 :dist 2.6 :box 2.4})
                                            (blaze {:eid 9 :dist 3.0 :box 2.8})]}))]
      (is (= :fight (:intent/kind act)))
      (is (= 4 (:intent/target act)))))
  (testing "three blazes that would see keep the window shut"
    (is (= {:expert/why :wait}
           (expert/choose (sit {:holding :stone_pickaxe
                                :blazes [(blaze {}) (blaze {:eid 2 :dist 7.0})
                                         (blaze {:eid 3 :dist 7.5})]})))))
  (testing "a hit reseals an open window"
    (is (= :seal
           (:intent/kind (expert/choose (sit {:sealed? false
                                              :struck? true
                                              :blazes [(blaze {:dist 2.6 :box 2.4})]}))))))
  (testing "a volley that starts charging out of the band reseals"
    (is (= :seal
           (:intent/kind (expert/choose
                          (sit {:sealed? false
                                :blazes [(blaze {:dist 6.6 :shot-step 1 :shot-at 5000})]}))))))
  (testing "an early charge already in the band is still a hit"
    (let [act (expert/choose (sit {:sealed? false
                                   :blazes [(blaze
                                             {:dist 2.6 :box 2.4 :shot-step 1 :shot-at 5000})]}))]
      (is (= :fight (:intent/kind act)))
      (is (:intent/hold? act))))
  (testing "a blaze outside the band is peeked"
    (let [act (expert/choose
               (sit {:holding :stone_pickaxe :blazes [(blaze {:dist 12.0})] :peek-cell [1 2 3]}))]
      (is (= :unseal (:intent/kind act)))
      (is (= :peek (:expert/why act)))))
  (testing "an open window with nobody that would see steps out to the opening"
    (is (= {:intent/kind :walk :intent/target [8 64 4] :intent/for :cover :expert/why :peek}
           (expert/choose
            (sit {:sealed? false :blazes [(blaze {:see? false :dist 8.0})] :peek-cell [8 64 4]})))))
  (testing "an unseal whose blaze has stopped closing is aborted before the block opens"
    ;; ten ticks in, the blaze has not closed: it would still be at 6.6 when the block broke
    (is (= :seal
           (:intent/kind (expert/choose (sit {:holding :stone_pickaxe
                                              :unseal-at 0
                                              :unseal-dist 6.6
                                              :now 500
                                              :blazes [(blaze {})]}))))))
  (testing "a sealed hold with no seer waits out the first 20 s"
    (is (= {:expert/why :wait}
           (expert/choose (sit {:holding :stone_pickaxe
                                :blazes [(blaze {:see? false :dist 12.0})]
                                :peek-cell [1 2 3]
                                :zero-since 0
                                :now 0})))))
  (testing "20 s at 0 seers steps one cell toward the blaze"
    (let [act (expert/choose (sit {:holding :stone_pickaxe
                                   :blazes [(blaze {:see? false :dist 12.0})]
                                   :zero-since 0
                                   :now 20000
                                   :step-cell [9 64 4]
                                   :feet [8 64 4]
                                   :peek-cell [8 64 4]}))]
      (is (= :unseal (:intent/kind act)))
      (is (= :peek-step (:expert/why act)))))
  (testing "an open step walks the one cell, not the old opening"
    (is (= {:intent/kind :walk :intent/target [9 64 4] :intent/for :cover :expert/why :peek-step}
           (expert/choose (sit {:sealed? false
                                :blazes [(blaze {:see? false :dist 12.0})]
                                :zero-since 0
                                :now 20000
                                :step-cell [9 64 4]
                                :feet [8 64 4]
                                :peek-cell [8 64 4]})))))
  (testing "a hit while stepped out walks home, and the walk continues after the hit"
    (is (= {:intent/kind :walk :intent/target [1 64 1] :intent/for :cover :expert/why :return}
           (expert/choose (sit {:sealed? false
                                :struck? true
                                :away? true
                                :home [1 64 1]
                                :blazes [(blaze {:dist 2.6 :box 2.4})]}))))
    (is (= {:intent/kind :walk :intent/target [1 64 1] :intent/for :cover :expert/why :return}
           (expert/choose (sit {:sealed? false
                                :returning? true
                                :away? true
                                :home [1 64 1]
                                :blazes [(blaze {:see? false :dist 8.0})]})))))
  (testing "20 s outside with no line walks home"
    (is (= {:intent/kind :walk :intent/target [1 64 1] :intent/for :cover :expert/why :return}
           (expert/choose (sit {:sealed? false
                                :away? true
                                :home [1 64 1]
                                :out-since 0
                                :now 20000
                                :zero-since 0
                                :blazes [(blaze {:see? false :dist 12.0})]
                                :peek-cell [8 64 4]})))))
  (testing "a carve fills a side once the dig is open"
    (is (= {:intent/kind :place :intent/item :cobblestone :intent/cell [5 5 6] :expert/why :carve}
           (expert/choose (sit {:phase :carve
                                :place-cell [5 5 6]
                                :place-item :cobblestone
                                :holding :stone_pickaxe
                                :pick-hotbar? true})))))
  (testing "a carve digs the solid cell, the pick in hand"
    (is (= {:intent/kind :dig :intent/target [4 5 6] :expert/why :carve}
           (expert/choose
            (sit {:phase :carve :dig-cell [4 5 6] :holding :stone_pickaxe :pick-hotbar? true}))))
    (is (= {:intent/kind :equip :intent/item :stone_pickaxe :expert/why :arm-pick}
           (expert/choose
            (sit {:phase :carve :dig-cell [4 5 6] :holding :iron_sword :pick-hotbar? true}))))))
