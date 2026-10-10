(ns clojurecraft.fleet-test
  (:require [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojurecraft.fleet :as fleet]
            [clojurecraft.spec]))

(def plan
  {:fleet/label "t1" :fleet/workers 4 :fleet/max-parallel 4
   :fleet/experiments
   [{:exp/name "wood" :exp/kind :gym :exp/goal "wood" :exp/runs 10 :exp/bots 4 :exp/est-s 120}
    {:exp/name "kit" :exp/kind :gym :exp/goal "table" :exp/runs 2 :exp/est-s 200
     :exp/arms [{:arm/name "head"} {:arm/name "base" :arm/ref "exp/base"}]}
    {:exp/name "forests" :exp/kind :sim :exp/property "forests" :exp/shards 3 :exp/worlds 1000 :exp/est-s 300}]})

(deftest a-plan-is-data
  (is (s/valid? :clojurecraft.spec/fleet-plan plan))
  (is (not (s/valid? :clojurecraft.spec/fleet-plan (assoc-in plan [:fleet/experiments 2 :exp/kind] :gym)))
      "a gym experiment needs a goal and runs"))

(deftest experiments-expand-into-units
  (let [us (fleet/units plan)
        by-exp (group-by :unit/exp us)]
    (testing "gym runs group k to a server, in run order"
      (is (= [[1 2 3 4] [5 6 7 8] [9 10]] (map :unit/runs (by-exp "wood")))))
    (testing "each arm runs every run"
      (is (= [["head" [1]] ["head" [2]] ["base" [1]] ["base" [2]]] (map (juxt (comp :arm/name :unit/arm) :unit/runs) (by-exp "kit")))))
    (testing "sim shards split the worlds and carry reproducible seeds"
      (is (= [334 334 334] (map :unit/worlds (by-exp "forests"))))
      (is (= (map :unit/seed (by-exp "forests")) (map :unit/seed ((group-by :unit/exp (fleet/units plan)) "forests"))))
      (is (apply distinct? (map :unit/seed (by-exp "forests")))))
    (is (apply distinct? (map :unit/id us)))))

(deftest units-are-scheduled-longest-first-onto-the-least-loaded-worker
  (let [sched (fleet/schedule plan)
        load (fn [w] (reduce + (map :unit/est-s (sched w))))]
    (is (= (set (map :unit/id (fleet/units plan))) (set (map :unit/id (mapcat val sched)))) "every unit exactly once")
    (is (= (count (fleet/units plan)) (count (mapcat val sched))))
    (is (<= (- (apply max (map load (keys sched))) (apply min (map load (keys sched)))) 360) "balanced within one unit")
    (is (= (fleet/assignment plan 2) (sched 2)) "a worker recomputes its own share")
    (is (= (fleet/schedule plan) (fleet/schedule plan)) "deterministic")))

(deftest a-plan-that-cannot-fit-fails-before-it-starts
  (is (thrown? clojure.lang.ExceptionInfo (fleet/schedule (assoc plan :fleet/workers 1 :fleet/max-worker-s 600)))))

(deftest the-matrix-and-width
  (is (= "{\"worker\":[1,2,3,4]}" (fleet/matrix plan)))
  (is (= 4 (fleet/width plan 20 0)))
  (is (= 3 (fleet/width plan 20 17)) "no wider than the free runners")
  (is (= 1 (fleet/width plan 20 25)) "always at least one"))

(deftest the-report-names-experiments-arms-and-seeds
  (let [md (fleet/report [{:fleet/exp "kit" :fleet/arm "base" :gym/goal :kit :gym/run 1 :gym/landing [1 2 70]
                           :gym/landed? true :gym/outcome :pass :gym/ms 1000 :gym/reason :goal :gym/truths []}]
                         [{:fleet/exp "forests" :pass? true :num-tests 334 :seed 1}
                          {:fleet/exp "forests" :pass? false :num-tests 12 :seed 7 :smallest [[{:x 3}] 0 0]}])]
    (is (str/includes? md "## kit (base)"))
    (is (str/includes? md "pass 1/1"))
    (is (str/includes? md "worlds 346 in 2 shards; failing shards 1"))
    (is (str/includes? md "- seed 7: smallest failing input"))))
