(ns clojurecraft.probe-run
  "A code question on a runner (probe.yml, AGENTS.md section 8) as two commands, so no agent
   hand-copies the dispatch, the lookup and the download.

     bb probe run <label> '<command>' [worlds]   dispatch probe.yml for the current branch; prints
                                                 the run id, or why it would not dispatch
     bb probe log <id>                           when the run is done: its artifact into
                                                 /tmp/probe-<id> (cleared first) and out/run.log
                                                 printed; when not, its status

   run refuses a branch that is not pushed as it stands (the runner checks out origin's branch,
   nothing local) and a label another run already has (the label is how the run is found).
   worlds is R1,R2,R3,R4 or a subset, for snapshots and simrace runs. The repository is named on
   every gh call, so the commands work from the monorepo's worktrees too."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [cheshire.core :as json]
            [clojure.string :as str]))

(def repo "BridgerB/clojurecraft")

;;;; I/O: git and gh ;;;;

(defn sh
  "A command's trimmed stdout, or nil when it fails."
  [& args]
  (let [r (apply p/shell {:out :string :err :string :continue true} args)]
    (when (zero? (:exit r)) (str/trim (:out r)))))

(defn runs
  "probe.yml's recent runs, [{:databaseId :displayTitle :status :conclusion}]."
  []
  (json/parse-string (sh "gh"
                         "run" "list"
                         "--repo" repo
                         "--workflow" "probe.yml"
                         "--limit" "50"
                         "--json" "databaseId,displayTitle,status,conclusion")
                     true))

(defn titled
  "The id of the newest probe run titled `probe <label>`, or nil."
  [label]
  (:databaseId (first (filter #(= (str "probe " label) (:displayTitle %)) (runs)))))

(defn dispatch!
  "Dispatch probe.yml for the current branch and print the run's id; refuses (printing why) an
   unpushed branch or a label in use."
  [label command worlds]
  (let [branch (sh "git" "branch" "--show-current")
        head (sh "git" "rev-parse" "HEAD")
        pushed (some-> (sh "git" "ls-remote" "--heads" "origin" (str branch))
                       (str/split #"\s")
                       first)]
    (cond
      (str/blank? branch) (println "not on a branch: switch to a pushed probe/<n>-<word> branch")
      (not= head pushed) (println branch "is not pushed as it stands: commit and git push first")
      (titled label) (println "label" label "is taken by run" (titled label) ": pick another word")
      :else
      (do (sh "gh" "workflow"
              "run" "probe.yml"
              "--repo" repo
              "--ref" branch
              "-f" (str "run=" command)
              "-f" (str "worlds=" (or worlds "none"))
              "-f" (str "label=" label))
          ;; the run is listed a few seconds after the dispatch
          (let [id (reduce
                    (fn [_ _] (Thread/sleep 3000) (when-let [id (titled label)] (reduced id)))
                    nil
                    (range 20))]
            (cond id
                  (println (str "probe "
                                label
                                " is run "
                                id
                                "\n  bb probe log "
                                id
                                "      when it is done"
                                "\n  gh run view "
                                id
                                " --repo "
                                repo
                                " --json status,conclusion"))
                  :else
                  (println "dispatched, but no run titled probe"
                           label
                           "after a minute: gh run list --workflow probe.yml")))))))

(defn log!
  "Print a finished run's out/run.log after downloading its artifact into /tmp/probe-<id>; when it
   is not finished, print its status."
  [id]
  (let [{:keys [status conclusion]}
        (json/parse-string (sh "gh" "run" "view" id "--repo" repo "--json" "status,conclusion")
                           true)
        dir (str "/tmp/probe-" id)]
    (cond (not= "completed" status) (println "run" id "is" status)
          :else
          (do (fs/delete-tree dir)
              (sh "gh" "run" "download" id "--repo" repo "-n" (str "probe-" id) "-D" dir)
              (println "run"
                       id
                       conclusion
                       "; files:"
                       (str/join " " (map #(str (fs/relativize dir %)) (fs/glob dir "**"))))
              (cond (fs/exists? (fs/path dir "run.log"))
                    (print (slurp (str (fs/path dir "run.log"))))
                    :else
                    (println
                     "no run.log in the artifact (a run before run.log existed: see probe.log)"))
              (flush)))))

(defn -main
  "run <label> <command> [worlds] | log <id>."
  [& [cmd a b c]]
  (case cmd
    "run" (dispatch! a b c)
    "log" (log! a)
    (println "bb probe run <label> '<command>' [worlds] | bb probe log <id>")))
