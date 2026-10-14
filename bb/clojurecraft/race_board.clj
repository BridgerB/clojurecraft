(ns clojurecraft.race-board
  "The race's one comment on the race-board issue (race.yml), edited in place as bots reach milestones.

     bb race-board create <title>              a new comment on the race-board issue; print its id
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

(def rank
  "A milestone's place in the run: its target row's :goal/priority (goals.edn), so the furthest
   of two milestones is the one with the greater rank."
  (into {}
        (for [g (edn/read-string (slurp "resources/clojurecraft/goals.edn"))
              :when (:goal/until g)]
          [(:goal/id g) (:goal/priority g)])))

(defn compact
  "The facts the comment keeps, from any set of facts: every Nether arrival and every race's end,
   the first fact of each milestone (the earliest), and each server's furthest (the greatest
   rank, the earliest of equals). Each part merges in any order (a union, a least, a greatest),
   so (compact (into a b)) is (compact (into (compact a) (compact b))) and writers need no lock;
   and the comment stays a few dozen lines however many bots race. A race of 500 bots made
   6,500 milestones, past the 65,536 characters a comment holds: every write after was refused
   and the board stood still (race 38469907232). Every fact is still kept in each runner's
   milestones.edn."
  [facts]
  (let [ms (filter :milestone/goal facts)
        earliest (fn [fs] (first (sort-by (juxt :milestone/at pr-str) fs)))
        furthest (fn [fs]
                   (earliest (let [top (apply max (map #(rank (:milestone/goal %) -1) fs))]
                               (filter #(= top (rank (:milestone/goal %) -1)) fs))))]
    (into #{}
          cat
          [(filter :race/runner facts) (filter #(= :nether (:milestone/goal %)) ms)
           (map earliest (vals (group-by :milestone/goal ms)))
           (map furthest (vals (group-by (juxt :milestone/runner :milestone/server) ms)))])))

(defn covered?
  "Does held, a comment's facts, already say all that new would add (compact of both is held)?"
  [held new]
  (= (compact (into held new)) held))

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
        furthest (->> (group-by (juxt :milestone/runner :milestone/server) ms)
                      (map
                       (fn [[[runner server] fs]]
                         (let [top (apply max-key
                                          #(rank (:milestone/goal %) -1)
                                          (reverse (sort-by :milestone/at fs)))]
                           {:runner runner :server server :bot (:milestone/bot top) :latest top})))
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
             ["" "**Furthest bot per runner**" "" (row "runner" "bot" "furthest" "at (UTC)")
              (row "---" "---" "---" "---")]
             (for [{:keys [runner server bot latest]} furthest]
               (row (where runner server)
                    bot
                    (name (:milestone/goal latest))
                    (when-utc (:milestone/at latest))))
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

(def board-label "race-board")           ; the one issue every race's comment goes on

(defn board-issue!
  "The number of the open issue labelled race-board, the standing place the races' comments go
   (one comment a race); opened when there is none. The races run on main, so there is no pull
   request to comment on."
  []
  (or
   (:number (first (gh!
                    (str "repos/" (repo) "/issues?state=open&labels=" board-label "&per_page=1"))))
   (do
     (gh! "-X"
          "POST" (str "repos/" (repo) "/labels")
          "-f" (str "name=" board-label)
          "-f" "color=0075ca")
     (:number
      (gh!
       (str "repos/" (repo) "/issues")
       "-f" "title=Race board"
       "-f"
       "body=Every race on main posts one comment here (bb race-board): the Nether arrivals, the first bot to each milestone, each server's furthest. Closing it opens a fresh one at the next race."
       "-f" (str "labels[]=" board-label))))))

(defn publish!
  "Add new facts to comment id: read, merge (compact), write, read again; start over until the
   comment covers them or tries run out. Returns whether it does."
  [id new]
  (loop [n 0]
    (let [body (body! id)
          merged (compact (into (facts body) new))]
      (gh! "-X"
           "PATCH" (str "repos/" (repo) "/issues/comments/" id)
           "-f" (str "body=" (render (title body) merged)))
      (cond (covered? (facts (body! id)) new) true
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
    (println (:id (gh! (str "repos/" (repo) "/issues/" (board-issue!) "/comments")
                       "-f"
                       (str "body=" (render a #{})))))
    "follow" (follow! a (parse-long b))))
