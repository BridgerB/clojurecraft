(ns clojurecraft.failures
  "Failures become issues: every failed run, the sim race's (runs.edn, a run map a line) and the
   real race's (race.log, a RESULT line a bot), grouped by its signature (failure/signature: the
   target it was on and why it stopped). One GitHub issue per signature, labelled bot-failure and
   found again by the signature it carries in a marker, open or closed; each report adds one
   section to that issue's body (how many runs, the intents they stopped in, how many landings,
   up to five reproductions), so a signature seen a thousand times is one issue with a tally.

   What is not filed: a run that stopped for a reason that is not the bot's play
   (:run/infra?: the server closed the socket or disconnected it), counted in the report's
   output only; and a signature fewer than min-runs runs hit, a one-off, listed but not filed.
   A closed issue whose signature comes back is reopened and its section says regressed. Each
   issue is labelled sim, real or both by where its runs came from: a failure only the model
   produces may be the model's, not the bot's. Before an issue is opened the open and closed
   issues are read again, so two reports finishing together do not open the same one twice. At
   most max-new issues are opened a report, the most frequent signatures first.

     bb failures report <dir> <title>   every run under dir; title heads this report's sections"
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

(defn repro
  "One line that reproduces a run: the sim race's command for a sim run, the recording's replay
   for a real one."
  [r]
  (cond (:run/file r)
        (str "- `clojure -M:simrace --world " (:run/file r)
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

(defn opening
  "A new issue's body before its first section."
  [sig]
  (str
   (marker sig)
   "\n\n"
   "Runs stopped with this signature (`clojurecraft.failure/signature`: the target, the reason): `"
   (pr-str sig)
   "`. Each report below adds how many runs hit it, where, and how to reproduce them.\n\n"))

(defn summary-lines
  "What a report prints beyond the issues: the infrastructure stops and the one-offs, counted."
  [runs groups]
  (concat [(str "infrastructure stops (not filed): "
                (pr-str (frequencies (map :reason (filter #(and (not (:ok %)) (infra? %)) runs)))))]
          (for [[sig rs] groups
                :when (< (count rs) min-runs)]
            (str "one-off (not filed): " (count rs) " " (pr-str sig)))))

;;;; I/O: the files, the GitHub API ;;;;

(defn runs-under
  "Every run under dir: the lines of each runs.edn and race.log."
  [dir]
  (into []
        (comp (filter #(#{"runs.edn" "race.log"} (fs/file-name %)))
              (mapcat #(str/split-lines (slurp (fs/file %))))
              (keep run-of-line))
        (fs/glob dir "**")))

(defn gh!
  "gh api with args, its JSON answer read, or nil when it fails."
  [& args]
  (let [r (apply p/shell {:out :string :err :string :continue true} "gh" "api" args)]
    (when (zero? (:exit r)) (json/parse-string (:out r) true))))

(defn repo "The repository the workflow runs in." [] (System/getenv "GITHUB_REPOSITORY"))

(defn issues
  "Every issue labelled label, open and closed: [{:number :body :state}]."
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

(defn file!
  "One signature's section: added to its issue (reopened when closed), or a new issue. Returns
   :added, :reopened or :opened."
  [report-title sig runs]
  (let [existing (issue-for (issues) sig) ; read again now: another report may have just opened it
        closed? (= "closed" (:state existing))
        text (section report-title runs closed?)]
    (cond existing
          (do (gh! "-X"
                   "PATCH" (str "repos/" (repo) "/issues/" (:number existing))
                   "-f" (str "body=" (:body existing) "\n" text)
                   "-f" (str "state=" "open"))
              (doseq [l (labels runs)]
                (gh! "-X"
                     "POST" (str "repos/" (repo) "/issues/" (:number existing) "/labels")
                     "-f" (str "labels[]=" l)))
              (if closed? :reopened :added))
          :else
          (do (apply gh!
                     "-X"
                     "POST"
                     (str "repos/" (repo) "/issues")
                     "-f"
                     (str "title=" (title sig))
                     "-f"
                     (str "body=" (opening sig) text)
                     (mapcat (fn [l] ["-f" (str "labels[]=" l)]) (labels runs)))
              :opened))))

(defn report!
  "File every signature hit at least min-runs times, the most frequent first, opening at most
   max-new issues; print what was filed and what was not."
  [dir report-title]
  (doseq [[l color] [[label "d93f0b"] ["sim" "1d76db"] ["real" "0e8a16"]]]
    (gh! "-X"
         "POST" (str "repos/" (repo) "/labels")
         "-f" (str "name=" l)
         "-f" (str "color=" color)))
  (let [runs (runs-under dir)
        groups (grouped runs)]
    (println "runs:" (count runs)
             "failed and filed-able:" (count (mapcat second groups))
             "signatures:" (count groups))
    (doseq [line (summary-lines runs groups)] (println line))
    (reduce (fn [opened [sig rs]]
              (cond (or (< (count rs) min-runs) ; a one-off
                        (and (>= opened max-new) (nil? (issue-for (issues) sig)))) ; no room for a new issue
                    opened
                    :else
                    (let [what (file! report-title sig rs)]
                      (println (count rs) (pr-str sig) (name what))
                      (if (= :opened what) (inc opened) opened))))
            0
            groups)))

(defn -main
  "report <dir> <title>: file the failures under dir as issues."
  [& [cmd dir report-title]]
  (case cmd "report" (report! dir report-title)))
