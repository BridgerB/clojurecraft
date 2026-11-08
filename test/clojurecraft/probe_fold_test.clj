(ns clojurecraft.probe-fold-test
  "A probe, not a test: folds one recording many times at once and writes out/fold-probe.edn with
   every distinct snapshot moment seen and, for a differing fold, the plan fields of its world."
  (:require [clojure.test :refer [deftest is]]
            [clojurecraft.dimension :as dimension]
            [clojurecraft.real :as real]
            [clojurecraft.real-test :as rt]))

(defn summary-of
  "The facts of a fold that decide its moment."
  [lines]
  (let [f (real/fold lines)
        w (:world f)]
    {:moment (select-keys (:snapshot/moment (real/convert f
                                                          {:seed 3
                                                           :until "nether"
                                                           :commit nil
                                                           :signature
                                                           (get-in f [:result :run/signature])
                                                           :difficulty nil
                                                           :recording "t.edn"
                                                           :bot "Clj_t"}))
                          [:decision-t :why :t])
     :status (:plan/status w)
     :reason (:plan/reason w)
     :waiting (:plan/waiting w)
     :waiting-since (:plan/waiting-since w)
     :waiting-ticks (:plan/waiting-ticks w)
     :attempts (:plan/attempts w)
     :intents (:plan/intents w)
     :now (:time/now w)
     :tick (:time/tick w)}))

(deftest fold-many-at-once
  (let [run (rt/recorded-run (rt/bare 3 11 dimension/overworld) 9 "nether" 300000 {})
        lines (:lines run)
        base (summary-of lines)
        results (->> (range 12)
                     (mapv (fn [_] (future (vec (repeatedly 6 #(summary-of lines))))))
                     (mapcat deref)
                     vec)
        distinct-results (distinct results)]
    (.mkdirs (java.io.File. "out"))
    (spit "out/fold-probe.edn" (pr-str {:base base :n (count results) :distinct distinct-results}))
    (is (= [base] (vec distinct-results)))))
