(ns clojurecraft.jfr-test
  "A race JVM's flight recording read: jfr print's samples tallied by the part of the bot they were
   in. Babashka runs these (bb test:bb)."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [clojurecraft.jfr :as jfr]))

(defn sample
  "One execution sample as jfr print writes it: thread, then frames leaf first."
  [thread & frames]
  (concat ["jdk.ExecutionSample {" "  startTime = 07:42:23.453 (2026-11-11)"
           (str "  sampledThread = \"" thread "\" (javaThreadId = 33)")
           "  state = \"STATE_RUNNABLE\"" "  stackTrace = ["]
          (for [f frames] (str "    " f "(Object, Object) line: 12"))
          ["    ..." "  ]" "}" ""]))

(def lines
  "Four samples: two in the planner's step, one in the ledger, one in a GC-free pool thread."
  (concat
   (sample "async-mixed-3"
           "datascript.query$_q.invokeStatic" "clojurecraft.memory$last_done.invokeStatic"
           "clojurecraft.plan$step_over.invoke" "clojurecraft.main$run_loop_BANG_.invokeStatic")
   (sample "async-mixed-4"
           "clojure.lang.RT.nth" "clojurecraft.path$search__1234.invoke"
           "clojurecraft.plan$step_over.invoke" "clojurecraft.main$run_loop_BANG_.invokeStatic")
   (sample "async-mixed-12"
           "clojurecraft.senses$events.invokeStatic"
           "clojurecraft.senses$ledger_BANG_$fn__99.invoke")
   (sample "JFR Periodic Tasks" "jdk.jfr.internal.PeriodicEvents.doPeriodic")))

(deftest a-sample-goes-to-the-part-its-stack-is-in
  (is (= :plan
         (jfr/role {:thread "async-mixed-3" :frames ["x" "clojurecraft.plan$step_over.invoke"]})))
  (is (= :ledger
         (jfr/role {:thread "async-mixed-3"
                    :frames ["clojurecraft.plan$step_over.invoke"
                             "clojurecraft.senses$ledger_BANG_$fn"]}))
      "the first of roles wins: the ledger diffs worlds the step made")
  (is (= :pool/async-mixed-#
         (jfr/role {:thread "async-mixed-7" :frames ["java.lang.Object.wait"]}))))

(deftest samples-are-tallied-by-part-with-their-frames
  (let [s (jfr/summary (jfr/tallies lines))]
    (is (= 4 (:samples s)))
    (is (= [:plan 2 50.0] ((juxt :role :samples :share) (first (:parts s)))) "biggest first")
    (is (= #{[:ledger 1 25.0] [(keyword "pool" "JFR Periodic Tasks") 1 25.0]}
           (set (map (juxt :role :samples :share) (rest (:parts s))))))
    (is (= #{["clojurecraft.plan$step_over" 2] ["clojurecraft.main$run_loop_BANG_" 2]
             ["clojurecraft.memory$last_done" 1] ["clojurecraft.path$search" 1]}
           (set (:bot (first (:parts s)))))
        "each clojurecraft function once a sample, its compiled suffix dropped")
    (is (= #{["datascript.query$_q.invokeStatic" 1] ["clojure.lang.RT.nth" 1]}
           (set (:self (first (:parts s))))))
    (is (str/includes? (jfr/text s) "== :plan 50.0% (2)"))))
