(ns clojurecraft.race-board
  "The race's one comment on its pull request (race.yml), edited in place as bots reach milestones.

     bb race-board create <title>              print the new comment's id (GH_TOKEN, GITHUB_REPOSITORY)
     ... | bb race-board follow <comment-id>   publish each MILESTONE or RACE line read from stdin

   The comment is a set of facts, one EDN map per line in its edn block: a milestone
   ({:milestone/runner :milestone/bot :milestone/goal :milestone/at ...}) or a runner's end
   ({:race/runner :race/ok :race/reasons ...}). Facts are only ever added, so twenty runners can
   write the one comment with no lock: each reads it, adds its facts, writes it back, reads it
   again, and starts over until its facts are there (another runner's write between its read and
   its write lost them). Merging is a union, so any order of writes ends with every fact. What the
   comment shows above the facts (the first bot to each milestone, each runner's furthest, the
   Nether arrivals) is rendered from the facts every time, never stored."
  (:require [babashka.process :as p]
            [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.string :as str]))

(def tries 10)                          ; reads, merges and writes before a publish gives up
(def fence "```")

;; ---------------------------------------------------------------- the comment as values

(defn title "The comment's first line." [body] (first (str/split-lines (str body))))

(defn facts
  "The set of facts in a comment's edn block."
  [body]
  (let [lines (str/split-lines (str body))
        block (->> lines
                   (drop-while #(not= (str fence "edn") %))
                   rest
                   (take-while #(not= fence %)))]
    (into #{} (keep #(try (edn/read-string %) (catch Exception _ nil))) block)))

(defn when-utc
  "A fact's time as HH:MM:SS UTC."
  [ms]
  (subs (str (java.time.Instant/ofEpochMilli ms)) 11 19))

(defn render
  "The comment for title and facts: the Nether arrivals, the first bot to each milestone in the
   order they were first reached, each runner's furthest bot, the runners that ended, then every
   fact by time."
  [title facts]
  (let [ms (sort-by :milestone/at (filter :milestone/goal facts))
        ends (sort-by :race/runner (filter :race/runner facts))
        firsts (->> (group-by :milestone/goal ms)
                    vals
                    (map first)
                    (sort-by :milestone/at))
        furthest (->> (group-by (juxt :milestone/runner :milestone/bot) ms)
                      (map (fn [[[runner bot] fs]]
                             {:runner runner :bot bot :n (count fs) :latest (last fs)}))
                      (group-by :runner)
                      (map (fn [[_ xs]] (apply max-key :n xs)))
                      (sort-by :runner))
        nether (filter #(= :nether (:milestone/goal %)) ms)
        row (fn [& cells] (str "| " (str/join " | " cells) " |"))]
    (str/join
     "\n"
     (concat [title ""
              (if (seq nether)
                (str "**Nether: "
                     (str/join ", "
                               (map #(str (:milestone/bot %)
                                          " on runner " (:milestone/runner %)
                                          " at " (when-utc (:milestone/at %)))
                                    nether))
                     "**")
                "Nether: none yet") "" "**First to each milestone**" ""
              (row "milestone" "bot" "runner" "at (UTC)" "after landing")
              (row "---" "---" "---" "---" "---")]
             (for [f firsts]
               (row (name (:milestone/goal f))
                    (:milestone/bot f)
                    (:milestone/runner f)
                    (when-utc (:milestone/at f))
                    (str (:milestone/after-s f "?") " s")))
             ["" "**Furthest bot per runner**" "" (row "runner" "bot" "milestones" "last")
              (row "---" "---" "---" "---")]
             (for [{:keys [runner bot n latest]} furthest]
               (row runner bot n (name (:milestone/goal latest))))
             [""
              (str "**Runners ended:** "
                   (count ends)
                   (when (seq ends)
                     (str " ("
                          (str/join ", " (map #(str (:race/runner %) ": " (:race/ok %) " ok") ends))
                          ")"))) "" "<details><summary>facts</summary>" "" (str fence "edn")]
             (map pr-str (sort-by (fn [f] [(:milestone/at f Long/MAX_VALUE) (pr-str f)]) facts))
             [fence "</details>"]))))

(defn line-fact
  "The fact a race log line carries, for a runner: a MILESTONE line's map, a RACE line's summary
   as a runner's end (no time on it: published twice, once live and once when the job ends, it is
   one fact), or nil for any other line."
  [runner line]
  (cond (str/starts-with? line "MILESTONE ") (edn/read-string (subs line 10))
        (str/starts-with? line "RACE ")
        (let [s (edn/read-string (subs line 5))]
          {:race/runner runner :race/ok (:race/ok s) :race/reasons (:race/reasons s)})
        :else nil))

;;;; I/O: the GitHub API ;;;;

(defn gh!
  "gh api with args, its JSON answer read, or nil when it fails."
  [& args]
  (let [r (apply p/shell {:out :string :err :string :continue true} "gh" "api" args)]
    (when (zero? (:exit r)) (json/parse-string (:out r) true))))

(defn repo
  "The repository the workflow runs in, owner/name."
  []
  (System/getenv "GITHUB_REPOSITORY"))

(defn body!
  "The comment's body now, or nil."
  [id]
  (:body (gh! (str "repos/" (repo) "/issues/comments/" id))))

(defn publish!
  "Add new facts to comment id: read, merge, write, read again; start over until they are in the
   comment or tries run out. Returns whether they are in."
  [id new]
  (loop [n 0]
    (let [body (body! id)
          merged (into (facts body) new)]
      (gh! "-X"
           "PATCH" (str "repos/" (repo) "/issues/comments/" id)
           "-f" (str "body=" (render (title body) merged)))
      (cond (every? (facts (body! id)) new) true
            (>= n tries) (do (binding [*out* *err*] (println "race-board: gave up on" new)) false)
            :else (do (Thread/sleep (long (+ 500 (rand-int 2500)))) (recur (inc n)))))))

(defn -main
  "create <title>: open the race's comment on the race branch's pull request and print its id.
   follow <id> <runner>: publish every MILESTONE and RACE line on stdin as it comes."
  [& [cmd a b]]
  (case cmd
    "create"
    (when-let [pr (:number (first (gh! (str "repos/"
                                            (repo)
                                            "/pulls?head="
                                            (first (str/split (repo) #"/"))
                                            ":race&state=open"))))]
      (println (:id (gh! (str "repos/" (repo) "/issues/" pr "/comments")
                         "-f"
                         (str "body=" (render a #{}))))))
    "follow"
    (doseq [line (line-seq (java.io.BufferedReader. *in*))
            :let [fact (line-fact (parse-long b) line)]
            :when fact]
      (publish! a #{fact}))))
