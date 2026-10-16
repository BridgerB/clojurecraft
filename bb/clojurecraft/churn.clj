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

   A real batch first turns its failures into snapshots (issue #299): each failed bot's recording,
   closed when its RESULT was printed, is folded on this runner at the commit that played it
   (clojure -M:simrace --from-recording, real.clj), and kept under <data-dir>/snapshots (so in the
   artifact) only when the model reproduces the failure; the issue's comment then lists it as a
   sim snapshot is listed, so bb issue-check fetch takes it and the fix's check judges it.

   Babashka runs this; nothing here requires the bot."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [clojurecraft.failures :as failures]
            [clojurecraft.page :as page]
            [clojurecraft.win :as win]))

(def state-file ".churn.edn")          ; in data-dir: {:churn/offsets {path lines} :churn/batch n :churn/counts {signature n}}
(def batches-dir "churn-batches")      ; in the checkout, beside data/: never inside a directory the job uploads
(def logs #{"race.log" "runs.edn"})    ; the files a run ends into, a line each
(def fresh {:churn/offsets {} :churn/batch 0 :churn/counts {}})
(def snapshots-dir "snapshots")        ; in data-dir: a real batch's snapshots, uploaded with the artifact
(def real-budget-ms 600000)            ; wall time a real batch's conversions may take: never past the job's last report
(def real-heap "-J-Xmx3g")             ; the conversion's JVM, beside two 3 GB servers and two 2 GB races on a 16 GB runner

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

(defn convertible
  "A real batch's failures to turn into snapshots: the failed runs (failures/failed) of a real
   bot whose recording is known (:run/bot, :run/dir), the first failures/examples of each
   signature, the ones its comment lists."
  [runs]
  (->> (failures/failed runs)
       (filter #(and (= :real (failures/source %)) (:run/bot %) (:run/dir %)))
       (group-by failures/key-of)
       vals
       (mapcat #(take failures/examples %))))

(defn snapshot-name
  "The file a real run's snapshot is kept in: real-<runner>-<server dir>-<bot>.edn.gz, one bot of
   one runner's job (each server's bots have the same names, s1 and s2 apart)."
  [runner r]
  (str "real-" runner "-" (str/replace (:run/dir r) "/" "-") "-" (:run/bot r) ".edn.gz"))

(defn line-of
  "A conversion's REAL or GAP line (real/from-recording!) read, its map with :line/kind :real or
   :gap; nil for any other line, or one that does not read."
  [s]
  (when-let [[_ k m] (re-matches #"(REAL|GAP) (.*)" s)]
    (try (assoc (edn/read-string {:default (fn [_ v] v)} m) :line/kind (keyword (str/lower-case k)))
         (catch Exception _ nil))))

(defn converted
  "runs with the snapshots a conversion kept (its REAL lines): each run whose snapshot-name one
   kept gets :run/snapshot (the file) and :run/moment (when and why), what bb failures' repro
   lists."
  [runs lines runner]
  (let [kept (into {}
                   (for [l lines
                         :when (= :real (:line/kind l))]
                     [(last (str/split (str (:path l)) #"/")) l]))]
    (mapv (fn [r]
            (let [l (when (:run/dir r) (kept (snapshot-name runner r)))]
              (cond-> r l (assoc :run/snapshot (:path l) :run/moment (:moment l)))))
          runs)))

(defn conversion-text
  "What a real batch's conversions did, one line for the log and the job's summary: snapshots
   kept, and the gaps by kind (the model did not reproduce the failure: never filed)."
  [lines]
  (str "real snapshots: " (count (filter #(= :real (:line/kind %)) lines))
       " kept of " (count lines)
       "; model gaps (not filed) "
       (pr-str (frequencies (keep :gap (filter #(= :gap (:line/kind %)) lines))))))

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

(defn convert!
  "Turn a real batch's failures (convertible) into snapshots: one clojure -M:simrace
   --from-recording over their recordings (<data-dir>/<dir>/<bot>.edn), niced under the race's
   servers, its heap held to real-heap, writing <data-dir>/snapshots (gaps under its gaps/) and its lines to
   snapshots/convert-<n>.log, stopped at real-budget-ms (the runs it did not reach keep their
   replay lines). Returns the REAL and GAP lines it printed (line-of)."
  [{:keys [data-dir run runner]} n runs]
  (let [todo (convertible runs)
        out (fs/path data-dir snapshots-dir)
        log (fs/file out (str "convert-" n ".log"))]
    (when (seq todo)
      (fs/create-dirs out)
      (let [proc (p/process {:out log :err :inherit}
                            "nice"
                            "-n"
                            "19"
                            "clojure"
                            real-heap
                            "-M:simrace"
                            "--from-recording"
                            (str/join
                             ","
                             (map #(str (fs/path data-dir (:run/dir %) (str (:run/bot %) ".edn")))
                                  todo))
                            "--name" (str/join "," (map #(snapshot-name runner %) todo))
                            "--out" (str out)
                            "--round" (str run))]
        (when-not (.waitFor ^Process (:proc proc)
                            (long real-budget-ms)
                            java.util.concurrent.TimeUnit/MILLISECONDS)
          (p/destroy-tree proc)
          (println "churn: the real conversions ran out of time; the rest keep their replay lines"))
        (let [lines (vec (keep line-of (str/split-lines (slurp log))))]
          (println "churn:" (conversion-text lines))
          lines)))))

(defn report!
  "Report a batch's runs: the issues, the job summary's wall, the page's batch, a win."
  [{:keys [kind run sha code-at runner data-dir]} n dir runs counts]
  (let [head (title kind run runner n sha)
        milestones (page/chain (page/goals) (page/race-end))
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
          (let [after (next-state state lines runs)
                real (when (= :real (:kind job)) (convert! job n runs))
                runs (cond-> runs (seq real) (converted real (:runner job)))]
            (when-let [summary (and (seq real) (System/getenv "GITHUB_STEP_SUMMARY"))]
              (spit summary (str (conversion-text real) "\n\n") :append true))
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
