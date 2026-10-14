(ns clojurecraft.issue-check
  "A fix's fixtures (issue #77): the failing moments a bot-failure issue lists, brought down into
   the repository for issues-test to play on.

     bb issue-check fetch <n>     the issue's snapshots into test/issues/<n>/

   The issue is the data: bb failures writes each sim reproduction as its snapshot's file name
   and the artifact that holds it. fetch downloads each artifact once (gh run download, by name,
   from the run the artifact's name belongs to) and copies the snapshots named into
   test/issues/<n>/, which the fix's pull request then commits: the check is a test over
   committed fixtures, and a fixture never silently updates."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [cheshire.core :as json]))

(def root "test/issues")

;; ---------------------------------------------------------------- the issue as values

(defn snapshots
  "The snapshots an issue body names, [[file artifact] ...], each once, in the order listed."
  [body]
  (into []
        (distinct)
        (for [[_ f a] (re-seq #"snapshot `([^`]+)` in artifact `([^`]+)`" (str body))] [f a])))

(defn by-artifact
  "{artifact [file ...]}: the snapshots to take from each artifact."
  [pairs]
  (reduce (fn [m [f a]] (update m a (fnil conj []) f)) {} pairs))

;;;; I/O: the GitHub API, the files ;;;;

(defn repo
  "The repository, owner/name."
  []
  (or (System/getenv "GITHUB_REPOSITORY") "BridgerB/clojurecraft"))

(defn gh
  "gh with args, its stdout; nil when it fails."
  [& args]
  (let [r (apply p/shell {:out :string :err :string :continue true} "gh" args)]
    (when (zero? (:exit r)) (:out r))))

(defn issue-body
  "Issue n's body."
  [n]
  (:body (json/parse-string (gh "api" (str "repos/" (repo) "/issues/" n)) true)))

(defn run-of-artifact
  "The id of the run that produced the artifact named, or nil: artifacts are searched by name
   across the repository's recent runs."
  [artifact]
  (some-> (gh "api" (str "repos/" (repo) "/actions/artifacts?name=" artifact "&per_page=1"))
          (json/parse-string true)
          :artifacts first
          :workflow_run :id))

(defn fetch!
  "Bring issue n's snapshots into test/issues/<n>/: each artifact downloaded once into a
   temporary directory, the named files copied over. Prints what landed and what could not be
   found."
  [n]
  (let [pairs (snapshots (issue-body n))
        dest (fs/path root (str n))]
    (when (empty? pairs)
      (println
       "issue"
       n
       "lists no snapshots: its runs predate #77, or the sim race has not run on this code"))
    (fs/create-dirs dest)
    (doseq [[artifact files] (by-artifact pairs)]
      (let [run (run-of-artifact artifact)
            tmp (fs/create-temp-dir {:prefix "issue-check"})]
        (cond (nil? run) (println "artifact" artifact "not found (expired after 7 days?)")
              :else
              (do (p/shell {:continue true}
                           "gh" "run"
                           "download" (str run)
                           "--repo" (repo)
                           "-n" artifact
                           "-D" (str tmp))
                  (doseq [f files]
                    (let [from (first (fs/glob tmp (str "**/" f)))]
                      (cond from
                            (do (fs/copy from (fs/path dest f) {:replace-existing true})
                                (println "fetched" (str (fs/path dest f))))
                            :else (println "missing in" artifact ":" f))))))))
    (println (count (fs/list-dir dest)) "snapshots under" (str dest))))

(defn -main
  "fetch <n>: the issue's snapshots into test/issues/<n>/."
  [& [cmd a]]
  (case cmd "fetch" (fetch! (parse-long a))))
