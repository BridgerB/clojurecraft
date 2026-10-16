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

   A batch first finds which of its failures the model reproduces on this commit (issue #299): a
   real batch folds each failed bot's recording, closed when its RESULT was printed, into a
   snapshot on this runner at the commit that played it (clojure -M:simrace --from-recording,
   real.clj), kept under <data-dir>/snapshots (so in the artifact) only when it recurs; a sim
   batch judges each failed run's snapshot (clojure -M:simrace --judge). A run whose snapshot
   recurs is :run/recurs?, and only those are filed (bb failures): an issue is open only while it
   has a reproducible snapshot, which bb issue-check fetch takes and the fix's check judges.

   The snapshots a batch files on are uploaded for good before its comments are written: to the
   release of the hour (evidence-tag, a prerelease), each under a name no other lane or run has
   (asset-name). The comment names that asset, so the evidence is there the moment the comment
   is, whatever happens to the job after: a merge cancels every lane, and a cancelled job often
   never reaches its artifact's upload (race 38962622402, issue #37's evidence lost). A run
   whose snapshot did not upload is not filed. The artifact still holds the recordings and logs.

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
(def budget-ms 600000)                 ; wall time a batch's conversions or judges may take: never past the job's last report
(def release-prefix "evidence-")       ; a release an hour holds the snapshots the hour's comments name
(def repo-default "BridgerB/clojurecraft") ; gh's repository when the workflow does not say
(def per-signature 2)                  ; failures a batch converts or judges of each signature: the most a fix's check judges (issue-check)
(def real-heap "-J-Xmx3g")             ; the conversion's JVM, beside two 3 GB servers and two 2 GB races on a 16 GB runner
(def sim-heap "-J-Xmx2g")              ; the judge's JVM, beside the sim race's (ci/simrace.edn's heap)
(def real-nice 19)                     ; the conversion's nice level beside the servers and bots
(def sim-nice 19)                      ; the judge's beside the sim race: judges take seconds

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
  "A real batch's failures to turn into snapshots, in the order to fold them: the failed runs
   (failures/failed) of a real bot whose recording is known (:run/bot, :run/dir), per-signature
   of each signature (a fix's check judges two at most), the smallest recordings (:run/bytes)
   first, and every signature's first before any second. A fold's time grows with its recording
   and the batch's budget stops the rest: on a race's runner they took 30 to 450 s (race
   38962774798), so the cheap ones and the most signatures come first."
  [runs]
  (->> (failures/failed runs)
       (filter #(and (= :real (failures/source %)) (:run/bot %) (:run/dir %)))
       (group-by failures/key-of)
       vals
       (mapcat #(map-indexed vector (take per-signature (sort-by (fn [r] (:run/bytes r 0)) %))))
       (sort-by (fn [[i r]] [i (:run/bytes r 0)]))
       (map second)))

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
  "runs with the snapshots a conversion kept (its REAL lines, each one that recurred): each run
   whose snapshot-name one kept gets :run/snapshot (the file), :run/moment (when and why) and
   :run/recurs?, what bb failures files and its repro lists."
  [runs lines runner]
  (let [kept (into {}
                   (for [l lines
                         :when (= :real (:line/kind l))]
                     [(last (str/split (str (:path l)) #"/")) l]))]
    (mapv (fn [r]
            (let [l (when (:run/dir r) (kept (snapshot-name runner r)))]
              (cond-> r
                l (assoc :run/snapshot (:path l) :run/moment (:moment l) :run/recurs? true))))
          runs)))

(defn conversion-text
  "What a real batch's conversions did, one line for the log and the job's summary: snapshots
   kept, and the gaps by kind (the model did not reproduce the failure: never filed)."
  [lines]
  (str "real snapshots: " (count (filter #(= :real (:line/kind %)) lines))
       " kept of " (count lines)
       "; model gaps (not filed) "
       (pr-str (frequencies (keep :gap (filter #(= :gap (:line/kind %)) lines))))))

(defn judgeable
  "A sim batch's failures to judge: the failed sim runs (failures/failed) that kept a snapshot
   (:run/snapshot), the first per-signature of each signature."
  [runs]
  (->> (failures/failed runs)
       (filter #(and (= :sim (failures/source %)) (:run/snapshot %)))
       (group-by failures/key-of)
       vals
       (mapcat #(take per-signature %))))

(defn judge-line-of
  "A judge's JUDGE line (simrace/judge!) read, its map; nil for any other line, or one that does
   not read."
  [s]
  (when-let [[_ m] (re-matches #"JUDGE (.*)" s)]
    (try (edn/read-string {:default (fn [_ v] v)} m) (catch Exception _ nil))))

(defn recurred?
  "Did a JUDGE line's snapshot recur as evidence: it recurs, its play was not halted, and it was
   not slow (simrace/slow-ms: a verdict that near the budget would flip on a slower runner)?"
  [l]
  (boolean (and (:recurs? l) (not (:halted? l)) (not (:slow? l)))))

(defn judged-runs
  "runs with what a judge said (its JUDGE lines): each run whose snapshot recurred? gets
   :run/recurs?, what bb failures files."
  [runs lines]
  (let [ok (set (for [l lines :when (recurred? l)] (str (:path l))))]
    (mapv #(cond-> % (contains? ok (str (:run/snapshot %))) (assoc :run/recurs? true)) runs)))

(defn judging-text
  "What a sim batch's judges said, one line for the log and the job's summary: how many recurred,
   and how the rest did not (never filed)."
  [lines]
  (str "sim snapshots: " (count (filter recurred? lines))
       " recur of " (count lines)
       "; not filed "
       (pr-str (frequencies (keep #(cond (recurred? %) nil
                                         (:halted? %) :halted
                                         (and (:recurs? %) (:slow? %)) :slow
                                         :else :passed)
                                  lines)))))

(defn evidence-tag
  "The release a batch reported at now (an ISO instant) uploads its snapshots to:
   evidence-<UTC day>-<hour>. An hour, not a day: GitHub holds 1000 assets a release, and the
   comments of 2026-10-16 named about 2,000 snapshots in one day."
  [now]
  (str release-prefix (subs now 0 10) "-" (subs now 11 13)))

(defn file-of "A path's last component." [path] (last (str/split (str path) #"/")))

(defn asset-name
  "The name a run's snapshot is uploaded under: <run>-<file>. The file alone repeats: a sim
   snapshot is <set>-<world>-<seed> and a real one real-<runner>-<s>-<bot>, in every run of a
   lane; the run id is unique to one lane's job."
  [run r]
  (str run "-" (file-of (:run/snapshot r))))

(defn evidenced
  "runs once their snapshots were uploaded: each reproduced run (:run/recurs?) whose asset-name
   the release tag holds (assets, a set of names) gets :run/release and :run/asset, what its
   comment names (failures/snapshot-note); one whose upload failed loses :run/recurs?, so no
   comment names evidence that may vanish."
  [runs run tag assets]
  (mapv (fn [r]
          (let [a (asset-name run r)]
            (cond (not (:run/recurs? r)) r
                  (contains? assets a) (assoc r :run/release tag :run/asset a)
                  :else (dissoc r :run/recurs?))))
        runs))

(defn evidence-text
  "What a batch's upload did, one line for the log and the job's summary: of the reproduced runs
   before (evidenced's input), how many have their snapshot in release tag after, and how many
   did not upload (not filed)."
  [before after tag]
  (let [up (count (filter :run/release after))]
    (str "evidence: " up
         " snapshots in release " tag
         "; not uploaded (not filed) " (- (count (filter :run/recurs? before)) up))))

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

(defn budgeted!
  "Run clojure with args at nice level (under the lane's own work), its output to log, stopped at
   budget (ms: what it did not reach stays unreproduced, never filed); returns the log's lines."
  [log level budget args]
  (let [proc (apply p/process {:out log :err :inherit} "nice" "-n" (str level) "clojure" args)]
    (when-not (.waitFor ^Process (:proc proc)
                        (long budget)
                        java.util.concurrent.TimeUnit/MILLISECONDS)
      (p/destroy-tree proc)
      (println "churn: out of time:" (str log)))
    (str/split-lines (slurp log))))

(defn recording
  "A real run's recording while its lane plays: <data-dir>/<dir>/<bot>.edn."
  [data-dir r]
  (str (fs/path data-dir (:run/dir r) (str (:run/bot r) ".edn"))))

(defn convert!
  "Turn a real batch's failures (convertible, each recording's size read first) into snapshots:
   one clojure -M:simrace --from-recording over their recordings, its heap held to real-heap, at
   real-nice, writing <data-dir>/snapshots (gaps under its gaps/) and its lines to
   snapshots/convert-<n>.log (budgeted!). Returns the REAL and GAP lines it printed (line-of)."
  [{:keys [data-dir run runner budget]} n runs]
  (let [todo (convertible (mapv #(cond-> %
                                   (and (:run/dir %) (fs/exists? (recording data-dir %)))
                                   (assoc :run/bytes (fs/size (recording data-dir %))))
                                runs))
        out (fs/path data-dir snapshots-dir)]
    (when (seq todo)
      (fs/create-dirs out)
      (let [lines (vec (keep line-of
                             (budgeted! (fs/file out (str "convert-" n ".log"))
                                        real-nice
                                        budget
                                        [real-heap "-M:simrace" "--from-recording"
                                         (str/join "," (map #(recording data-dir %) todo)) "--name"
                                         (str/join "," (map #(snapshot-name runner %) todo)) "--out"
                                         (str out) "--round" (str run)])))]
        (println "churn:" (conversion-text lines) "of" (count todo) "to fold")
        lines))))

(defn judge!
  "Judge a sim batch's failures' snapshots (judgeable) as the fix's check will, on this commit:
   one clojure -M:simrace --judge over them, its heap held to sim-heap, at sim-nice, its lines
   to <data-dir>/snapshots/judge-<n>.log (budgeted!). Returns the JUDGE lines (judge-line-of)."
  [{:keys [data-dir budget]} n runs]
  (let [todo (filter #(fs/exists? (str (:run/snapshot %))) (judgeable runs)) ; one missing file would stop them all
        out (fs/path data-dir snapshots-dir)]
    (when (seq todo)
      (fs/create-dirs out)
      (let [lines (vec (keep judge-line-of
                             (budgeted! (fs/file out (str "judge-" n ".log"))
                                        sim-nice
                                        budget
                                        [sim-heap "-M:simrace" "--judge"
                                         (str/join "," (map #(str (:run/snapshot %)) todo))])))]
        (println "churn:" (judging-text lines))
        lines))))

(defn gh-repo "The repository gh acts on." [] (or (failures/repo) repo-default))

(defn release-assets
  "The asset names release tag holds, a set; nil when there is no such release."
  [tag]
  (let [r (p/shell {:out :string :err :string :continue true}
                   "gh" "release"
                   "view" tag
                   "--repo" (gh-repo)
                   "--json" "assets"
                   "--jq" ".assets[].name")]
    (when (zero? (:exit r)) (set (str/split-lines (:out r))))))

(defn upload!
  "Upload the reproduced runs' snapshots (:run/recurs?) to release tag for good, each copied
   under dir as its asset-name first; the release is made (a prerelease, never the latest, at
   sha) by the hour's first batch to need it, and a second lane making it at the same moment
   only fails to. Returns the asset names the release holds after, #{} when it holds none."
  [dir run sha tag runs]
  (let [copies (vec (for [r runs
                          :when (and (:run/recurs? r) (fs/exists? (str (:run/snapshot r))))]
                      [(str (:run/snapshot r)) (str (fs/path dir (asset-name run r)))]))
        files (mapv second copies)]
    (cond (empty? files) #{}
          :else
          (do (fs/create-dirs dir)
              (doseq [[from to] copies] (fs/copy from to {:replace-existing true}))
              (when-not (release-assets tag)
                (p/shell {:continue true}
                         "gh" "release"
                         "create" tag
                         "--repo" (gh-repo)
                         "--target" sha
                         "--prerelease" "--latest=false"
                         "--title" tag
                         "--notes"
                         (str "The snapshots the bot-failure issues' comments of this hour"
                              " name (bb churn): each a failure's moment the model"
                              " reproduced, <run>-<file>.")))
              (apply p/shell
                     {:continue true}
                     "gh" "release"
                     "upload" tag
                     "--repo" (gh-repo)
                     "--clobber" files)
              (or (release-assets tag) #{})))))

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
                real? (= :real (:kind job))
                said (if real? (convert! job n runs) (judge! job n runs))
                reproduced (cond (empty? said) runs
                                 real? (converted runs said (:runner job))
                                 :else (judged-runs runs said))
                tag (evidence-tag (str (java.time.Instant/now)))
                runs (evidenced reproduced
                                (:run job)
                                tag
                                (upload! (fs/path batches-dir (str n) "evidence")
                                         (:run job)
                                         (:sha job)
                                         tag
                                         reproduced))
                said-text (when (seq said)
                            (str (if real? (conversion-text said) (judging-text said))
                                 "\n"
                                 (evidence-text reproduced runs tag)))]
            (when said-text (println "churn:" (evidence-text reproduced runs tag)))
            (when-let [summary (and said-text (System/getenv "GITHUB_STEP_SUMMARY"))]
              (spit summary (str said-text "\n\n") :append true))
            (report! job n dir runs (:churn/counts after))
            (spit (str (fs/file data-dir state-file)) (pr-str after))))))

(defn -main
  "batch <kind> <data-dir> <artifact> <run> <sha> <code-at> <runner> [budget-s]: report the runs
   ended since the last batch, its conversions or judges stopped after budget-s (budget-ms when
   not given; a cancelled job's last report has a few minutes before GitHub kills it)."
  [& [cmd kind data-dir artifact run sha code-at runner budget-s]]
  (case cmd
    "batch"
    (batch! {:kind (keyword kind)
             :data-dir data-dir
             :artifact artifact
             :run (parse-long run)
             :sha sha
             :code-at code-at
             :runner (parse-long runner)
             :budget (or (some-> budget-s
                                 parse-long
                                 (* 1000))
                         budget-ms)})
    (do
      (println
       "usage: bb churn batch <real|sim> <data-dir> <artifact> <run> <sha> <code-at> <runner> [budget-s]")
      (System/exit 2))))
