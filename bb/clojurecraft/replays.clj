(ns clojurecraft.replays
  "bb replay: fold a recording with the code that made it (issue #111).

     bb replay <file> [--here]     file: run.edn, run.edn.gz, or a race artifact's run.edn.zst

   A recording replays only with the code that made it: newer code decides otherwise and the
   bot ends up elsewhere, which reads as a truncated file. Issue #111 was that: every race
   recording it named was whole, ended at its RESULT, and replayed :identical at its own commit.
   The recording's first line names that code ({:record/commit sha}), so this checks it out in a
   detached worktree under .worktrees/<sha> (once) and folds there; --here folds with the
   working tree's code instead, to see what a change would decide. A .zst is unpacked beside
   itself first (the JVM's reader takes .edn and .edn.gz).

   The namespace is clojurecraft.replays because clojurecraft.replay is the JVM's entry point.
   Babashka runs this; the fold itself is the JVM (clojure -M:replay)."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [babashka.tasks :as tasks]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.util.zip GZIPInputStream]))

(def worktrees ".worktrees")           ; detached checkouts, one per commit replayed at; ignored by git

;; ---------------------------------------------------------------- the recording's code

(defn commit-of
  "The commit a recording's first line names ({:record/commit sha}), or nil: an older recording,
   or one made without --commit."
  [first-line]
  (let [m (try (edn/read-string {:default (fn [_ v] v)} (str first-line)) (catch Exception _ nil))]
    (when (map? m) (:record/commit m))))

(defn where
  "Whose code to fold with: :here for the working tree (asked for, or no commit named, or the
   named commit is HEAD), else :worktree for a checkout of the named commit."
  [here? named head]
  (if (or here? (nil? named) (= named head)) :here :worktree))

;;;; I/O: the file, git, the JVM ;;;;

(defn unpacked!
  "The recording as the JVM reads it: a .zst is unpacked beside itself once (zstd on PATH, as
   the flake and the runners have it); anything else is returned as given."
  [path]
  (if (str/ends-with? path ".zst")
    (let [out (subs path 0 (- (count path) 4))]
      (when-not (fs/exists? out) (p/shell "zstd" "-dq" "-o" out path))
      out)
    path))

(defn first-line
  "The first line of a recording, through gzip when it ends in .gz."
  [path]
  (with-open [r (io/reader
                 (if (str/ends-with? path ".gz") (GZIPInputStream. (io/input-stream path)) path))]
    (.readLine ^java.io.BufferedReader r)))

(defn head
  "The working tree's commit."
  []
  (str/trim (:out (p/shell {:out :string} "git" "rev-parse" "HEAD"))))

(defn worktree!
  "A detached worktree of commit sha under .worktrees, made once; a commit not here yet is
   fetched from origin first."
  [sha]
  (let [dir (str worktrees "/" (subs sha 0 12))]
    (when-not (fs/exists? dir)
      (when-not (zero? (:exit (p/shell {:continue true :out :string :err :string}
                                       "git" "cat-file"
                                       "-e" (str sha "^{commit}"))))
        (p/shell "git" "fetch" "-q" "origin" sha))
      (p/shell "git" "worktree" "add" "--detach" dir sha))
    dir))

(defn -main
  "bb replay <file> [--here]: print the replayed RESULT, folded with the recording's own code
   unless --here."
  [& args]
  (let [here? (boolean (some #{"--here"} args))
        file (first (remove #{"--here"} args))]
    (when-not file
      (binding [*out* *err*] (println "usage: bb replay <run.edn|run.edn.gz|run.edn.zst> [--here]"))
      (System/exit 2))
    (let [path (unpacked! file)
          named (commit-of (first-line path))
          abs (str (fs/absolutize path))
          say (fn [& words] (binding [*out* *err*] (apply println "replay:" words)))]
      (case (where here? named (head))
        :here
        (do (say (cond here? "folding with the working tree's code, as asked"
                       (nil? named)
                       "the recording names no commit: folding with the working tree's code"
                       :else
                       (str "folding with its own code, " (subs named 0 7) ", which is HEAD")))
            (tasks/clojure "-M:replay" abs))
        :worktree
        (let [dir (worktree! named)]
          (say "folding with its own code," (subs named 0 7) "in" dir)
          (tasks/clojure {:dir dir} "-M:replay" abs))))))
