(ns clojurecraft.failures
  "Failures become issues: every failed run, the sim race's (runs.edn, a run map a line) and the
   real race's (race.log, a RESULT line a bot), grouped by its signature (failure/signature: the
   target it was on and why it stopped). One GitHub issue per signature, labelled bot-failure and
   found again by the signature it carries in a marker, open or closed; each round adds one
   comment to that issue (how many runs, the intents they stopped in, how many landings, up to
   five reproductions, the run's page) and brings the body's tally up to date (rounds, runs,
   first and last seen), so a signature seen a thousand times is one issue with a tally, and
   every round notifies (issue #116).

   What is not filed: a run that stopped for a reason that is not the bot's play
   (:run/infra?: the server closed the socket or disconnected it), counted in the report's
   output only; and a signature fewer than min-runs runs hit, a one-off, listed but not filed.
   A closed issue whose signature comes back in runs of code newer than its closing is reopened
   and its section says regressed; runs of older code (a round that started before the fix merged)
   add their section and leave it closed, since they never ran the fix. Each
   issue is labelled sim, real or both by where its runs came from: a failure only the model
   produces may be the model's, not the bot's. Before an issue is opened the open and closed
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
(def min-runs 3)                      ; a signature fewer runs hit is a one-off: listed, not filed
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

(defn labels
  "An issue's labels for runs: bot-failure, and sim and/or real by where they came from."
  [runs]
  (into [label] (map name) (sort (distinct (map source runs)))))

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

(defn repro
  "One line that reproduces a run: for a sim run its snapshot (a moment kept from before the
   choice that failed, in the runner's artifact: bb issue-check fetch brings it down, and the
   fix's check judges it) and the sim race's command that made it; the recording's replay for a
   real one."
  [r]
  (cond (:run/file r)
        (str "- "
             (when (:run/snapshot r)
               (str "snapshot `"
                    (last (str/split (str (:run/snapshot r)) #"/"))
                    "` in artifact `"
                    (:run/artifact r)
                    "`"
                    (moment-note (:run/moment r))
                    "; or "))
             "`clojure -M:simrace --world " (:run/file r)
             " --seed " (:run/seed r)
             " --seeds 1` at " (:run/commit r)
             ", landing " (pr-str (get-in r [:run/world :capture/landing])))
        :else
        (str "- real race: bot "
             (:run/bot r)
             ", replay its recording with `clojure -M:replay <artifact>/"
             (:run/bot r)
             ".edn`")))

(defn section
  "This report's section for one signature: how many runs, sim or real, the intents they stopped
   in, how many landings, and reproductions; regressed when its issue had been closed."
  [report-title runs regressed?]
  (str/join "\n"
            (concat
             [(str "### "
                   report-title
                   ": "
                   (count runs)
                   " run"
                   (when (> (count runs) 1) "s")
                   (when regressed? " (regressed: this issue was closed)")) ""
              (str "From: " (pr-str (frequencies (map source runs)))
                   "; last intents: "
                   (pr-str (frequencies (map #(get-in % [:plan :plan/last :intent/kind]) runs)))
                   "; landings: "
                   (count (distinct (keep #(get-in % [:run/world :capture/landing]) runs)))) ""]
             (map repro (take examples runs))
             [""])))

(defn regressed?
  "Did a closed issue come back: the runs' code was committed (code-at, an ISO instant) after the
   issue was closed? A round whose code predates the fix never ran it, so its failures say
   nothing about the fix; with no code-at every closed issue counts as come back."
  [issue code-at]
  (and (= "closed" (:state issue))
       (or (nil? code-at) (nil? (:closed_at issue)) (pos? (compare code-at (:closed_at issue))))))

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
  "The tally a body carries, {:rounds n :runs m :first title :last title}, or nil."
  [body]
  (some->> (re-find #"<!-- tally (\{.*?\}) -->" (str body))
           second
           (edn/read-string {:default (fn [_ v] v)})))

(defn tallied
  "The tally after one more round of runs under report-title."
  [tally report-title runs]
  (-> (or tally {:rounds 0 :runs 0 :first report-title})
      (update :rounds inc)
      (update :runs + (count runs))
      (assoc :last report-title)))

(defn tally-lines
  "The tally as the body shows it: a sentence, then the marker that keeps it."
  [{:keys [rounds runs first last]}]
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
       ".\n"
       tally-marker
       (pr-str {:rounds rounds :runs runs :first first :last last})
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
  "What a report prints beyond the issues: the infrastructure stops and the one-offs, counted
   (by counts, the job's runs so far, when given)."
  [runs groups counts]
  (concat [(str "infrastructure stops (not filed): "
                (pr-str (frequencies (map :reason (filter #(and (not (:ok %)) (infra? %)) runs)))))]
          (for [[sig rs] groups
                :when (< (get counts sig (count rs)) min-runs)]
            (str "one-off (not filed): " (count rs) " " (pr-str sig)))))

;;;; I/O: the files, the GitHub API ;;;;

(defn runs-under
  "Every run under dir: the lines of each runs.edn and race.log, each run saying which artifact
   it came from (:run/artifact, the directory under dir the file sits in: what the report job
   downloaded each artifact into)."
  [dir]
  (into []
        (comp (filter #(#{"runs.edn" "race.log"} (fs/file-name %)))
              (mapcat (fn [f]
                        (let [artifact (first (fs/components (fs/relativize dir f)))]
                          (map #(some-> (run-of-line %)
                                        (assoc :run/artifact (str artifact)))
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

(defn file!
  "One signature's round: one comment on its issue (reopened when closed and the runs' code is
   newer than its closing, regressed?) with the body's tally brought up to date, or a new issue
   with its first comment. The body is the summary (first seen, last seen, rounds, runs), the
   comments are the rounds, so every round notifies (issue #116). Returns :added, :reopened or
   :opened."
  [report-title sig runs code-at]
  (let [existing (issue-for (issues) sig) ; read again now: another report may have just opened it
        closed? (regressed? existing code-at)
        text (comment-text (section report-title runs closed?) (run-url))
        tally (tallied (tally-of (:body existing)) report-title runs)]
    (cond existing
          (do (apply gh!
                     "-X"
                     "PATCH"
                     (str "repos/" (repo) "/issues/" (:number existing))
                     "-f"
                     (str "body=" (with-tally (:body existing) tally))
                     (when closed? ["-f" "state=open"]))
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

(defn report-runs!
  "File runs (values): every signature hit at least min-runs times, the most frequent first,
   opening at most max-new issues; print what was filed and what was not. counts, when given, is
   how many runs of each signature the job has had so far ({signature n}): a churn batch is judged
   by those (issue #127), so a signature a few runs a batch hit is still filed once it adds up;
   with no counts, by runs alone."
  [runs report-title code-at counts]
  (doseq [[l color] [[label "d93f0b"] ["sim" "1d76db"] ["real" "0e8a16"]]]
    (gh! "-X"
         "POST" (str "repos/" (repo) "/labels")
         "-f" (str "name=" l)
         "-f" (str "color=" color)))
  (let [groups (grouped runs)
        seen (fn [sig rs] (get counts sig (count rs)))]
    (println "runs:" (count runs)
             "failed and filed-able:" (count (mapcat second groups))
             "signatures:" (count groups))
    (doseq [line (summary-lines runs groups counts)] (println line))
    (reduce (fn [opened [sig rs]]
              (cond (or (< (seen sig rs) min-runs) ; a one-off, so far
                        (and (>= opened max-new) (nil? (issue-for (issues) sig)))) ; no room for a new issue
                    opened
                    :else
                    (let [what (file! report-title sig rs code-at)]
                      (println (count rs) (pr-str sig) (name what))
                      (if (= :opened what) (inc opened) opened))))
            0
            groups)))

(defn report!
  "File the failed runs under dir (report-runs!)."
  [dir report-title code-at]
  (report-runs! (runs-under dir) report-title code-at nil))

(defn -main
  "report <dir> <title> [code-at]: file the failures under dir as issues."
  [& [cmd dir report-title code-at]]
  (case cmd "report" (report! dir report-title code-at)))
