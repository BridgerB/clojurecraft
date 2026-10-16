(ns clojurecraft.issue-check
  "A fix's fixtures (issue #77): the failing moments a bot-failure issue lists, brought down into
   the repository for the pull request's check to judge (issue.yml, simrace --judge).

     bb issue-check fetch <n>     the issue's snapshots into test/issues/<n>/

   The issue is the data: bb failures writes each reproduction into the issue's comments as its
   snapshot's file name and where it is, a sim run's or a real one the model reproduces (issue
   #299: a real race's snapshot, real-<runner>-<s>-<bot>.edn.gz). bb churn uploads each to the
   hour's evidence release before the comment names it (\"as `<run>-<file>` in release
   `evidence-<day>-<hour>`\": gh release download, kept for good); an older comment names its
   runner's artifact (gh run download, by name, from the run the artifact's name belongs to, each
   downloaded once; gone after 7 days). fetch reads the comments newest first, then the body, and
   takes at most max-snapshots of them, never two of one world (a snapshot is named
   <set>-<world>-<seed>: two seeds of one world are the same moment), only snapshots that carry a
   kept moment (:snapshot/moment, a moment from before the choice that failed), each under its
   file name. The pull request then commits them: the check is a judgement over committed
   fixtures, and a fixture never silently updates."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(def root "test/issues")
(def max-snapshots 2) ; an issue's check judges two moments, from two worlds

;; ---------------------------------------------------------------- the issue as values

(def note
  "A reproduction's snapshot as failures/snapshot-note writes it: the file, then its release's
   asset and tag or its artifact, then the kept moment when it has one."
  #"snapshot `([^`]+)` (?:as `([^`]+)` in release `([^`]+)`|in artifact `([^`]+)`)( \(moment )?")

(defn sources
  "The snapshots a text names, each once, in the order listed: {:file :release :asset} for one
   uploaded to an evidence release, {:file :artifact} for one in a runner's artifact, each with
   :moment? when its line says its kept moment."
  [body]
  (into []
        (distinct)
        (for [[_ f asset tag artifact moment] (re-seq note (str body))]
          (cond-> {:file f :moment? (some? moment)}
            tag (assoc :release tag :asset asset)
            artifact (assoc :artifact artifact)))))

(defn kept-snapshots
  "The snapshots a text names whose line says their kept moment (bb failures writes
   \"(moment m:ss before the stop, ...)\" after where it is), as sources: the only ones the check
   can judge, found without downloading anything."
  [body]
  (filterv :moment? (sources body)))

(defn place
  "Where a source is, in words."
  [{:keys [release asset artifact]}]
  (cond release (str "release " release " asset " asset)
        :else (str "artifact " artifact)))

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

(defn release-file!
  "Download asset of release tag into a temporary directory: its path, or nil when it is not
   there."
  [tag asset]
  (let [tmp (fs/create-temp-dir {:prefix "issue-check"})
        f (fs/path tmp asset)]
    (p/shell {:continue true}
             "gh" "release"
             "download" tag
             "--repo" (repo)
             "-p" asset
             "-D" (str tmp))
    (when (fs/exists? f) f)))

(defn fetch!
  "Bring issue n's snapshots into test/issues/<n>/ (see the namespace): the reproductions in its
   comments newest first, then its body, at most max-snapshots, one a world, only those with a
   kept moment, each from its release (release-file!) or its artifact (each artifact downloaded
   once into a temporary directory). Prints what it took and why it passed over the rest."
  [n]
  (let [found (kept-snapshots (str/join "\n" (issue-texts n)))
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
                       (when run tmp))))
        file-of (fn [{:keys [file release asset artifact]}]
                  (cond release (release-file! release asset)
                        :else
                        (some-> (dir-of artifact)
                                (fs/glob (str "**/" file))
                                first)))]
    (when (empty? found)
      (println
       "issue"
       n
       "lists no snapshot with a kept moment yet: the lanes file one with its next failure"))
    (fs/create-dirs dest)
    (reduce
     (fn [taken {:keys [file] :as s}]
       (let [world (world-of file)
             from (when (and (< (count taken) max-snapshots) (not (contains? taken world)))
                    (file-of s))]
         (cond (>= (count taken) max-snapshots) (reduced taken)
               (contains? taken world)
               (do (println "passed over" file ": world" world "taken already") taken)
               (nil? from)
               (do (println "not found:" file "in" (place s) "(an artifact expires after 7 days)")
                   taken)
               (not (kept-moment? from))
               (do (println "passed over" file ": no kept moment (an older snapshot)") taken)
               :else
               (do (fs/copy from (fs/path dest file) {:replace-existing true})
                   (println "fetched" (str (fs/path dest file)))
                   (conj taken world)))))
     #{}
     found)
    (println (count (fs/list-dir dest)) "snapshots under" (str dest))))

(defn -main
  "fetch <n>: the issue's snapshots into test/issues/<n>/."
  [& [cmd a]]
  (case cmd "fetch" (fetch! (parse-long a))))
