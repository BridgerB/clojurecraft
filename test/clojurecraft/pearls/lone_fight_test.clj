(ns clojurecraft.pearls.lone-fight-test
  "A lone blaze or wither skeleton at hand is fought, never covered from; two of a kind are
   covered from."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojurecraft.fixtures :as fx]
            [clojurecraft.pearls-support :as ps]
            [clojurecraft.pearls.cover :as pcover]
            [clojurecraft.pearls.params :as pparams]
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

(defn baby
  "A baby hoglin at pos."
  [pos]
  (assoc (ps/mob :hoglin pos) :entity/baby? true :entity/health 40.0))

(deftest a-baby-hoglin-in-the-burrow-is-fought-at-or-over-danger-health-and-left-under-it
  ;; real Clj_r09 (botlogs 2026-11-09-15, 42627258462-r1-b2): a baby hoglin one block off in the
  ;; burrow, 0.22 a hit, 54 hits in 59 s from 12.1 health to 0, the bot on :lookout
  ;; (hunt/wall-in-burrow), 0 swings
  (let [w (ps/burrowed [8.5 61.0 8.5] {9 (baby [7.5 61.0 8.5])})
        at-12 (assoc w :player/health 12.1 :player/attacker 9)
        i (plan/next-intent at-12 ps/row plan/goals)]
    (is (= :baby_hoglin (pcover/foe-kind (baby [7.5 61.0 8.5]))))
    (is (= :hoglin (pcover/foe-kind (ps/mob :hoglin [7.5 61.0 8.5]))))
    (is (= :fight (:intent/kind i)) "at 12.1 health it is fought")
    (is (= 9 (:intent/target i)))
    (is (true? (:intent/hold? i)) "held where the bot stands")
    (is (true? (:intent/yield? i)) "and broken off under retreat-health")
    (testing "under danger-health it is never fought (#577): a sealed tunnel away"
      (let [hurt (assoc at-12 :player/health 8.2)
            j (plan/next-intent hurt ps/row plan/goals)]
        (is (nil? (pcover/lone-fight hurt)))
        (is (= :tunnel-to (:intent/kind j)))
        (is (true? (:intent/seal-behind? j)))
        (is (= :back-off
               (:intent/kind (pcover/leave-baby
                              (assoc hurt :player/inventory {0 (ps/item :iron_sword 1)}))))
            "with no pickaxe, a back-off")))))

(deftest a-hoglin-at-hand-is-fought-ahead-of-the-refuge
  ;; real Clj_r03 (botlogs 2026-11-09-14, 42628418702-r6-b1): :pocket-hold (hunt/blaze-refuge) at
  ;; 16.5 health with a baby hoglin half a block off, 62 hits in 60 s, 0 swings; real Clj_r05 and
  ;; others: one adult hoglin, cover rows and back-offs, 0 swings
  (let [lone (kit (ps/standing {9 (baby [9.0 64.0 8.5])}))
        adult (kit (ps/standing {9 (ps/mob :hoglin [9.5 64.0 8.5])}))]
    (is (= :fight (:intent/kind (pcover/lone-fight lone (pcover/over-refuge pparams/at-hand))))
        "a baby is fought by the row ahead of the refuge")
    (is (= :fight (:intent/kind (pcover/lone-fight adult (pcover/over-refuge pparams/at-hand))))
        "a lone adult too")
    (is (= #{:hoglin :baby_hoglin} (set (keys (pcover/over-refuge pparams/at-hand))))
        "only the hoglins go ahead of the refuge")
    (testing "two adults are a pack: never fought, covered from as before"
      (is (nil? (pcover/lone-fight (kit (ps/standing {9 (ps/mob :hoglin [9.5 64.0 8.5])
                                                      10 (ps/mob :hoglin [8.5 64.0 13.5])}))))))
    (testing "under danger-health an adult is not fought and not left as a baby is"
      (let [hurt (assoc adult :player/health 8.0)]
        (is (nil? (pcover/lone-fight hurt)))
        (is (nil? (pcover/leave-baby hurt)))))))
