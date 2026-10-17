(ns clojurecraft.verify
  "A fix's whole check in one command, for a Copilot session (bot-fixer profile) or anyone:

     bb verify <n>    the issue's snapshots judged on the working tree, the suite without the
                      forest property, and every static check; a short verdict at the end

   A session that ran these one at a time started each in the background and asked after it again
   and again, every question re-sending its whole context (2026-10-17: 461 such polls in 32
   sessions, 53 million input tokens, a third of the day's). One command run to its end prints
   only what a fix needs: which snapshots still recur, which tests fail, which checks complain.
   Each step's full output stays in /tmp/verify-<step>.log for a closer look.

   The snapshots are test/issues/<n>/*.edn.gz, else cache/issues/<n>/*.edn.gz (the brief's)."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.edn :as edn]
            [clojure.string :as str]))

(def fail-lines 40) ; the lines of a failing step kept in the verdict

;; ---------------------------------------------------------------- the verdict as values

(defn judge-verdict
  "What the judge's output says of each snapshot: [{:path :recurs?}] from its JUDGE lines (each an
   EDN map, simrace/judge!)."
  [out]
  (for [line (str/split-lines (str out))
        :when (str/starts-with? line "JUDGE ")
        :let [m (edn/read-string {:default (fn [_ v] v)} (subs line 6))]]
    {:path (:path m) :recurs? (boolean (:recurs? m))}))

(defn test-verdict
  "The suite's summary line and its FAIL and ERROR blocks, at most fail-lines lines; nil summary
   when it never printed one (it crashed)."
  [out]
  (let [lines (str/split-lines (str out))]
    {:summary (last (filter #(re-find #"\d+ failures, \d+ errors" %) lines))
     :failures (take fail-lines (drop-while #(not (re-find #"^(FAIL|ERROR) in" %)) lines))}))

(defn ok-test?
  "Did the suite pass: a summary of 0 failures and 0 errors."
  [{:keys [summary]}]
  (boolean (and summary (re-find #"\b0 failures, 0 errors" summary))))

(defn report
  "The verdict's lines from the steps' results: judged (judge-verdict), tests (test-verdict),
   check {:exit :out}; the last line VERIFY READY, or VERIFY NOT READY with what is left."
  [{:keys [snapshots judged tests check]}]
  (let [recurring (filter :recurs? judged)
        unjudged (- (count snapshots) (count judged))
        test-ok (ok-test? tests)
        check-ok (zero? (:exit check))
        left (cond-> []
               (empty? snapshots) (conj "no snapshots under test/issues/<n>/ or cache/issues/<n>/")
               (seq recurring) (conj (str (count recurring) " snapshot(s) still recur"))
               (pos? unjudged) (conj (str unjudged " snapshot(s) not judged (the judge crashed)"))
               (not test-ok) (conj "tests fail")
               (not check-ok) (conj "bb check fails"))]
    (concat ["== snapshots, judged on this tree (none may recur)"]
            (for [{:keys [path recurs?]} judged] (str (if recurs? "RECURS  " "passes  ") path))
            ["" "== tests (FOREST_TRIALS=0 clojure -M:test)"
             (or (:summary tests) "no summary: the suite crashed (see /tmp/verify-test.log)")]
            (:failures tests)
            ["" (str "== bb check: " (if check-ok "clean" "fails"))]
            (when-not check-ok (take-last fail-lines (str/split-lines (str (:out check)))))
            [""
             (cond (empty? left) "VERIFY READY"
                   :else (str "VERIFY NOT READY: " (str/join "; " left)))])))

;;;; I/O: the three steps, their logs ;;;;

(defn step!
  "Run cmd (a vector) with env added, its stdout and stderr together in /tmp/verify-<step>.log:
   {:exit :out}."
  [step env cmd]
  (println (str "-- " step ": " (str/join " " cmd)))
  (let [r (apply p/shell {:out :string :err :out :continue true :extra-env env} cmd)]
    (spit (str "/tmp/verify-" step ".log") (:out r))
    (select-keys r [:exit :out])))

(defn snapshots
  "The issue's snapshots: test/issues/<n>/, else cache/issues/<n>/."
  [n]
  (let [of (fn [dir] (sort (map str (fs/glob dir "*.edn.gz"))))]
    (or (seq (of (str "test/issues/" n))) (of (str "cache/issues/" n)))))

(defn verify!
  "Run the three steps for issue n and print the verdict; exit 1 unless ready."
  [n]
  (let [snaps (snapshots n)
        judged (when (seq snaps)
                 (judge-verdict
                  (:out
                   (step! "judge" {} ["clojure" "-M:simrace" "--judge" (str/join "," snaps)]))))
        tests (test-verdict (:out (step! "test" {"FOREST_TRIALS" "0"} ["clojure" "-M:test"])))
        check (step! "check" {} ["bb" "check"])
        lines (report {:snapshots snaps :judged judged :tests tests :check check})]
    (run! println lines)
    (when-not (= "VERIFY READY" (last lines)) (System/exit 1))))

(defn -main
  "bb verify <n>."
  [& [n]]
  (cond (and n (re-matches #"\d+" n)) (verify! n)
        :else (println "usage: bb verify <issue number>")))
