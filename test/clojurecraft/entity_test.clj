(ns clojurecraft.entity-test
  "Issue #258: the entities as values, and the questions a fight asks of them."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojurecraft.blocks :as blocks]
            [clojurecraft.entity :as entity]
            [clojurecraft.fixtures :as fx]
            [clojurecraft.observe]
            [clojurecraft.plan :as plan]))

(use-fixtures :once fx/instrumented)

(defn mob
  [kind pos & {:as more}]
  (merge {:entity/type (blocks/entity-types kind) :entity/pos pos} more))

(def world
  {:world/entities {1 (mob :zombie [10.0 64.0 0.0])
                    2 (mob :pig [1.0 64.0 0.0])
                    3 (mob :enderman [2.0 64.0 0.0])
                    4 (mob :blaze [5.0 64.0 0.0])
                    5 (mob :item [0.5 64.0 0.0])
                    6 (mob :skeleton [3.0 64.0 0.0] :entity/died-at 100)}})

(deftest hostiles-are-the-living-monsters-that-attack-on-sight
  (is (= #{1 4} (set (keys (entity/hostiles world))))
      "a pig is a creature, an enderman neutral, an item no mob, a dead skeleton dead")
  (is (entity/living? (mob :player [0.0 0.0 0.0])))
  (is (not (entity/living? (mob :item [0.0 0.0 0.0]))))
  (is (entity/item? (mob :item [0.0 0.0 0.0]))))

(deftest nearest-is-by-distance-within-a-radius
  (is (= 4 (first (entity/nearest (entity/hostiles world) [0.0 64.0 0.0]))))
  (is (= [4 (get-in world [:world/entities 4])]
         (entity/nearest (entity/hostiles world) [0.0 64.0 0.0] 5.0)))
  (is (nil? (entity/nearest (entity/hostiles world) [0.0 64.0 0.0] 4.0)) "none within 4")
  (is (nil? (entity/nearest {} [0.0 64.0 0.0]))))

(deftest gone-is-removed-or-dead
  (is (not (entity/gone? world 1)))
  (is (entity/gone? world 6) "the server said it died")
  (is (entity/gone? world 99) "the server removed it"))

(deftest metadata-gives-flags-and-a-living-entity-its-health
  (let [pig (blocks/entity-types :pig)
        interaction (blocks/entity-types :interaction)]
    (is (= {:entity/flags 1 :entity/health 7.5} (entity/metadata-facts pig {0 1 1 300 9 7.5})))
    (is (= {:entity/flags 0} (entity/metadata-facts interaction {0 0 9 2.0}))
        "index 9 of an interaction is its height")
    (is (= {} (entity/metadata-facts pig {2 "name"})) "nothing known, nothing said")
    (testing "a dropped item says what it holds, and only a dropped item (issue #262)"
      (is (= {:entity/item 1117}
             (entity/metadata-facts blocks/item-entity-type {8 {:item 1117 :count 1}})))
      (is (= {} (entity/metadata-facts pig {8 {:item 1117 :count 1}})))
      (is (= {} (entity/metadata-facts blocks/item-entity-type {8 nil})) "an empty stack, nothing"))
    (testing "a blaze's flags say it burns"
      (is (entity/on-fire? (merge (mob :blaze [0.0 0.0 0.0])
                                  (entity/metadata-facts (blocks/entity-types :blaze) {0 1})))))))

(deftest healths-name-the-nearest-of-each-kind
  (let [w {:player/pos [0.0 64.0 0.0]
           :world/entities {1 (mob :pig [5.0 64.0 0.0] :entity/health 10.0)
                            2 (mob :pig [0.0 66.0 0.0] :entity/health 7.5)
                            3 (mob :zombie [9.0 64.0 0.0] :entity/health 20.0)
                            4 (mob :cow [1.0 64.0 0.0])}}]
    (is (= {:pig 7.5 :zombie 20.0} (entity/healths w (:player/pos w)))
        "the pig over the bot, not the farther one; a cow whose health was never said is absent")
    (testing "the gym's entities trial is done once one is known (issue #258)"
      (is (plan/done-by w {:goal/done? :health-seen?}))
      (is (not (plan/done-by (assoc w :world/entities {4 (mob :cow [1.0 64.0 0.0])})
                             {:goal/done? :health-seen?})))
      (is
       (not (plan/done-by (update w :world/entities dissoc 2) {:goal/done? :health-seen?}))
       "a health known only of mobs farther off (round the spawn the bot joined at) is not the
           one beside it: gym batch entities-258a run 4 ended there, naming a natural pig")
      (is (= {:plan/wait :no-mob} (plan/next-intent w {:goal/plan :watch} plan/goals))))
    (testing "it is a trial of its own, never a step of a run"
      (is (plan/aside? (first (filter #(= :entities (:goal/id %)) plan/goals))))
      (is (not-any? #{:entities} (plan/through (plan/current-until)))))))
