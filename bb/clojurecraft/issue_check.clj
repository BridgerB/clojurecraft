(ns clojurecraft.issue-check
  "A fix's proof (issue.yml): the runs a bot-failure issue lists as its reproductions, played
   again with the fix, none ending with the issue's signature.

     bb issue-check plan <n> <dir>               dir/runs.edn ([[world-file seed] ...]) and
                                                 dir/check.edn ({:issue :signature :runs})
     bb issue-check verdict <dir> <runs.edn>     exit 1 when a run still ends with the signature

   The issue is the data: its marker (<!-- signature [...] -->, bb failures writes it) names the
   signature, and its sections list each reproduction as the sim race's own command (clojure
   -M:simrace --world f --seed s --seeds 1). A run of the model is a pure function of world,
   seed and code, so the same world and seed with the fix is the same run but for the fix."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.string :as str]))

;; ---------------------------------------------------------------- the issue as values

(defn signature
  "The signature an issue body's marker names, or nil."
  [body]
  (some-> (re-find #"<!-- signature (\[[^\]]*\]) -->" (str body))
          second
          edn/read-string))

(defn reproductions
  "The sim runs an issue body lists, [[world-file seed] ...], each once, in the order listed."
  [body]
  (into []
        (distinct)
        (for [[_ f s] (re-seq #"clojure -M:simrace --world (\S+) --seed (\d+) --seeds 1"
                              (str body))]
          [f (parse-long s)])))

(defn ended-with
  "Whether a run (a runs.edn map) ended with signature: its first two parts, the target and the
   reason, are the signature's."
  [signature run]
  (= (vec (take 2 signature)) (vec (take 2 (:run/signature run)))))

(defn unproven?
  "Does a run prove nothing either way: it failed without a signature to compare?"
  [run]
  (and (not (:ok run)) (nil? (:run/signature run))))

(defn verdict
  "{:pass? :still :endings} for the runs played against an issue's signature and the n runs it
   lists: a pass when all n were played, none ended with the signature and none failed unsigned.
   :still the runs that ended with it, :endings how every run ended ([world seed ok reason
   signature])."
  [signature n runs]
  {:pass?
   (and (= n (count runs)) (not-any? #(ended-with signature %) runs) (not-any? unproven? runs))
   :still (filterv #(ended-with signature %) runs)
   :endings (mapv (fn [r] [(:run/file r) (:run/seed r) (:ok r) (:reason r) (:run/signature r)])
                  runs)})

;;;; I/O: the GitHub API, the files ;;;;

(defn issue-body
  "Issue n's body, read with gh."
  [n]
  (:body (json/parse-string (:out (p/shell {:out :string}
                                           "gh"
                                           "api"
                                           (str "repos/" (System/getenv "GITHUB_REPOSITORY")
                                                "/issues/" n)))
                            true)))

(defn plan!
  "Write issue n's reproductions and signature into dir; throw when it has none to play."
  [n dir]
  (let [body (issue-body n)
        sig (signature body)
        runs (reproductions body)]
    (when-not sig (throw (ex-info (str "issue " n " names no signature") {})))
    (when (empty? runs)
      (throw (ex-info (str "issue " n " lists no sim reproduction") {:signature sig})))
    (fs/create-dirs dir)
    (spit (str dir "/runs.edn") (pr-str runs))
    (spit (str dir "/check.edn") (pr-str {:issue n :signature sig :runs runs}))
    (println "issue" n "signature" (pr-str sig) "-" (count runs) "reproductions:")
    (doseq [r runs] (println " " (pr-str r)))))

(defn verdict!
  "Judge the runs in runs-file against the check in dir; print every run's ending; exit 1 when one
   still ends with the issue's signature, or none was played."
  [dir runs-file]
  (let [{:keys [issue signature runs]} (edn/read-string (slurp (str dir "/check.edn")))
        played (into []
                     (keep #(try (edn/read-string {:default (fn [_ v] v)} %)
                                 (catch Exception _ nil)))
                     (str/split-lines (slurp runs-file)))
        v (verdict signature (count runs) played)]
    (println "issue"
             issue
             (pr-str signature)
             "-"
             (count played)
             "of"
             (count runs)
             "reproductions played")
    (doseq [e (:endings v)] (println " " (pr-str e)))
    (println (if (:pass? v)
               (str "PASS: all " (count runs)
                    " reproductions of issue " issue
                    " played, none ends with " (pr-str signature))
               (str "FAIL: "
                    (count played)
                    " of "
                    (count runs)
                    " played, "
                    (count (:still v))
                    " still end with "
                    (pr-str signature)
                    ", "
                    (count (filter unproven? played))
                    " failed with no signature")))
    (when-not (:pass? v) (System/exit 1))))

(defn -main
  "plan <n> <dir> | verdict <dir> <runs.edn>."
  [& [cmd a b]]
  (case cmd
    "plan" (plan! (parse-long a) b)
    "verdict" (verdict! a b)))
