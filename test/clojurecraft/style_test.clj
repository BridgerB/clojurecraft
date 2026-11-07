(ns clojurecraft.style-test
  "RICH-HICKEY.md style rules that a test can hold."
  (:require [clojure.edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [clojure.walk :as walk]))

(def if-family '#{if if-let if-not if-some})

(defn forms
  "Every top-level form of every .clj file under dir, as [file form]."
  [dir]
  (for [f (sort (map str (filter #(str/ends-with? (str %) ".clj") (file-seq (io/file dir)))))
        form (binding [*read-eval* false
                       *ns* (create-ns 'clojurecraft.style-test.scratch)]
               (with-open [r (java.io.PushbackReader. (io/reader f))]
                 (doall (take-while #(not= % ::eof)
                                    (repeatedly #(read {:eof ::eof :read-cond :allow} r))))))]
    [f form]))

(defn nested-ifs
  "[file name] for each top-level form holding an if (or if-let, if-not, if-some) whose branch is
   itself one: cond reads better."
  [dir]
  (for [[f form] (forms dir)
        :when (seq? form)
        :let [hits (atom 0)]
        :when (do (walk/postwalk (fn [x]
                                   (when (and (seq? x)
                                              (if-family (first x))
                                              (some #(and (seq? %) (if-family (first %)))
                                                    (drop 2 x)))
                                     (swap! hits inc))
                                   x)
                                 form)
                  (pos? @hits))]
    [f (second form)]))

(deftest cond-over-nested-if
  (is (= [] (vec (concat (nested-ifs "src/clojurecraft") (nested-ifs "dev/clojurecraft"))))))

(defn without-reflection-warnings
  "Every .clj file under dir that does not set *warn-on-reflection* right after its ns form: Java
   interop that falls back to reflection is slow and silent, so every namespace asks to be told."
  [dir]
  (for [[f forms] (group-by first (forms dir))
        :when (not= '(set! *warn-on-reflection* true) (second (second (vec forms))))]
    f))

(deftest every-namespace-warns-on-reflection
  (is (= []
         (vec (concat (without-reflection-warnings "src/clojurecraft")
                      (without-reflection-warnings "dev/clojurecraft"))))))

;;; Incident history and global redefinition: held by a ratchet. A file may hold no more such
;;; lines than style_baseline.edn records for it (none when it is not listed); the baseline only
;;; ever shrinks, as the history moves to problems.edn's :problem/seen and the issues, and as
;;; with-redefs gives way to arguments and rows of a test's own.

(def history-pattern
  "A line that names a run, race, probe or batch by id, or a numbered race round of a kind the
   issues keep: the story belongs on the issue, the data in problems.edn."
  #"(plan run|race|probe|run|job|jobs) \d{8,}|blaze-race-|pearls-warped|\b(?:batch|gym batch) [a-z][a-z0-9]*-[a-z0-9-]*")

(def redefs-pattern
  "A line using with-redefs, which rebinds a var for the whole JVM."
  #"with-redefs")

(defn clj-files
  "Every .clj file under the dirs, as paths."
  [dirs]
  (for [d dirs f (file-seq (io/file d)) :when (str/ends-with? (str f) ".clj")] (str f)))

(defn line-counts
  "{path n} for each .clj file under dirs with n lines matching pattern (n > 0)."
  [dirs pattern]
  (into (sorted-map)
        (for [f (clj-files dirs)
              :let [n (with-open [r (io/reader f)]
                        (count (filter #(re-find pattern %) (line-seq r))))]
              :when (pos? n)]
          [f n])))

(def baseline (clojure.edn/read-string (slurp "test/clojurecraft/style_baseline.edn")))

(defn grown
  "[path found allowed] for each file whose count of lines matching pattern is above its allowance."
  [dirs pattern allowed]
  (for [[f n] (line-counts dirs pattern)
        :let [limit (get allowed f 0)]
        :when (> n limit)]
    [f n limit]))

(deftest no-new-incident-history-in-src-or-test
  (is (= []
         (vec (grown ["src/clojurecraft" "dev/clojurecraft" "test/clojurecraft"]
                     history-pattern
                     (:history baseline))))
      "a run id or batch name in a comment: put it in problems.edn :problem/seen or the issue"))

(deftest no-new-with-redefs-in-tests
  (is (= [] (vec (grown ["test/clojurecraft"] redefs-pattern (:redefs baseline))))
      "pass the dependency as an argument, or give the test a row of its own plan key"))
