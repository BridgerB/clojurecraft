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

(def rules "The hunt's rows in the order they are tried." (:pearls/rules table))

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

(defn decide
  "The intent of the first row of rules whose :rule/when holds in world, labelled with the row's
   :rule/id (intent/why), or nil when its builder yields none. The last row has no conditions."
  [world]
  (let [f (facts/context world)
        row (first (filter #(holds? f (:rule/when %)) rules))]
    (intent/why (:rule/id row) (then f (:rule/then row)))))

(defn phase
  "The id of the first phase of the hunt that is not done in world (its :phase/done facts do not
   all hold); the last phase when every earlier one is."
  [world]
  (let [f (facts/context world)]
    (:phase/id (or (first (filter #(and (:phase/done %) (not (holds? f (:phase/done %)))) phases))
                   (last phases)))))
