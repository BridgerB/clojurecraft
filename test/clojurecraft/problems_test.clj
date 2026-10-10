(ns clojurecraft.problems-test
  "docs/hickey.md: before each stage, a written problem statement (what information it needs,
   what can go wrong, what done means in world terms, what the last recorded failure showed),
   kept as a fact next to the goal table. These tests keep that true."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.spec.alpha :as s]
            [clojure.test :refer [deftest is]]
            [clojurecraft.plan :as plan]
            [clojurecraft.spec]))

(def problems (edn/read-string (slurp (io/resource "clojurecraft/problems.edn"))))

(deftest every-statement-has-the-four-parts
  (is (= [] (remove #(s/valid? :clojurecraft.spec/problem %) problems)))
  (is (apply distinct? (map :problem/stage problems))))

(deftest every-target-goal-was-stated-first
  (is (= #{} (reduce disj (set (map :goal/id plan/targets)) (mapcat :problem/goals problems)))
      "a target in goals.edn with no problem statement"))

(deftest every-source-exists
  (is (= [] (remove #(.exists (io/file %)) (mapcat :problem/sources problems)))))
