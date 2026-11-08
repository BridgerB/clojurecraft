(ns clojurecraft.ranks-test
  "A row's :goal/ranks lists the branches of its plan; the planner cuts a running intent only for a
   branch ranked above it."
  (:require [clojure.test :refer [deftest is]]
            [clojurecraft.plan :as plan]))

(defn row "The shipped row with :goal/id id." [id] (plan/row-of plan/goals id))

(defn labels-in
  "The distinct labels ns-name/<branch> that file passes to a (ranked ...) call, directly or as
   the two arms of an (if test a b) in its first argument."
  [file ns-name]
  (let [label (str ":" ns-name "/[a-z0-9?-]+")
        call (re-pattern (str "\\(intent/ranked\\s+(?:\\(if\\s+\\S+\\s+("
                              label
                              ")\\s+("
                              label
                              ")\\)|("
                              label
                              "))"))]
    (set (mapcat #(remove nil? (rest %)) (re-seq call (slurp file))))))

(deftest every-branch-the-dragon-plan-labels-is-ranked-and-every-rank-is-a-branch
  (let [used (set (map keyword
                       (map #(subs % 1) (labels-in "src/clojurecraft/dragon.clj" "dragon"))))
        ranked (set (:goal/ranks (row :dragon)))]
    (is (seq used))
    (is (= used ranked))
    (is (= (count ranked) (count (:goal/ranks (row :dragon)))) "no branch listed twice")))

(deftest only-a-strictly-more-urgent-branch-outranks
  (let [goal {:goal/ranks [:a/high :a/mid :a/low]}
        at (fn [label] (vary-meta {:intent/kind :walk} assoc :intent/why label))]
    (is (plan/outranks? goal (at :a/low) (at :a/high)))
    (is (not (plan/outranks? goal (at :a/mid) (at :a/mid))) "an equal branch is committed")
    (is (not (plan/outranks? goal (at :a/high) (at :a/low))))
    (is (not (plan/outranks? goal (at :a/low) {:plan/wait :x})) "an unlisted wait never cuts")
    (is (plan/outranks? goal (at :a/low) (vary-meta {:plan/wait :x} assoc :intent/why :a/high))
        "a labelled wait cuts what ranks below it")
    (is (not (plan/outranks? goal (at :a/low) nil)))
    (is (plan/outranks? goal (at :a/unlisted) (at :a/low)) "an unlisted label ranks last")
    (is (not (plan/outranks? {} (at :a/low) (at :a/high))) "a row with no ranks never ranks")))
