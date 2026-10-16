(ns clojurecraft.failures
  "Failures become issues: every failed run, the sim race's (runs.edn, a run map a line) and the
   real race's (race.log, a RESULT line a bot), grouped by its signature (failure/signature: the
   target it was on and why it stopped). One GitHub issue per signature, labelled bot-failure and
   found again by the signature it carries in a marker, open or closed; each round adds one
   comment to that issue (how many runs, the intents they stopped in, how many landings, up to
   five reproducible snapshots, the run's page) and brings the body's tally up to date (rounds,
   runs, first and last seen, when it was last reproduced), so a signature seen a thousand times
   is one issue with a tally, and every round notifies (issue #116).

   An issue is open only while it has a reproducible snapshot (issue #299, Bridger's rule): a
   signature is filed, commented or reopened only when a run of the batch has a snapshot that
   recurred against the model on the batch's own commit (:run/recurs?, judged by bb churn at batch
   time: a sim run's snapshot judged, a real run's recording converted), one such run being
   enough; the comment lists those snapshots and nothing else. Runs with no recurring snapshot
   are counted in the report's output (and on the page), never filed; so are runs that stopped
   for a reason that is not the bot's play (:run/infra?: the server closed the socket or
   disconnected it). An open issue last reproduced longer ago than retention-days (its evidence
   a week old: the snapshots stay in their release, but the code has moved on) is closed as not
   planned with closing-text, and a batch that reproduces it reopens it. A closed issue whose signature comes back in runs of code newer than
   its closing is reopened and its section says regressed; runs of older code (a round that
   started before the fix merged) add their section and leave it closed, since they never ran the
   fix. Each issue is labelled bot-failure and sim (the model reproduces it, so the fix's check
   judges its snapshots), and real when real runs hit it. Before an issue is opened the open and closed
   issues are read again, so two reports finishing together do not open the same one twice. At
   most max-new issues are opened a report, the most frequent signatures first.

     bb failures report <dir> <title> [code-at]   every run under dir; title heads this report's
                                                  sections; code-at, an ISO instant, is when the
                                                  code that ran was committed"
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.string :as str]))

(def label "bot-failure")             ; every failure issue carries it
(def infra-reasons #{:disconnected :closed}) ; not the bot's play, for runs that predate :run/infra?
(def retention-days 7)                ; an issue no batch reproduced for this long is closed (an artifact lives as long)
(def closing-text "closed: no reproducible snapshot; reopens when a batch reproduces it")
(def max-new 10)                      ; issues opened by one report, at most
(def examples 5)                      ; reproductions listed per signature per report

;; ---------------------------------------------------------------- runs, as values

(defn run-of-line
  "The run a line carries: a runs.edn line (a map), or a race.log RESULT line (RESULT <bot> {...})
   with the bot's name; nil for any other line or one that does not read."
  [line]
  (try (cond (str/starts-with? line "{") (edn/read-string {:default (fn [_ v] v)} line)
             (str/starts-with? line "RESULT ")
             (let [[_ bot m] (re-matches #"RESULT (\S+) (.*)" line)]
               (assoc (edn/read-string {:default (fn [_ v] v)} m) :run/bot bot))
             :else nil)
       (catch Exception _ nil)))

(defn infra?
  "Did a failed run stop for a reason that is not the bot's play?"
  [r]
  (boolean (or (:run/infra? r) (contains? infra-reasons (:reason r)))))

(defn key-of
  "The signature a run is filed under: [target reason] (a signature written with the last
   intent's kind as a third element is filed with the rest of its kind)."
  [r]
  (vec (take 2 (:run/signature r))))

(defn source
  "Where a run came from: :sim (a captured world against the model) or :real (a server)."
  [r]
  (if (:run/file r) :sim :real))

(defn failed
  "The runs that failed with a signature to file: no pass, no infrastructure stop."
  [runs]
  (filter (fn [r] (and (not (:ok r)) (:run/signature r) (not (infra? r)))) runs))

(defn grouped
  "Failed runs by signature, the most frequent first: [[signature runs] ...]."
  [runs]
  (sort-by (comp - count second) (group-by key-of (failed runs))))

(defn marker
  "The line that finds a signature's issue again."
  [sig]
  (str "<!-- signature " (pr-str sig) " -->"))

(defn title
  "An issue's title: the target and the reason, in words."
  [[stage reason]]
  (str "Bot stops at "
       (some-> stage
               name)
       ": "
       (some-> reason
               name)))

(defn reproduced
  "The runs whose snapshot recurred against the model on the batch's own commit (:run/recurs?,
   bb churn): the only evidence an issue is filed on."
  [runs]
  (filter :run/recurs? runs))

(defn labels
  "An issue's labels for runs: bot-failure, and sim and/or real by where they came from; sim too
   when a real run's failure was kept as a recurring snapshot (issue #299): the model reproduces
   it, so the fix's check can judge it and requires one."
  [runs]
  (into [label]
        (map name)
        (sort (distinct (concat (map source runs) (when (seq (reproduced runs)) [:sim]))))))

(defn clock
  "Simulated ms as m:ss."
  [ms]
  (let [s (quot (long ms) 1000)] (format "%d:%02d" (quot s 60) (rem s 60))))

(defn moment-note
  "A kept moment in words: how long before the stop, why, and the window it is judged over
   (simrace/chosen-moment's :moment); nil without one."
  [{:keys [why t stop-t window-ms]}]
  (when why
    (str " (moment "
         (clock (- stop-t t))
         " before the stop, "
         why
         ", judged over "
         (clock window-ms)
         ")")))

(defn snapshot-note
  "A kept snapshot in a reproduction's words, the shape bb issue-check fetch looks for: \"snapshot
   `f` as `asset` in release `tag` (moment ...)\" once bb churn uploaded it for good (:run/release,
   :run/asset), else \"snapshot `f` in artifact `a` (moment ...)\", the runner's artifact, uploaded
   at the job's end and gone after retention-days; nil when the run kept none."
  [r]
  (let [file (last (str/split (str (:run/snapshot r)) #"/"))
        moment (moment-note (:run/moment r))]
    (cond
      (:run/release r)
      (str "snapshot `" file "` as `" (:run/asset r) "` in release `" (:run/release r) "`" moment)
      (:run/snapshot r) (str "snapshot `" file "` in artifact `" (:run/artifact r) "`" moment)
      :else nil)))

(defn repro
  "One line that reproduces a run whose snapshot recurred (reproduced): its snapshot (a moment
   kept from before the choice that failed, in the hour's evidence release, snapshot-note: bb
   issue-check fetch brings it down, and the fix's check judges it), then for a sim run the sim race's command that made
   it, for a real one its bot under its server's directory (:run/dir, s1 or s2: each server's
   bots have the same names). The replay line of a real run's recording is gone (issue #299): a
   replay judges nothing."
  [r]
  (cond (:run/file r)
        (str "- " (snapshot-note r)
             "; or `clojure -M:simrace --world " (:run/file r)
             " --seed " (:run/seed r)
             " --seeds 1` at " (:run/commit r)
             ", landing " (pr-str (get-in r [:run/world :capture/landing])))
        :else
        (str "- "
             (snapshot-note r)
             "; real race: bot "
             (:run/bot r)
             (some->> (:run/dir r)
                      (str " in ")))))

(defn section
  "This report's section for one signature: how many runs, how many of them reproduced against
   the model, sim or real, the intents they stopped in, how many landings, and the reproducible
   snapshots (repro, reproduced runs only); regressed when its issue had been closed."
  [report-title runs regressed?]
  (str/join "\n"
            (concat
             [(str "### " report-title
                   ": " (count runs)
                   " run" (when (> (count runs) 1) "s")
                   ", " (count (reproduced runs))
                   " reproduced" (when regressed? " (regressed: this issue was closed)")) ""
              (str "From: " (pr-str (frequencies (map source runs)))
                   "; last intents: "
                   (pr-str (frequencies (map #(get-in % [:plan :plan/last :intent/kind]) runs)))
                   "; landings: "
                   (count (distinct (keep #(get-in % [:run/world :capture/landing]) runs)))) ""]
             (map repro (take examples (reproduced runs)))
             [""])))

(defn regressed?
  "Did a closed issue come back: closed for want of a reproducible snapshot (state_reason
   not_planned, closing-text: any batch that reproduces it reopens it), or the runs' code was
   committed (code-at, an ISO instant) after the issue was closed? A round whose code predates
   the fix never ran it, so its failures say nothing about the fix; with no code-at every closed
   issue counts as come back."
  [issue code-at]
  (and (= "closed" (:state issue))
       (or (= "not_planned" (:state_reason issue))
           (nil? code-at)
           (nil? (:closed_at issue))
           (pos? (compare code-at (:closed_at issue))))))

(defn opening
  "A new issue's body before its tally."
  [sig]
  (str
   (marker sig)
   "\n\n"
   "Runs stopped with this signature (`clojurecraft.failure/signature`: the target, the reason): `"
   (pr-str sig)
   "`. Each round that hits it adds one comment: how many runs, where, and how to reproduce them.\n\n"))

(def tally-marker "<!-- tally ")           ; the line that keeps the issue's count across rounds

(defn tally-of
  "The tally a body carries, {:rounds n :runs m :first title :last title :reproduced instant},
   or nil."
  [body]
  (some->> (re-find #"<!-- tally (\{.*?\}) -->" (str body))
           second
           (edn/read-string {:default (fn [_ v] v)})))

(defn tallied
  "The tally after one more round of runs under report-title, reproduced at now (an ISO instant:
   the newest reproducible snapshot's batch, which stale? measures from)."
  [tally report-title runs now]
  (-> (or tally {:rounds 0 :runs 0 :first report-title})
      (update :rounds inc)
      (update :runs + (count runs))
      (assoc :last report-title :reproduced now)))

(defn stale?
  "Is an open issue past its evidence at now (an ISO instant): last reproduced (its tally's
   :reproduced) more than retention-days ago, so no batch of the last week saw it (an older
   comment's artifact has expired by then; a release keeps its snapshots)? An issue whose tally
   never said (filed before issue #299) is left to a person."
  [issue now]
  (let [at (:reproduced (tally-of (:body issue)))]
    (boolean (and (= "open" (:state issue))
                  at
                  (.isBefore (java.time.Instant/parse at)
                             (.minus (java.time.Instant/parse now)
                                     (java.time.Duration/ofDays retention-days)))))))

(defn tally-lines
  "The tally as the body shows it: a sentence, then the marker that keeps it."
  [{:keys [rounds runs first last reproduced]}]
  (str "Seen in "
       rounds
       " round"
       (when (not= 1 rounds) "s")
       ", "
       runs
       " run"
       (when (not= 1 runs) "s")
       ": first in "
       first
       ", last in "
       last
       (when reproduced (str "; last reproduced " reproduced))
       ".\n"
       tally-marker
       (pr-str (cond-> {:rounds rounds :runs runs :first first :last last}
                 reproduced (assoc :reproduced reproduced)))
       " -->"))

(defn with-tally
  "The body with its tally lines replaced, or, for a body from before tallies (its reports in the
   body), put in after the opening paragraph; the old reports stay below."
  [body tally]
  (let [lines (tally-lines tally)]
    (cond (re-find #"(?m)^Seen in .*\n<!-- tally .*? -->" (str body))
          (str/replace-first (str body)
                             #"(?m)^Seen in .*\n<!-- tally .*? -->"
                             (str/re-quote-replacement lines))
          :else
          (let [[marker-line explanation & more] (str/split (str body) #"\n\n" 3)]
            (str/join "\n\n" (remove nil? (concat [marker-line explanation lines] more)))))))

(defn comment-text
  "One round's comment on an issue: the section, headed by the run's page when the workflow
   says where it runs (run-url)."
  [section run-url]
  (str section (when run-url (str "\nRun: " run-url "\n"))))

(defn summary-lines
  "What a report prints beyond the issues: the infrastructure stops, and the signatures no run
   of the batch reproduced (no recurring snapshot: never filed), with the job's runs of each so
   far (counts, when given)."
  [runs groups counts]
  (concat [(str "infrastructure stops (not filed): "
                (pr-str (frequencies (map :reason (filter #(and (not (:ok %)) (infra? %)) runs)))))]
          (for [[sig rs] groups
                :when (empty? (reproduced rs))]
            (str "not reproduced (not filed): "
                 (count rs)
                 " "
                 (pr-str sig)
                 " ("
                 (get counts sig (count rs))
                 " in the job)"))))

;;;; I/O: the files, the GitHub API ;;;;

(defn runs-under
  "Every run under dir: the lines of each runs.edn and race.log, each run saying which artifact
   it came from (:run/artifact, the directory under dir the file sits in: what the report job
   downloaded each artifact into) and, when the file sits deeper, the directory inside the
   artifact (:run/dir: a real race's s1 or s2, where its bot's recording is)."
  [dir]
  (into []
        (comp (filter #(#{"runs.edn" "race.log"} (fs/file-name %)))
              (mapcat (fn [f]
                        (let [[artifact & inner] (map str (fs/components (fs/relativize dir f)))
                              sub (str/join "/" (butlast inner))]
                          (map #(some-> (run-of-line %)
                                        (assoc :run/artifact artifact)
                                        (cond-> (seq sub) (assoc :run/dir sub)))
                               (str/split-lines (slurp (fs/file f)))))))
              (remove nil?))
        (fs/glob dir "**")))

(defn gh!
  "gh api with args, its JSON answer read, or nil when it fails."
  [& args]
  (let [r (apply p/shell {:out :string :err :string :continue true} "gh" "api" args)]
    (when (zero? (:exit r)) (json/parse-string (:out r) true))))

(defn repo "The repository the workflow runs in." [] (System/getenv "GITHUB_REPOSITORY"))

(defn issues
  "Every issue labelled label, open and closed: [{:number :body :state :closed_at}]."
  []
  (into []
        cat
        (take-while
         seq
         (map #(gh! (str "repos/" (repo) "/issues?state=all&labels=" label "&per_page=100&page=" %))
              (iterate inc 1)))))

(defn issue-for
  "The issue carrying sig's marker, the open one first, or nil."
  [all sig]
  (first (sort-by #(if (= "open" (:state %)) 0 1)
                  (filter #(str/includes? (str (:body %)) (marker sig)) all))))

(defn run-url
  "The page of the run the report runs in, from the workflow's environment, or nil."
  []
  (let [server (System/getenv "GITHUB_SERVER_URL")
        run (System/getenv "GITHUB_RUN_ID")]
    (when (and server (repo) run) (str server "/" (repo) "/actions/runs/" run))))

(defn comment!
  "Post one round's comment on issue n."
  [n text]
  (gh! "-X" "POST" (str "repos/" (repo) "/issues/" n "/comments") "-f" (str "body=" text)))

(defn close!
  "Close issue n as not planned with closing-text: no reproducible snapshot is left."
  [n]
  (comment! n closing-text)
  (gh! "-X"
       "PATCH" (str "repos/" (repo) "/issues/" n)
       "-f" "state=closed"
       "-f" "state_reason=not_planned"))

(defn file!
  "One signature's round, runs with a reproducible snapshot among them: one comment on its issue
   (reopened when closed and come back, regressed?) with the body's tally brought up to date
   (reproduced now), or a new issue with its first comment. The body is the summary (first seen,
   last seen, last reproduced, rounds, runs), the comments are the rounds, so every round
   notifies (issue #116). Returns :added, :reopened or :opened."
  [report-title sig runs code-at now]
  (let [existing (issue-for (issues) sig) ; read again now: another report may have just opened it
        closed? (regressed? existing code-at)
        text (comment-text (section report-title runs closed?) (run-url))
        tally (tallied (tally-of (:body existing)) report-title runs now)]
    (cond existing
          (do (apply gh!
                     "-X"
                     "PATCH"
                     (str "repos/" (repo) "/issues/" (:number existing))
                     "-f"
                     (str "body=" (with-tally (:body existing) tally))
                     (when closed? ["-f" "state=open" "-f" "state_reason=reopened"]))
              (comment! (:number existing) text)
              (doseq [l (labels runs)]
                (gh! "-X"
                     "POST" (str "repos/" (repo) "/issues/" (:number existing) "/labels")
                     "-f" (str "labels[]=" l)))
              (if closed? :reopened :added))
          :else
          (let [made (apply gh!
                            "-X"
                            "POST"
                            (str "repos/" (repo) "/issues")
                            "-f"
                            (str "title=" (title sig))
                            "-f"
                            (str "body=" (opening sig) (tally-lines tally))
                            (mapcat (fn [l] ["-f" (str "labels[]=" l)]) (labels runs)))]
            (when (:number made) (comment! (:number made) text))
            :opened))))

(defn sweep!
  "Close every open issue past its evidence (stale? at now, close!); print each."
  [now]
  (doseq [i (issues)
          :when (stale? i now)]
    (close! (:number i))
    (println "closed" (:number i)
             ": no reproducible snapshot since" (:reproduced (tally-of (:body i))))))

(defn report-runs!
  "File runs (values): every signature with a reproduced run (one is enough), the most frequent
   first, opening at most max-new issues; print what was filed and what was not (summary-lines:
   the job's counts so far of each, {signature n}, when given); then close the open issues past
   their evidence (sweep!). A run is reproduced only when bb churn judged its snapshot to recur
   at batch time, so bb failures report over a directory files nothing new."
  [runs report-title code-at counts]
  (doseq [[l color] [[label "d93f0b"] ["sim" "1d76db"] ["real" "0e8a16"]]]
    (gh! "-X"
         "POST" (str "repos/" (repo) "/labels")
         "-f" (str "name=" l)
         "-f" (str "color=" color)))
  (let [groups (grouped runs)
        now (str (java.time.Instant/now))]
    (println "runs:" (count runs)
             "failed:" (count (mapcat second groups))
             "reproduced:" (count (reproduced runs))
             "signatures:" (count groups))
    (doseq [line (summary-lines runs groups counts)] (println line))
    (reduce (fn [opened [sig rs]]
              (cond (or (empty? (reproduced rs)) ; no evidence: counted, never filed
                        (and (>= opened max-new) (nil? (issue-for (issues) sig)))) ; no room for a new issue
                    opened
                    :else
                    (let [what (file! report-title sig rs code-at now)]
                      (println (count rs) (pr-str sig) (name what))
                      (if (= :opened what) (inc opened) opened))))
            0
            groups)
    (sweep! now)))

(defn report!
  "File the failed runs under dir (report-runs!)."
  [dir report-title code-at]
  (report-runs! (runs-under dir) report-title code-at nil))

(defn -main
  "report <dir> <title> [code-at]: file the failures under dir as issues."
  [& [cmd dir report-title code-at]]
  (case cmd "report" (report! dir report-title code-at)))
