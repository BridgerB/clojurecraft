(ns clojurecraft.issue-check
  "A fix's fixtures (issue #77): the failing moments a bot-failure issue lists, brought down into
   the repository for the pull request's check to judge (issue.yml, simrace --judge).

     bb issue-check fetch <n>     the issue's snapshots into test/issues/<n>/

   The issue is the data: bb failures writes each sim reproduction into the issue's comments as
   its snapshot's file name and the artifact that holds it. fetch reads the comments newest
   first, then the body, and takes at most max-snapshots of them, never two of one world (a
   snapshot is named <set>-<world>-<seed>: two seeds of one world are the same moment), only
   snapshots that carry a kept moment (:snapshot/moment, a moment from before the choice that
   failed), each artifact downloaded once (gh run download, by name, from the run the artifact's
   name belongs to). The pull request then commits them: the check is a judgement over committed
   fixtures, and a fixture never silently updates."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(def root "test/issues")
(def max-snapshots 2) ; an issue's check judges two moments, from two worlds

;; ---------------------------------------------------------------- the issue as values

(defn snapshots
  "The snapshots a text names, [[file artifact] ...], each once, in the order listed."
  [body]
  (into []
        (distinct)
        (for [[_ f a] (re-seq #"snapshot `([^`]+)` in artifact `([^`]+)`" (str body))] [f a])))

(defn kept-snapshots
  "The snapshots a text names whose line says their kept moment (bb failures writes
   \"(moment m:ss before the stop, ...)\" after the artifact), [[file artifact] ...]: the only
   ones the check can judge, found without downloading anything."
  [body]
  (into []
        (distinct)
        (for [[_ f a] (re-seq #"snapshot `([^`]+)` in artifact `([^`]+)` \(moment " (str body))]
          [f a])))

(defn world-of
  "The world a snapshot's file name names: R4-35-2.edn.gz is world R4-35 (its seed, 2, does not
   change the moment); a real failure's (real-4-s2-Clj_r17.edn.gz, issue #299: one bot of one
   race, on a landing of its own) is its own world."
  [file]
  (cond (str/starts-with? file "real-") (str/replace file #"\.edn\.gz$" "")
        :else (second (re-find #"^(.+)-\d+\.edn\.gz$" file))))

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

(defn issue-texts
  "Issue n's comments, newest first, then its body: where its reproductions are, freshest first."
  [n]
  (concat (reverse (map :body
                        (apply concat
                               (json/parse-string
                                (gh "api" "--paginate"
                                    "--slurp" (str "repos/" (repo) "/issues/" n "/comments"))
                                true))))
          [(issue-body n)]))

(defn kept-moment?
  "Does the gzipped snapshot at path carry a kept moment (:snapshot/moment, printed inside the
   snapshot namespace map as :moment {:why ...})? Older snapshots,
   one tick before the stop, do not, and are not taken."
  [path]
  (with-open [in (java.util.zip.GZIPInputStream. (io/input-stream (str path)))]
    (boolean (re-find #"moment \{:why " (slurp in)))))

(defn run-of-artifact
  "The id of the run that produced the artifact named, or nil: artifacts are searched by name
   across the repository's recent runs."
  [artifact]
  (some-> (gh "api" (str "repos/" (repo) "/actions/artifacts?name=" artifact "&per_page=1"))
          (json/parse-string true)
          :artifacts first
          :workflow_run :id))

(defn fetch!
  "Bring issue n's snapshots into test/issues/<n>/ (see the namespace): the reproductions in its
   comments newest first, then its body, at most max-snapshots, one a world, only those with a
   kept moment, each artifact downloaded once into a temporary directory. Prints what it took and
   why it passed over the rest."
  [n]
  (let [pairs (kept-snapshots (str/join "\n" (issue-texts n)))
        dest (fs/path root (str n))
        downloaded (atom {})
        dir-of (fn [artifact]
                 (or (get @downloaded artifact)
                     (let [run (run-of-artifact artifact)
                           tmp (fs/create-temp-dir {:prefix "issue-check"})]
                       (when run
                         (p/shell {:continue true}
                                  "gh" "run"
                                  "download" (str run)
                                  "--repo" (repo)
                                  "-n" artifact
                                  "-D" (str tmp)))
                       (swap! downloaded assoc artifact (when run tmp))
                       (when run tmp))))]
    (when (empty? pairs)
      (println
       "issue"
       n
       "lists no snapshot with a kept moment yet: the lanes file one with its next failure"))
    (fs/create-dirs dest)
    (reduce (fn [taken [file artifact]]
              (let [world (world-of file)
                    tmp (when (and (< (count taken) max-snapshots) (not (contains? taken world)))
                          (dir-of artifact))
                    from (when tmp (first (fs/glob tmp (str "**/" file))))]
                (cond (>= (count taken) max-snapshots) (reduced taken)
                      (contains? taken world)
                      (do (println "passed over" file ": world" world "taken already") taken)
                      (nil? tmp)
                      (do (println "artifact" artifact "not found (expired after 7 days?)") taken)
                      (nil? from) (do (println "missing in" artifact ":" file) taken)
                      (not (kept-moment? from))
                      (do (println "passed over" file ": no kept moment (an older snapshot)") taken)
                      :else
                      (do (fs/copy from (fs/path dest file) {:replace-existing true})
                          (println "fetched" (str (fs/path dest file)))
                          (conj taken world)))))
            #{}
            pairs)
    (println (count (fs/list-dir dest)) "snapshots under" (str dest))))

(defn -main
  "fetch <n>: the issue's snapshots into test/issues/<n>/."
  [& [cmd a]]
  (case cmd "fetch" (fetch! (parse-long a))))
