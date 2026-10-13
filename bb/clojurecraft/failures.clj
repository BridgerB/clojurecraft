(ns clojurecraft.failures
  "Failures become issues: every failed run, the sim race's (runs.edn, a run map a line) and the
   real race's (race.log, a RESULT line a bot), grouped by its :run/signature (failure/signature:
   the target it was on, why it stopped, the last intent's kind). One GitHub issue per signature,
   labelled bot-failure and found again by the signature it carries in a marker; each report adds
   one section to that issue's body (how many runs, where, and up to five reproductions), so a
   signature seen a thousand times is one issue with a tally, never a thousand issues. At most
   max-new issues are opened a report, the most frequent signatures first.

     bb failures report <dir> <title>   every run under dir; title heads this report's sections"
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.string :as str]))

(def label "bot-failure")             ; every failure issue carries it
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

(defn failed
  "The runs that failed with a signature to group by."
  [runs]
  (filter (fn [r] (and (not (:ok r)) (:run/signature r))) runs))

(defn grouped
  "Failed runs by signature, the most frequent first: [[signature runs] ...]."
  [runs]
  (sort-by (comp - count second) (group-by :run/signature (failed runs))))

(defn marker
  "The line that finds a signature's issue again."
  [sig]
  (str "<!-- signature " (pr-str sig) " -->"))

(defn title
  "An issue's title: the target, the reason and the intent, in words."
  [[stage reason kind]]
  (str "Bot stops at "
       (some-> stage
               name)
       ": "
       (some-> reason
               name)
       (when kind (str " (" (name kind) ")"))))

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
  "This report's section for one signature: how many runs, from where, and reproductions."
  [report-title runs]
  (str/join "\n"
            (concat
             [(str "### " report-title ": " (count runs) " run" (when (> (count runs) 1) "s")) ""
              (str "Landings: "
                   (count (distinct (keep #(get-in % [:run/world :capture/landing]) runs)))
                   ", reasons as printed: " (pr-str (frequencies (map :reason runs)))) ""]
             (map repro (take examples runs))
             [""])))

(defn opening
  "A new issue's body before its first section."
  [sig]
  (str
   (marker sig)
   "\n\n"
   "Runs stopped with this signature (`clojurecraft.failure/signature`: the target, the reason, the last intent): `"
   (pr-str sig)
   "`. Each report below adds how many runs hit it and how to reproduce them.\n\n"))

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

(defn open-issues
  "Every open issue labelled label: [{:number :body}]."
  []
  (into []
        cat
        (take-while seq
                    (map #(gh! (str "repos/" (repo)
                                    "/issues?state=open&labels=" label
                                    "&per_page=100&page=" %))
                         (iterate inc 1)))))

(defn report!
  "File every signature's section: the open issue that carries its marker gets the section added
   to its body; a signature with none gets a new issue, max-new at most."
  [dir report-title]
  (gh! "-X" "POST" (str "repos/" (repo) "/labels") "-f" (str "name=" label) "-f" "color=d93f0b")
  (let [issues (open-issues)
        groups (grouped (runs-under dir))]
    (println "failures:" (count (mapcat second groups)) "runs," (count groups) "signatures")
    (reduce (fn [opened [sig runs]]
              (let [text (section report-title runs)
                    existing (first (filter #(str/includes? (str (:body %)) (marker sig)) issues))]
                (println (count runs) (pr-str sig) (if existing (str "#" (:number existing)) "new"))
                (cond existing
                      (do (gh! "-X"
                               "PATCH" (str "repos/" (repo) "/issues/" (:number existing))
                               "-f" (str "body=" (:body existing) "\n" text))
                          opened)
                      (< opened max-new)
                      (do (gh! "-X"
                               "POST" (str "repos/" (repo) "/issues")
                               "-f" (str "title=" (title sig))
                               "-f" (str "body=" (opening sig) text)
                               "-f" (str "labels[]=" label))
                          (inc opened))
                      :else opened)))
            0
            groups)))

(defn -main
  "report <dir> <title>: file the failures under dir as issues."
  [& [cmd dir report-title]]
  (case cmd "report" (report! dir report-title)))
