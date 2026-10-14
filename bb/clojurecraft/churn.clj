(ns clojurecraft.churn
  "The churn's reports while it plays (issue #127): a runner reports the runs that ended since
   its last report, so nothing waits for the job, the workflow or another runner to end.

     bb churn batch <kind> <data-dir> <artifact> <run> <sha> <code-at> <runner>

   kind is real or sim; data-dir the runner's record directory (data/race, data/simrace), whose
   race.log or runs.edn files grow a line a run; artifact the name the job uploads that directory
   under at its end (race-<sha>-<runner>, simrace-<sha>-<runner>), which a reproduction names. A
   batch is the whole lines each file gained since the last batch: they are copied under
   churn-batches/<n>/<artifact>/, then their failures filed (bb failures, judged by the job's
   counts so far), their facts published as a batch of the run on the results page (bb page),
   the wall appended to the job's summary, a win released. The state (offsets, the batch number,
   the counts) is a value in data-dir's .churn.edn, written only after a batch is reported; a
   batch with no run ended in it reports nothing and keeps its lines for the next.

   Babashka runs this; nothing here requires the bot."
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [clojurecraft.failures :as failures]
            [clojurecraft.page :as page]
            [clojurecraft.win :as win]))

(def state-file ".churn.edn")          ; in data-dir: {:churn/offsets {path lines} :churn/batch n :churn/counts {signature n}}
(def batches-dir "churn-batches")      ; in the checkout, beside data/: never inside a directory the job uploads
(def logs #{"race.log" "runs.edn"})    ; the files a run ends into, a line each
(def fresh {:churn/offsets {} :churn/batch 0 :churn/counts {}})

;; ---------------------------------------------------------------- a batch, as values

(defn whole-lines
  "The lines of text a writer has finished: every line but a last one with no newline yet."
  [text]
  (let [lines (str/split-lines text)]
    (cond (str/ends-with? text "\n") lines
          :else (vec (butlast lines)))))

(defn grown
  "{path lines} of the whole lines each file gained past its offset, from texts {path text} and
   offsets {path lines}; files that gained none are absent."
  [texts offsets]
  (into {}
        (keep (fn [[path text]]
                (let [lines (drop (get offsets path 0) (whole-lines text))]
                  (when (seq lines) [path (vec lines)]))))
        texts))

(defn counted
  "The job's counts with runs' failures added: {signature n}, the runs that failed with one and
   were not the server's doing (failures/failed)."
  [counts runs]
  (merge-with + counts (frequencies (map failures/key-of (failures/failed runs)))))

(defn next-state
  "The state once a batch of grown lines (grown) and its runs is reported: each offset past the
   lines copied, the batch number on, the runs' failures counted."
  [state lines runs]
  (-> state
      (update :churn/offsets #(merge-with + % (update-vals lines count)))
      (update :churn/batch inc)
      (update :churn/counts counted runs)))

(defn title
  "A batch's title in the issues and the page."
  [kind run runner batch sha]
  (str (if (= kind :real) "Race" "Sim race")
       " " run
       " runner " runner
       " batch " batch
       " at " (subs sha 0 7)))

(defn wall-text
  "The wall (page/wall) as the job summary's markdown, under head: a line a stage, its three
   most frequent signatures."
  [head wall]
  (str "### "
       head
       ": the wall, furthest stage first\n```\n"
       (str/join "\n"
                 (for [[stage total sigs] wall]
                   (str (name stage)
                        " " total
                        " "
                        (str/join ", " (for [[sig c] (take 3 sigs)] (str (pr-str sig) " " c))))))
       "\n```\n"))

;;;; I/O: the record files, the batch's copy, the reports ;;;;

(defn read-state
  "The state in data-dir, or a fresh one."
  [data-dir]
  (let [f (fs/file data-dir state-file)] (if (fs/exists? f) (edn/read-string (slurp f)) fresh)))

(defn texts
  "{path text} of every log under data-dir, paths relative to it."
  [data-dir]
  (into {}
        (comp (filter #(logs (fs/file-name %)))
              (map (fn [f] [(str (fs/relativize data-dir f)) (slurp (fs/file f))])))
        (fs/glob data-dir "**")))

(defn copy!
  "Write lines {path lines} under dir/artifact, each at its path; returns dir."
  [dir artifact lines]
  (doseq [[path ls] lines
          :let [f (fs/path dir artifact path)]]
    (fs/create-dirs (fs/parent f))
    (spit (str f) (str (str/join "\n" ls) "\n")))
  dir)

(defn report!
  "Report a batch's runs: the issues, the job summary's wall, the page's batch, a win."
  [{:keys [kind run sha code-at runner data-dir]} n dir runs counts]
  (let [head (title kind run runner n sha)
        milestones (page/chain (page/goals))
        round (page/round runs
                          (page/marks-under dir)
                          milestones
                          (merge {:kind kind
                                  :run run
                                  :commit sha
                                  :at (page/instant code-at)
                                  :runner runner
                                  :batch n
                                  :issues (page/issue-numbers runs)}
                                 (page/run-times run)))]
    (println "churn:" head ":" (count runs) "runs")
    (failures/report-runs! runs head code-at counts)
    (when-let [summary (System/getenv "GITHUB_STEP_SUMMARY")]
      (spit summary (wall-text head (page/wall (:round/failures round) milestones)) :append true))
    (page/publish-round! round)
    (when (= kind :real) (win/release-runs! runs run sha data-dir))))

(defn batch!
  "Report the runs that ended since the last batch (see the namespace)."
  [{:keys [data-dir artifact] :as job}]
  (let [state (read-state data-dir)
        n (inc (:churn/batch state))
        lines (grown (texts data-dir) (:churn/offsets state))
        dir (copy! (fs/path batches-dir (str n)) artifact lines)
        runs (failures/runs-under dir)]
    (cond (empty? runs) (println "churn: no run ended since batch" (:churn/batch state))
          :else
          (let [after (next-state state lines runs)]
            (report! job n dir runs (:churn/counts after))
            (spit (str (fs/file data-dir state-file)) (pr-str after))))))

(defn -main
  "batch <kind> <data-dir> <artifact> <run> <sha> <code-at> <runner>: report the runs ended since
   the last batch."
  [& [cmd kind data-dir artifact run sha code-at runner]]
  (case cmd
    "batch"
    (batch! {:kind (keyword kind)
             :data-dir data-dir
             :artifact artifact
             :run (parse-long run)
             :sha sha
             :code-at code-at
             :runner (parse-long runner)})
    (do (println
         "usage: bb churn batch <real|sim> <data-dir> <artifact> <run> <sha> <code-at> <runner>")
        (System/exit 2))))
