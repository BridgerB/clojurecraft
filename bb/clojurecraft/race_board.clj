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
(def batch-ms 10000)                    ; how often the follower publishes what has gathered
(def last-rounds 30)                    ; batches tried for the last facts once the race's output ends
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

(defn where
  "Where a fact happened, as the board shows it: its runner, and which of the runner's servers
   when the runner carries more than one (13/2)."
  [runner server]
  (str runner (when server (str "/" server))))

(defn render
  "The comment for title and facts: the Nether arrivals, the first bot to each milestone in the
   order they were first reached, each server's furthest bot, the races that ended, then every
   fact by time. A runner with one server shows as its number, with more as runner/server."
  [title facts]
  (let [ms (sort-by :milestone/at (filter :milestone/goal facts))
        ends (sort-by (juxt :race/runner :race/server) (filter :race/runner facts))
        firsts (->> (group-by :milestone/goal ms)
                    vals
                    (map first)
                    (sort-by :milestone/at))
        furthest (->> (group-by (juxt :milestone/runner :milestone/server :milestone/bot) ms)
                      (map
                       (fn [[[runner server bot] fs]]
                         {:runner runner :server server :bot bot :n (count fs) :latest (last fs)}))
                      (group-by (juxt :runner :server))
                      (map (fn [[_ xs]] (apply max-key :n xs)))
                      (sort-by (juxt :runner :server)))
        at (fn [f] (where (:milestone/runner f) (:milestone/server f)))
        nether (filter #(= :nether (:milestone/goal %)) ms)
        row (fn [& cells] (str "| " (str/join " | " cells) " |"))]
    (str/join
     "\n"
     (concat [title ""
              (if (seq nether)
                (str "**Nether: "
                     (str/join ", "
                               (map #(str (:milestone/bot %)
                                          " on runner " (at %)
                                          " at " (when-utc (:milestone/at %)))
                                    nether))
                     "**")
                "Nether: none yet") "" "**First to each milestone**" ""
              (row "milestone" "bot" "runner" "at (UTC)" "after landing")
              (row "---" "---" "---" "---" "---")]
             (for [f firsts]
               (row (name (:milestone/goal f))
                    (:milestone/bot f)
                    (at f)
                    (when-utc (:milestone/at f))
                    (str (:milestone/after-s f "?") " s")))
             ["" "**Furthest bot per runner**" "" (row "runner" "bot" "milestones" "last")
              (row "---" "---" "---" "---")]
             (for [{:keys [runner server bot n latest]} furthest]
               (row (where runner server) bot n (name (:milestone/goal latest))))
             [""
              (str "**Runners ended:** "
                   (count ends)
                   (when (seq ends)
                     (str " ("
                          (str/join
                           ", "
                           (map
                            #(str (where (:race/runner %) (:race/server %)) ": " (:race/ok %) " ok")
                            ends))
                          ")"))) "" "<details><summary>facts</summary>" "" (str fence "edn")]
             (map pr-str (sort-by (fn [f] [(:milestone/at f Long/MAX_VALUE) (pr-str f)]) facts))
             [fence "</details>"]))))

(defn line-fact
  "The fact a race log line carries, for a runner: a MILESTONE line's map, a RACE line's summary
   as a race's end, with the server it ran on when the line says (no time on it: published twice,
   once live and once when the job ends, it is one fact), or nil for any other line."
  [runner line]
  (cond (str/starts-with? line "MILESTONE ") (edn/read-string (subs line 10))
        (str/starts-with? line "RACE ")
        (let [s (edn/read-string (subs line 5))]
          (cond-> {:race/runner runner :race/ok (:race/ok s) :race/reasons (:race/reasons s)}
            (:race/server s) (assoc :race/server (:race/server s))))
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

(defn follow!
  "Publish every MILESTONE and RACE line on stdin to comment id, in batches: a reader collects
   facts as they come and, every batch-ms, all those pending go in one read, merge and write. A
   batch that loses to the other runners' writes stays pending for the next one, so a fact is
   never dropped while the race runs; once stdin ends, the last pending facts get last-rounds
   more batches. Twenty writers publishing one fact each read, merged and wrote so often that
   they gave facts up (race 38395034172); one write a batch keeps the comment's traffic to a
   few writes a minute a runner."
  [id runner]
  (let [pending (atom #{})
        ended (promise)]
    (future (doseq [line (line-seq (java.io.BufferedReader. *in*))
                    :let [fact (line-fact runner line)]
                    :when fact]
              (swap! pending conj fact))
            (deliver ended true))
    (loop [after-end 0]
      (Thread/sleep (long batch-ms))
      (let [batch @pending]
        (when (and (seq batch) (publish! id batch)) (swap! pending #(reduce disj % batch)))
        (cond (not (realized? ended)) (recur 0)
              (and (seq @pending) (< after-end last-rounds)) (recur (inc after-end))
              (seq @pending) (binding [*out* *err*] (println "race-board: gave up on" @pending))
              :else nil)))))

(defn -main
  "create <title>: open the race's comment on the race branch's pull request and print its id.
   follow <id> <runner>: publish every MILESTONE and RACE line on stdin, in batches (follow!)."
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
    "follow" (follow! a (parse-long b))))
