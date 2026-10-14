(ns clojurecraft.win
  "A win is a release (issue #116): when a run of the real race reaches the goal, the report job
   tags the commit and creates a GitHub release with the winning bots' recordings attached, so the
   proof outlives the artifacts' seven days and replays at its commit (bb replay).

     bb win release <dir> <run> <sha>     the winners under dir (RESULT :ok true), their recordings
                                           fetched from the run's race artifacts, one release

   Babashka runs this; nothing here requires the bot."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.string :as str]
            [clojurecraft.failures :as failures]))

(def tag-prefix "nether-")             ; a release a winning run: nether-<run id>

;; ---------------------------------------------------------------- the winners as values

(defn winners
  "The real runs that reached the goal: [{:bot name :runner n} ...], from the RESULT lines under
   dir (a racelog artifact is racelog-<sha>-<runner>)."
  [runs]
  (vec (for [r runs
             :when (and (:ok r) (= :real (failures/source r)))
             :let [[_ n] (re-matches #"racelog-[0-9a-f]+-(\d+)" (str (:run/artifact r)))]]
         {:bot (:run/bot r)
          :runner (some-> n
                          parse-long)})))

(defn notes
  "The release's notes: who won, where, and how to see it again."
  [run sha ws]
  (str "A bot reached the Nether in race run "
       run
       " at "
       (subs (str sha) 0 7)
       ".\n\n"
       (str/join "\n" (for [{:keys [bot runner]} ws] (str "- " bot " on runner " runner)))
       "\n\nEach recording attached replays with the code that made it: `bb replay <file>` "
       "(a worktree at "
       (subs (str sha) 0 7)
       ", every event and effect of the run; docs/CHURN.md).\n"))

;;;; I/O: the artifacts, gh ;;;;

(defn recordings!
  "Download the race artifacts the winners' runners made into dir and return the winners'
   recording files, one a bot."
  [run sha ws dir]
  (doseq [runner (distinct (keep :runner ws))]
    (p/shell {:continue true}
             "gh" "run"
             "download" (str run)
             "-n" (str "race-" sha "-" runner)
             "-D" (str (fs/path dir (str runner)))))
  (vec (for [{:keys [bot]} ws f (fs/glob dir (str "**/" bot ".edn.zst"))] (str f))))

(defn release!
  "Tag sha as nether-<run> and create the release with the winners' recordings; nothing when no
   run won. Prints what it did."
  [dir run sha]
  (let [ws (winners (failures/runs-under dir))]
    (cond (empty? ws) (println "no winner in" dir)
          :else
          (let [tag (str tag-prefix run)
                files (recordings! run sha ws (fs/create-dirs (str "win-" run)))]
            (apply
             p/shell
             "gh"
             "release"
             "create"
             tag
             "--target"
             sha
             "--title"
             (str (count ws) " bot" (when (> (count ws) 1) "s") " reached the Nether (run " run ")")
             "--notes"
             (notes run sha ws)
             files)
            (println "released" tag
                     "with" (count files)
                     "recordings:" (str/join ", " (map :bot ws)))))))

(defn -main
  "release <dir> <run> <sha>: the winners under dir become a release."
  [& [cmd dir run sha]]
  (case cmd
    "release" (release! dir run sha)
    (do (println "usage: bb win release <dir> <run> <sha>") (System/exit 2))))
