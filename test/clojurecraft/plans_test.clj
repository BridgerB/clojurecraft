(ns clojurecraft.plans-test
  (:require [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojurecraft.plans :as plans]
            [clojurecraft.spec]))

(def plan
  {:plans/label "t1"
   :plans/jobs 4
   :plans/max-parallel 4
   :plans/experiments
   [{:exp/name "wood" :exp/kind :gym :exp/goal "wood" :exp/runs 10 :exp/bots 4 :exp/est-s 120}
    {:exp/name "kit"
     :exp/kind :gym
     :exp/goal "table"
     :exp/runs 2
     :exp/est-s 200
     :exp/arms [{:arm/name "head"} {:arm/name "base" :arm/ref "exp/base"}]}
    {:exp/name "forests"
     :exp/kind :sim
     :exp/property "forests"
     :exp/shards 3
     :exp/worlds 1000
     :exp/est-s 300}]})

(deftest a-plan-is-data
  (is (s/valid? :clojurecraft.plans/plan plan))
  (is (not (s/valid? :clojurecraft.plans/plan
                     (assoc-in plan [:plans/experiments 2 :exp/kind] :gym)))
      "a gym experiment needs a goal and runs"))

(deftest experiments-expand-into-units
  (let [us (plans/units plan)
        by-exp (group-by :unit/exp us)]
    (testing "gym runs group k to a server, in run order"
      (is (= [[1 2 3 4] [5 6 7 8] [9 10]] (map :unit/runs (by-exp "wood")))))
    (testing "each arm runs every run"
      (is (= [["head" [1]] ["head" [2]] ["base" [1]] ["base" [2]]]
             (map (juxt (comp :arm/name :unit/arm) :unit/runs) (by-exp "kit")))))
    (testing "sim shards split the worlds and carry reproducible seeds"
      (is (= [334 334 334] (map :unit/worlds (by-exp "forests"))))
      (is (= (map :unit/seed (by-exp "forests"))
             (map :unit/seed ((group-by :unit/exp (plans/units plan)) "forests"))))
      (is (apply distinct? (map :unit/seed (by-exp "forests")))))
    (is (apply distinct? (map :unit/id us)))))

(deftest units-are-scheduled-longest-first-onto-the-least-loaded-job
  (let [sched (plans/schedule plan)
        load (fn [w] (reduce + (map :unit/est-s (sched w))))]
    (is (= (set (map :unit/id (plans/units plan))) (set (map :unit/id (mapcat val sched))))
        "every unit exactly once")
    (is (= (count (plans/units plan)) (count (mapcat val sched))))
    (is (<= (- (apply max (map load (keys sched))) (apply min (map load (keys sched)))) 360)
        "balanced within one unit")
    (is (= (plans/assignment plan 2) (sched 2)) "a job recomputes its own share")
    (is (= (plans/schedule plan) (plans/schedule plan)) "deterministic")))

(deftest a-plan-that-cannot-fit-fails-before-it-starts
  (is (thrown? clojure.lang.ExceptionInfo
               (plans/schedule (assoc plan :plans/jobs 1 :plans/max-job-s 600)))))

(deftest the-matrix-and-width
  (is (= "{\"job\":[1,2,3,4]}" (plans/matrix plan)))
  (is (= 4 (plans/width plan 20 0)))
  (is (= 3 (plans/width plan 20 17)) "no wider than the free runners")
  (is (= 1 (plans/width plan 20 25)) "always at least one"))

(deftest the-report-names-experiments-arms-and-seeds
  (let [md (plans/report
            [{:plans/exp "kit"
              :plans/arm "base"
              :gym/goal :kit
              :gym/run 1
              :gym/landing [1 2 70]
              :gym/landed? true
              :gym/outcome :pass
              :gym/ms 1000
              :gym/reason :goal
              :gym/truths []}]
            [{:plans/exp "forests" :pass? true :num-tests 334 :seed 1}
             {:plans/exp "forests" :pass? false :num-tests 12 :seed 7 :smallest [[{:x 3}] 0 0]}])]
    (is (str/includes? md "## kit (base)"))
    (is (str/includes? md "pass 1/1"))
    (is (str/includes? md "worlds 346 in 2 shards; failing shards 1"))
    (is (str/includes? md "- seed 7: smallest failing input"))))

(deftest a-salt-gives-a-repeated-plan-fresh-seeds-and-nothing-else
  (let [seeds (fn [p] (map :unit/seed (filter :unit/seed (plans/units p))))
        salted (assoc plan :plans/salt "run-7")]
    (is (s/valid? :clojurecraft.plans/plan salted))
    (is (not-any? (set (seeds plan)) (seeds salted)))
    (is (= (seeds salted) (seeds (assoc plan :plans/salt "run-7"))) "the same salt, the same seeds")
    (is (= (map :unit/id (plans/units plan)) (map :unit/id (plans/units salted))))))

(deftest failures-are-every-row-that-did-not-pass-with-its-directory
  (let [located
        [{:row {:plans/exp "wood" :plans/arm "head" :gym/run 1 :gym/outcome :pass} :path "a"}
         {:row {:plans/exp "wood"
                :plans/arm "head"
                :gym/run 2
                :gym/outcome :fail
                :gym/reason :stuck
                :gym/landing [1 70 2]}
          :path "b"} {:row {:plans/exp "forests" :pass? true :seed 1} :path "c"}
         {:row {:plans/exp "forests" :pass? false :seed 9 :smallest [[{:x 1}] 0 1]} :path "d"}]
        fs (plans/failures located)]
    (is (= #{"b" "d"} (set (map :path fs))))
    (is (= #{"- wood (head) run 2 :fail :stuck at [1 70 2]: b"
             "- sim forests seed 9 smallest [[{:x 1}] 0 1]: d"}
           (set (map plans/failure-line fs))))))
