(ns clojurecraft.pearls.lone-fight-test
  "A lone blaze or wither skeleton at hand is fought, never covered from; two of a kind are
   covered from."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojurecraft.fixtures :as fx]
            [clojurecraft.pearls-support :as ps]
            [clojurecraft.pearls.cover :as pcover]
            [clojurecraft.plan :as plan]))

(use-fixtures :once fx/instrumented)

(defn kit
  "The world with the race kit's sword and a stack of cobblestone held."
  [w]
  (assoc w :player/inventory {0 (ps/item :iron_sword 1) 1 (ps/item :cobblestone 64)}))

(def cover-dig
  "The dig the real bots were in while a blaze at two blocks touched them (cover-from-shooter)."
  {:intent/kind :dig
   :intent/target [9 64 9]
   :intent/for :pearls
   :intent/goal :ender-pearls
   :intent/why :hunt/cover-from-shooter})

(deftest a-lone-blaze-at-hand-is-fought
  ;; two real bots dug and laid cover from one blaze at 1.3 to 2.9 blocks for 11 and 14 s,
  ;; 0 swings, and died of its touches
  (let [w (kit (ps/standing {5 (ps/mob :blaze [10.5 64.5 8.5])}))
        i (pcover/lone-fight w)]
    (is (= :fight (:intent/kind i)))
    (is (= 5 (:intent/target i)))
    (is (true? (:intent/yield? i)) "a blaze fight yields under retreat-health")
    (is (= :fight (:intent/kind (plan/next-intent w ps/row plan/goals)))
        "the hunt fights it before any cover")
    (is (plan/interrupts? w ps/row cover-dig) "a cover dig under way is cut for it")
    (is (not (plan/interrupts? w ps/row (assoc i :intent/goal :ender-pearls)))
        "the fight is not cut in turn")
    (testing "under danger-health it is never fought (#577)"
      (is (nil? (pcover/lone-fight (assoc w :player/health 8.0)))))
    (testing "two blazes are covered from, not fought"
      (is (nil? (pcover/lone-fight (kit (ps/standing {5 (ps/mob :blaze [10.5 64.5 8.5])
                                                      6 (ps/mob :blaze [8.5 65.5 13.5])}))))))
    (testing "one five blocks off is not at hand"
      (is (nil? (pcover/lone-fight (kit (ps/standing {5 (ps/mob :blaze [13.5 64.5 8.5])}))))))))

(deftest a-lone-wither-skeleton-at-hand-is-fought
  ;; one wither skeleton took a tunnelling sim bot from 15 to 0 in 4 s while it raised a pillar,
  ;; laid the same block three times and dug, never swinging
  (let [w (kit (ps/standing {7 (ps/mob :wither_skeleton [9.5 64.0 8.5])}))
        i (pcover/lone-fight w)]
    (is (= :fight (:intent/kind i)))
    (is (= 7 (:intent/target i)))
    (is (true? (:intent/hold? i)) "held where the bot stands")
    (is (true? (:intent/yield? i)) "and breaks off under retreat-health (#577)")
    (is (nil? (pcover/lone-fight (assoc w :player/health 15.2)))
        "begun only from 18: after the first strike, the cover")
    (is (= :fight (:intent/kind (plan/next-intent w ps/row plan/goals))))
    (testing "two wither skeletons are a pack: never fought"
      (is (nil? (pcover/lone-fight (kit (ps/standing {7 (ps/mob :wither_skeleton [9.5 64.0 8.5])
                                                      8 (ps/mob :wither_skeleton
                                                                [8.5 64.0 13.5])}))))))
    (testing "a lone zombified piglin is no kind of the table"
      (is (nil? (pcover/lone-fight (kit (ps/standing {7 (ps/mob :zombified_piglin
                                                                [9.5 64.0 8.5])}))))))))
