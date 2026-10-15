(ns clojurecraft.issues-test
  "The issue check (issue #77): a bot-failure issue's failing moments, kept as snapshots under
   test/issues/<n>/ (bb issue-check fetch), are played against the model as their signature can
   be judged (simrace/judged): played on from the moment for two simulated minutes, or, for a
   :timeout, the run replayed from its landing until it gets past its stage; the fix holds when none
   fails again with the issue's signature. A snapshot stays
   once its issue is fixed (docs/RICH-HICKEY.md: a goal that was wrong is retired, never rewritten),
   so a bug that comes back fails here, naming the issue. A snapshot whose captured world is
   not on this machine (cache/sim-worlds, the runners restore it) is skipped and said so, so
   the suite runs on a laptop too."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojurecraft.capture :as capture]
            [clojurecraft.simrace :as simrace]))

(def root "test/issues")
(def max-file-bytes (* 100 1024 1024)) ; GitHub refuses a push with a file over 100 MiB: the only size limit a snapshot meets

(defn issues
  "[issue-number [snapshot-path ...]] for every issue directory, in issue order."
  []
  (for [d (sort-by #(parse-long (.getName ^java.io.File %))
                   (filter #(and (.isDirectory ^java.io.File %)
                                 (re-matches #"\d+" (.getName ^java.io.File %)))
                           (or (.listFiles (io/file root)) [])))]
    [(parse-long (.getName ^java.io.File d))
     (sort (map str
                (filter #(str/ends-with? (.getName ^java.io.File %) ".edn.gz")
                        (.listFiles ^java.io.File d))))]))

(defn too-big
  "Every file under dir larger than max-file-bytes: what GitHub would refuse to take."
  [dir]
  (map str
       (filter #(and (.isFile ^java.io.File %) (> (.length ^java.io.File %) max-file-bytes))
               (file-seq (io/file dir)))))

(def worlds (atom {}))                 ; captured worlds read once, by file

(defn world-of
  "The captured world a snapshot names, prepared, read once; nil when the file is not here."
  [file]
  (when (.exists (io/file file))
    (or (get @worlds file)
        (let [w (assoc (simrace/prepared (capture/read-world file)) :capture/file file)]
          (swap! worlds assoc file w)
          w))))

(deftest every-snapshot-fits-on-github (is (empty? (too-big root))))

(deftest no-issues-failing-moment-fails-the-same-way-again
  (doseq [[n paths] (issues)
          path paths]
    (testing (str "issue #" n " " path)
      (let [snap (capture/read-world path)
            w (world-of (:snapshot/file snap))]
        (cond (nil? w) (println "issues-test: skipped" path "- no" (:snapshot/file snap) "here")
              :else
              (let [played (simrace/judged snap w)]
                (is (not (simrace/recurs? snap played))
                    (str "issue #" n
                         " recurs: " (pr-str (:run/signature played))
                         " at " (:run/ms played)
                         " ms from " path))))))))
