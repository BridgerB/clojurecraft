(ns clojurecraft.pearls.rules
  "The pearl hunt's rule table (pearls-rules.edn) and the function that reads it: the first row
   whose facts hold chooses the intent."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojurecraft.intent :as intent]
            [clojurecraft.pearls.facts :as facts]))

(set! *warn-on-reflection* true)

(def table
  "The rules file as data: {:pearls/phases [...] :pearls/rules [...]}."
  (edn/read-string (slurp (io/resource "clojurecraft/pearls-rules.edn"))))

(def all-rules
  "Every row, in the order next-intent tries them: fire, the hunt's survival rows, the pearl's
   drop, the kit's upkeep, then the rest of the hunt."
  (:pearls/rules table))

(def rules
  "The rows of the hunt alone (hunt), in the order they are tried."
  (vec (remove (comp #{:fire :collect :upkeep} :rule/phase) all-rules)))

(def phases
  "The hunt's phases in order, each with the facts that say it is done (the last has none)."
  (:pearls/phases table))

(defn holds?
  "Do all the conditions in when hold, given the fact getter f: a key must be truthy, a
   [:not key] falsy. Stops at the first that does not."
  [f when]
  (every? (fn [c] (if (vector? c) (not (f (second c))) (f c))) when))

(defn then
  "The intent a row yields, given the fact getter f: [:fact k] is fact k, [:intent m] is m with
   each [:fact k] value replaced by fact k."
  [f [form arg]]
  (case form
    :fact (f arg)
    :intent (update-vals arg (fn [v] (if (and (vector? v) (= :fact (first v))) (f (second v)) v)))))

(defn decide-in
  "The intent of the first of rows whose :rule/when holds under the fact getter f, labelled with
   the row's :rule/id (intent/why), or nil when its builder yields none. The last row of every
   list has no conditions."
  [f rows]
  (let [row (first (filter #(holds? f (:rule/when %)) rows))]
    (intent/why (:rule/id row) (then f (:rule/then row)))))

(defn decide
  "The hunt's intent in world: decide-in over the rows of the hunt alone."
  [world]
  (decide-in (facts/context world) rules))

(defn decide-all
  "The pearl stage's intent in world, for goal row goal and its needs table: decide-in over every
   row. Nil when the last row yields none (next-intent then falls back)."
  [world goal table]
  (decide-in (facts/context world {:goal goal :table table}) all-rules))

(defn row-of
  "The id of the first row of the hunt's rules that holds in world: the row that decides, whatever
   label an inner planner puts on its intent."
  [world]
  (let [f (facts/context world)] (:rule/id (first (filter #(holds? f (:rule/when %)) rules)))))

(defn phase
  "The id of the first phase of the hunt that is not done in world (its :phase/done facts do not
   all hold); the last phase when every earlier one is."
  [world]
  (let [f (facts/context world)]
    (:phase/id (or (first (filter #(and (:phase/done %) (not (holds? f (:phase/done %)))) phases))
                   (last phases)))))
