(ns clojurecraft.recordings-test
  "Recorded gym runs as regression inputs (docs/hickey.md: \"a recorded race from last month still
   replays\"). Each directory under test/recordings holds a run's recording (run.edn.gz) and the
   result the gym judged (result.edn). Folding the recording must never throw, every world along
   the way must satisfy the world spec, and the fold must reach exactly the RESULT the run printed.
   A replay is open-loop (the server's replies were to the old bot's actions), so when the bot's
   behaviour changes on purpose, a recording that diverges is re-promoted from the next batch."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.spec.alpha :as s]
            [clojure.test :refer [deftest is testing]]
            [clojurecraft.game :as game]
            [clojurecraft.main :as main]
            [clojurecraft.record :as record]
            [clojurecraft.spec]))

(def root "test/recordings")
(def max-bytes (* 10 1024 1024))       ; the corpus stays under 10 MB in git

(defn corpus
  "[name recording-path result] for every promoted run."
  []
  (for [d (sort (.listFiles (io/file root)))
        :when (.isDirectory ^java.io.File d)
        :let [rec (io/file d "run.edn.gz") res (io/file d "result.edn")]
        :when (and (.exists rec) (.exists res))]
    [(.getName ^java.io.File d) (str rec) (edn/read-string (slurp res))]))

(defn bytes-under
  "Total size of every file under dir."
  [dir]
  (reduce + (map #(.length ^java.io.File %) (filter #(.isFile ^java.io.File %) (file-seq (io/file dir))))))

(deftest the-corpus-exists-and-stays-small
  (is (seq (corpus)) "at least one promoted run")
  (is (< (bytes-under root) max-bytes)))

(deftest every-recorded-run-replays
  (doseq [[name path result] (corpus)]
    (testing name
      (let [worlds (reductions (fn [w e] (assoc (main/step w e) :bot/effects []))
                               (game/init {:host "replay" :port 0 :name "Clj_replay"})
                               (record/events path))
            bad (first (keep-indexed (fn [i w] (when-not (s/valid? :clojurecraft.spec/world w) i)) worlds))]
        (is (nil? bad) (str "world " bad " breaks the world spec"))
        (let [replayed (main/replayed path)]
          (is (= :identical (:replay/result replayed)) "the fold reaches the RESULT the run printed")
          (is (= (:gym/result result) (record/recorded-result path)) "the gym judged the RESULT the recording holds"))))))
