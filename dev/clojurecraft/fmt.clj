(ns clojurecraft.fmt
  "The formatter: zprint over the repo, with the settings in .zprint.edn.

     clojure -M:fmt fix              ; every file under the paths below, rewritten in place
     clojure -M:fmt check            ; exit 1 and name the files that would change
     clojure -M:fmt fix a.clj b.edn  ; just these (the pre-commit hook passes the staged files)

   Only Clojure and EDN files are formatted, and never the generated tables or the recording
   corpus, which their writers own."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [zprint.core :as zprint]))

(set! *warn-on-reflection* true)

(def paths
  "Where a bare fix or check looks: the code, the tests, the tooling and the hand-written data."
  ["src" "dev" "test/clojurecraft" "repl" "bb" "test-bb" "bb.edn" "deps.edn" "ci" ".zprint.edn"
   "resources/clojurecraft/goals.edn" "resources/clojurecraft/problems.edn"])

(def formatted? #"\.(clj|cljc|cljs|edn)$")      ; what a path must end in to be formatted

(def generated
  "Files their writers own: the tables bb datagen writes, and recorded runs."
  #"resources/clojurecraft/(blocks|components|drops|entity-types|fuel|hardness|harvest|item-tags|items|materials|menus|needs-tool|packets|recipes|smelting|version)\.edn$|test/recordings/")

(defn files
  "Every file to format under the given paths (files or directories), sorted, as strings."
  [paths]
  (->> paths
       (mapcat #(file-seq (io/file %)))
       (filter #(.isFile ^java.io.File %))
       (map str)
       (filter #(re-find formatted? %))
       (remove #(re-find generated %))
       sort
       distinct))

(defn formatted
  "The text of a file as zprint lays it out under options."
  [options path text]
  (zprint/zprint-file-str text path options))

;;;; I/O: reading the settings, the files, and the exit code ;;;;

(defn -main
  "fix or check the given files, or every file under paths."
  [mode & args]
  (let [options (edn/read-string (slurp ".zprint.edn"))
        changed (into []
                      (keep (fn [path]
                              (let [text (slurp path)
                                    out (formatted options path text)]
                                (when (not= text out) [path out]))))
                      (files (or (seq args) paths)))]
    (doseq [[path out] changed]
      (when (= mode "fix") (spit path out))
      (println (str (if (= mode "fix") "formatted " "needs formatting ") path)))
    (shutdown-agents)
    (System/exit (if (and (= mode "check") (seq changed)) 1 0))))
