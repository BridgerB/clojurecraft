(ns clojurecraft.dispatch-test
  "The planner's open registries (intent/run on :intent/kind, plan/next-intent, plan/act, plan/done-by,
   plan/interrupts?, plan/steer) are keyed by one value, and a second defmethod for it in another
   namespace silently replaces the first, whichever loads last: :rest (dragon against blaze) and
   :hold (dragon against blaze's pocket) did, in one day (issue #615). The source is scanned, since
   a multimethod keeps no history of what it replaced."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [clojurecraft.model :as model]))

(def defmethod-form
  "A defmethod of one of the registries (run, next-intent, act, done-by, interrupts?, steer) on a keyword: groups multimethod and dispatch value."
  #"\(defmethod\s+(?:[\w.-]+/)?(run|next-intent|act|done-by|interrupts\?|steer)\s+(:[^\s\[\]()]+)")

(defn definitions
  "Every [multimethod dispatch-value file] a registry defmethod in src/clojurecraft has, in file order."
  []
  (for [^java.io.File f (sort (filter #(str/ends-with? (.getName ^java.io.File %) ".clj")
                                      (file-seq (io/file "src/clojurecraft"))))
        [_ m v] (re-seq defmethod-form (slurp f))]
    [m v (.getPath f)]))

(deftest every-dispatch-value-is-defined-once
  (let [dups (for [[[m v] defs] (group-by (juxt first second) (definitions))
                   :when (< 1 (count defs))]
               [m v (mapv #(nth % 2) defs)])]
    (is (seq (definitions)) "the scan found the registries")
    (is (empty? dups) "one defmethod a dispatch value: [multimethod value files]")))

(deftest every-intent-kind-is-listed-once-in-the-model
  (let [listed (re-seq #":[a-z0-9-]+" (:doc (model/by-attribute :intent/kind)))
        defined (set (for [[m v] (definitions) :when (and (= "run" m) (not= ":default" v))] v))]
    (is (= [] (sort (remove (set listed) defined)))
        "an executor the model's :intent/kind row lacks")
    (is (= [] (sort (for [[k n] (frequencies listed) :when (< 1 n)] k))) "a kind listed twice")))
