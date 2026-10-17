(ns clojurecraft.page
  "The results' data (issue #116): a batch's facts from its runs, and where they are kept on the
   gh-pages branch so the page reads little and the history stays whole.

     bb page round <dir> <kind> <run> <commit> <at>    the round's facts from the runs under dir
                                                       (what bb failures reads, and the race
                                                       logs' MILESTONE lines), as one EDN map
     bb page wall <dir>                                the round's failures ranked by how far the
                                                       bot got, then how often (the job summary)
     bb page publish <round.edn>                       the round published as a batch on gh-pages

   A round is a map: the run, its kind (:real or :sim), the commit, when it started and ended, how
   many runs, how many won, how many reached each milestone, how fast (fastest and median seconds
   from the bot going active to each milestone: the race's MILESTONE facts, a sim run's
   :plan/reached-after), the deaths, the furthest bot's splits, the failures by signature and the
   issue each is filed as. Every instant is an ISO-8601 string in UTC; durations are seconds. The
   chain is goals.edn's targets in priority order up to the race's target (ci/race.edn's
   :race/until: the fortress since issue #261), both read from their files, so the page and the
   bot agree on what a milestone is.

   On the branch a round is a batch (batch: the same facts as JSON, plain keys), laid out by the
   UTC day its run started (brain/bot/tooling/results-data.md says why this layout):
     data/batches/<day>.ndjson   every batch of the runs that started that day, a line each, in
                                 the order published: the whole record, never cut to a head
     data/days/<day>.json        that day's runs (each run's batches combined, one row a run)
                                 and its totals per kind, rebuilt from the day's batches
     data/summary.json           every day's totals per kind, oldest first: what the page
                                 loads first, small
   A publish rewrites one day's two files and the summary, from what the branch holds when it
   pushes; a push another writer won meanwhile is fetched and the publish applied again on top
   (update-branch!), and a publish that never gets through is handed back to its caller to keep
   and send with its next. The site itself (the page, its script, the road, the agents' report)
   is built from main by pages.yml (clojurecraft.site); the branch holds only data. Babashka
   runs this; nothing here requires the bot."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.math :as math]
            [clojure.string :as str]
            [clojurecraft.failures :as failures]))

(def branch "gh-pages")                ; the data's branch: data/, orchestrator.json (bb copilot dispatch)
(def legacy-file "rounds.edn")         ; the one-file layout before data/: absorbed by the next publish, then removed
(def stale-files ["index.html" "agents.html"]) ; the one-file layout's pages, built on the branch: pages.yml builds the site now
(def summary-file "data/summary.json")
(def summary-failures 25)              ; the most frequent failures a day's totals keep, with their issues
(def tries 8)                          ; pushes a publish attempts before it hands its batches back
(def goals-file "resources/clojurecraft/goals.edn")
(def race-file "ci/race.edn")          ; the race's settings: its :race/until is where the chain ends

;; ---------------------------------------------------------------- the chain

(defn chain
  "The milestones in order, from the goal table: every target with a --until name that is not an
   escape or an aside, by priority, up to and with the one whose until is end (the race's
   :race/until, race-end)."
  [goals end]
  (let [targets (->> goals
                     (filter #(and (:goal/target? %)
                                   (:goal/until %)
                                   (not (:goal/always? %))
                                   (not= :wade (:goal/plan %))
                                   (not (:goal/aside? %))))
                     (sort-by :goal/priority))
        [before from] (split-with #(not= end (:goal/until %)) targets)]
    (mapv :goal/id (concat before (take 1 from)))))

;; ---------------------------------------------------------------- a round from its runs

(defn median
  "The median of xs, or nil when there are none."
  [xs]
  (let [v (vec (sort xs))
        n (count v)]
    (cond (zero? n) nil
          (odd? n) (v (quot n 2))
          :else (/ (+ (v (dec (quot n 2))) (v (quot n 2))) 2.0))))

(defn mark-of-line
  "The milestone a race log's MILESTONE line carries, or nil."
  [line]
  (when (str/starts-with? line "MILESTONE ")
    (try (edn/read-string {:default (fn [_ v] v)} (subs line 10)) (catch Exception _ nil))))

(defn reached
  "How many runs reached each milestone: a run's RESULT carries :plan/reached."
  [runs milestones]
  (into {}
        (for [m milestones]
          [m (count (filter #(contains? (set (get-in % [:plan :plan/reached])) m) runs))])))

(defn times
  "Each milestone's times in seconds from the bot going active: the race's MILESTONE facts
   (:milestone/after-s), else the runs' :plan/reached-after (ms). {milestone [s ...]}."
  [runs marks]
  (if (seq marks)
    (reduce (fn [m {:milestone/keys [goal after-s]}] (update m goal (fnil conj []) after-s))
            {}
            marks)
    (reduce (fn [m r]
              (reduce (fn [m [g ms]] (update m g (fnil conj []) (quot ms 1000)))
                      m
                      (get-in r [:plan :plan/reached-after])))
            {}
            runs)))

(defn speed
  "{milestone {:n :fastest-s :median-s}} from the times."
  [ts]
  (into {} (for [[g xs] ts] [g {:n (count xs) :fastest-s (apply min xs) :median-s (median xs)}])))

(defn first-at
  "When each milestone was first reached in the round, as an ISO instant (the race's MILESTONE
   facts carry the wall clock; a sim run has none): {milestone iso}."
  [marks]
  (into {}
        (for [[g ms] (group-by :milestone/goal marks)
              :let [at (apply min (keep :milestone/at ms))]]
          [g (str (java.time.Instant/ofEpochMilli at))])))

(defn leader
  "The run that got furthest (most milestones, then the earliest last one), with its splits:
   {:bot name :splits {milestone s}}, or nil when none reached any."
  [runs marks milestones]
  (let [place (zipmap milestones (range))
        rank (fn [[_ splits]] [(- (count splits)) (apply max 0 (vals splits))])
        splits (if (seq marks)
                 (reduce (fn [m {:milestone/keys [runner server bot goal after-s]}]
                           (assoc-in m
                            [(str bot " (runner " runner (when server (str "/" server)) ")") goal]
                            after-s))
                         {}
                         marks)
                 (into {}
                       (keep-indexed
                        (fn [i r]
                          (when-let [ra (not-empty (get-in r [:plan :plan/reached-after]))]
                            [(str "run "
                                  (or (:run/seed r) i)
                                  (when-let [l (get-in r [:run/world :capture/landing])]
                                    (str " at " (pr-str l)))) (update-vals ra #(quot % 1000))])))
                       runs))]
    (when-let [[bot s] (first (sort-by rank (filter (fn [[_ s]] (some place (keys s))) splits)))]
      {:bot bot :splits s})))

(defn deaths
  "How often runs died: {:deaths total :died runs with at least one}, from each RESULT's
   :stats/deaths (the server's death screens, counted by the game reducer; the sim's model sends
   the same)."
  [runs]
  (let [ds (map #(get-in % [:stats :stats/deaths] 0) runs)]
    {:deaths (reduce + 0 ds) :died (count (filter pos? ds))}))

(defn round
  "The round as a value (see the namespace): runs and marks are what the report job downloaded,
   the rest is the run's own facts."
  [runs marks milestones {:keys [kind run commit at started ended issues runner batch]}]
  (let [{:keys [deaths died]} (deaths runs)]
    (cond-> {:round/run run
             :round/kind kind
             :round/commit commit
             :round/at at
             :round/runs (count runs)
             :round/ok (count (filter :ok runs))
             :round/deaths deaths
             :round/died died
             :round/reached (reached runs milestones)
             :round/speed (speed (times runs marks))
             :round/failures (mapv (fn [[sig rs]] [sig (count rs)]) (failures/grouped runs))}
      started (assoc :round/started started)
      ended (assoc :round/ended ended)
      runner (assoc :round/runner runner)
      batch (assoc :round/batch batch)
      (seq marks) (assoc :round/first-at (first-at marks))
      (seq issues) (assoc :round/issues issues)
      (leader runs marks milestones) (assoc :round/leader (leader runs marks milestones)))))

(defn wall
  "The failures by the stage the bot was on, the furthest stage first: [[stage total
   [[signature count] ...]] ...]."
  [failures milestones]
  (let [place (zipmap milestones (range))]
    (->> (group-by (comp first first) failures)
         (map (fn [[stage sigs]] [stage (reduce + (map second sigs))
                                  (vec (sort-by (comp - second) sigs))]))
         (sort-by (fn [[stage total]] [(- (place stage -1)) (- total)]))
         vec)))

(defn instant
  "An ISO instant in UTC for a timestamp string with any offset, or the string unchanged."
  [s]
  (try (str (.toInstant (java.time.OffsetDateTime/parse (str s))))
       (catch Exception _
         (some-> s
                 str))))

;; ---------------------------------------------------------------- a round as a batch

(defn sig-key
  "A signature [stage reason] as the key a batch files its issue under: :|cast-one no-path|, the
   JSON key \"cast-one no-path\"."
  [[stage reason]]
  (keyword (str (name stage) " " (name reason))))

(defn batch
  "A round as the branch keeps it and the page reads it: plain keys, every keyword value its name,
   failures as [stage reason n] (most first), issues by sig-key, :first for :round/first-at. A
   key the round lacks is absent (older rounds have no deaths)."
  [r]
  (let [issues (into {}
                     (for [[k n] (:round/issues r)
                           :let [sig (try (edn/read-string (str k)) (catch Exception _ nil))]
                           :when (and (vector? sig) (= 2 (count sig)))]
                       [(sig-key sig) n]))
        speed (update-vals (:round/speed r {})
                           (fn [{:keys [n fastest-s median-s]}]
                             {:n n :fastest fastest-s :median median-s}))]
    (cond-> {:run (:round/run r)
             :kind (some-> (:round/kind r)
                           name)
             :commit (:round/commit r)
             :at (instant (:round/at r))
             :runs (:round/runs r 0)
             :ok (:round/ok r 0)
             :reached (:round/reached r {})
             :speed speed
             :failures (mapv (fn [[[stage reason] n]] [(name stage) (name reason) n])
                             (:round/failures r))}
      (:round/started r) (assoc :started (instant (:round/started r)))
      (:round/ended r) (assoc :ended (instant (:round/ended r)))
      (:round/runner r) (assoc :runner (:round/runner r))
      (:round/batch r) (assoc :batch (:round/batch r))
      (:round/deaths r) (assoc :deaths (:round/deaths r) :died (:round/died r 0))
      (seq issues) (assoc :issues issues)
      (:round/first-at r) (assoc :first (:round/first-at r))
      (:round/leader r) (assoc :leader (:round/leader r)))))

(defn day-of
  "The UTC day a batch belongs to, yyyy-mm-dd: its run's start's, else its commit's, else its
   end's. Every batch of a run carries the same start, so a run is never split across days."
  [b]
  (let [t (str (or (:started b) (:at b) (:ended b)))]
    (cond (re-find #"^\d{4}-\d\d-\d\d" t) (subs t 0 10)
          :else "1970-01-01")))

(defn batch-key
  "What makes a batch the same batch: run, kind, runner and batch number."
  [b]
  ((juxt :run :kind :runner :batch) b))

(defn batches-path "A day's batches on the branch." [day] (str "data/batches/" day ".ndjson"))

(defn day-path "A day's runs and totals on the branch." [day] (str "data/days/" day ".json"))

;; ---------------------------------------------------------------- batches combined

(defn earliest "The earlier of two ISO instants." [a b] (first (sort [a b])))

(defn best-leader
  "Of leaders, the one that got furthest: most milestones, then the earliest last one."
  [leaders]
  (first (sort-by (fn [{:keys [splits]}] [(- (count splits)) (reduce max 0 (vals splits))])
                  leaders)))

(defn speeds
  "Each milestone's times over batches: the fastest the least, the median the batches' medians
   weighted by their counts, to a tenth of a second (a median of medians: the batches keep no
   more)."
  [speed-maps]
  (into {}
        (for [g (distinct (mapcat keys speed-maps))
              :let [xs (keep #(get % g) speed-maps)
                    n (reduce + (map :n xs))]
              :when (pos? n)]
          [g
           {:n n
            :fastest (apply min (map :fastest xs))
            :median (/ (math/round (/ (* 10.0 (reduce + (map #(* (:n %) (:median %)) xs))) n))
                       10.0)}])))

(defn failures-summed
  "[stage reason n] rows summed by signature, the most frequent first (then by name, so the
   order is the same whoever sums)."
  [rows]
  (->> rows
       (reduce (fn [m [stage reason n]] (update m [stage reason] (fnil + 0) n)) {})
       (sort-by (fn [[[stage reason] n]] [(- n) stage reason]))
       (mapv (fn [[[stage reason] n]] [stage reason n]))))

(defn combine
  "One row from batches (a run's, or a day's of one kind): counts summed, failures summed by
   signature, each milestone's fastest the least and its median weighted (speeds), the earliest
   start and first reach, the latest end, the runners, the furthest leader; deaths only over the
   batches that counted them, with the runs they cover as :death-runs."
  [bs]
  (let [some-of (fn [k] (some k bs))
        sum (fn [k xs] (reduce + 0 (keep k xs)))
        starts (sort (keep :started bs))
        ends (sort (keep :ended bs))
        counted (filter :deaths bs)]
    (cond-> {:run (some-of :run)
             :kind (some-of :kind)
             :commit (some-of :commit)
             :at (some-of :at)
             :runners (vec (sort (distinct (keep :runner bs))))
             :batches (count bs)
             :runs (sum :runs bs)
             :ok (sum :ok bs)
             :reached (apply merge-with + {} (keep :reached bs))
             :speed (speeds (keep :speed bs))
             :failures (failures-summed (mapcat :failures bs))}
      (seq starts) (assoc :started (first starts))
      (seq ends) (assoc :ended (last ends))
      (seq counted)
      (assoc :deaths (sum :deaths counted)
             :died (sum :died counted)
             :death-runs (sum :runs counted))
      (seq (keep :issues bs)) (assoc :issues (apply merge (keep :issues bs)))
      (seq (keep :first bs)) (assoc :first (apply merge-with earliest (keep :first bs)))
      (seq (keep :leader bs)) (assoc :leader (best-leader (keep :leader bs))))))

(defn totals
  "A day's totals for one kind's batches (combine): the counts, times, first reaches and deaths;
   how many rounds (runs of a lane) and commits; the stops by the stage the bot was on; the
   summary-failures most frequent failures with the issues they are filed as."
  [bs]
  (let [c (combine bs)
        top (vec (take summary-failures (:failures c)))
        t (assoc (select-keys c [:runs :ok :deaths :died :death-runs :reached :speed :first])
                 :rounds (count (distinct (map :run bs)))
                 :commits (vec (distinct (keep :commit bs)))
                 :stops
                 (reduce (fn [m [stage _ n]] (update m (keyword stage) (fnil + 0) n))
                         {}
                         (:failures c))
                 :failures top)]
    (cond-> t (:issues c) (assoc :issues (select-keys (:issues c) (map sig-key top))))))

(defn day
  "A day's file from its batches: its runs (one row a run and kind, by start), and its totals
   per kind ({:real totals :sim totals}, a kind with no batch absent)."
  [date bs]
  {:date date
   :runs (->> (group-by (juxt :run :kind) bs)
              vals
              (map combine)
              (sort-by (juxt #(str (:started %)) :run :kind))
              vec)
   :totals (into {} (for [[k kbs] (group-by :kind bs)] [(keyword k) (totals kbs)]))})

(defn with-day
  "summary ({:days [{:date :real :sim}]}, oldest first) with a day's totals in place of its own."
  [summary {:keys [date totals]}]
  {:days (->> (:days summary)
              (remove #(= date (:date %)))
              (cons (assoc totals :date date))
              (sort-by :date)
              vec)})

(defn with-batches
  "A day's batches with bs added: a batch replaces an earlier one with its key (batch-key), and
   each keeps the place of its last publish."
  [day-batches bs]
  (let [all (concat day-batches bs)]
    (->> (reverse all)
         (reduce (fn [[seen acc] b]
                   (cond (seen (batch-key b)) [seen acc]
                         :else [(conj seen (batch-key b)) (conj acc b)]))
                 [#{} ()])
         second
         vec)))

;; ---------------------------------------------------------------- the files

(defn json-line "A value as one line of JSON." [x] (json/generate-string x))

(defn read-json
  "JSON text as a value with keyword keys, or nil for nil or text that does not read."
  [s]
  (when s (try (json/parse-string s true) (catch Exception _ nil))))

(defn read-ndjson
  "The values of an NDJSON text, a line each; blank or unreadable lines are skipped."
  [s]
  (into [] (keep #(when (seq (str/trim %)) (read-json %))) (str/split-lines (str s))))

(defn ndjson
  "Values as NDJSON text, a line each."
  [xs]
  (str/join (map #(str (json-line %) "\n") xs)))

(defn read-rounds
  "The rounds in an EDN text, a map a line (the legacy rounds.edn)."
  [s]
  (into []
        (keep #(when (seq (str/trim %)) (edn/read-string {:default (fn [_ v] v)} %)))
        (str/split-lines (str s))))

(defn changes
  "What publishing batches bs writes on the branch, given what it holds: {path text}, nil text
   for a file to remove. state is {:legacy rounds from rounds.edn, or nil when there is none;
   :days {day ndjson-text-or-nil} for every day bs and legacy touch; :summary summary.json's value
   or nil; :now the publish's instant}. The legacy rounds join their days as batches, and
   rounds.edn and the pages built beside it go (stale-files); each touched day's batches file and
   day file are rewritten, and the summary takes each touched day's totals. Pure: what a retry
   recomputes from the branch as it then is."
  [{:keys [legacy days summary now]} bs]
  (let [all (concat (map batch legacy) bs)
        by-day (into (sorted-map)
                     (for [[d dbs] (group-by day-of all)]
                       [d (with-batches (read-ndjson (get days d)) dbs)]))
        files (into {}
                    (mapcat (fn [[d dbs]] [[(batches-path d) (ndjson dbs)]
                                           [(day-path d) (json-line (day d dbs))]]))
                    by-day)
        summary' (reduce with-day (or summary {:days []}) (map (fn [[d dbs]] (day d dbs)) by-day))]
    (cond-> (assoc files summary-file (json-line (assoc summary' :updated now)))
      (some? legacy) (merge (zipmap (cons legacy-file stale-files) (repeat nil))))))

(defn publish-change
  "The change a publish of bs makes (changes), reading the branch through read (a path's text,
   or nil when the branch has none): the legacy rounds, every day they and bs touch, the summary."
  [now bs read]
  (let [legacy (some-> (read legacy-file)
                       read-rounds)
        touched (distinct (map day-of (concat (map batch legacy) bs)))]
    (changes {:legacy legacy
              :days (into {} (for [d touched] [d (read (batches-path d))]))
              :summary (read-json (read summary-file))
              :now now}
             bs)))

;;;; I/O: the files, git, gh ;;;;

(defn goals
  "The goal table, from the repository's resources."
  []
  (edn/read-string {:default (fn [_ v] v)} (slurp goals-file)))

(defn race-end
  "The --until the races play to (ci/race.edn's :race/until): the chain ends there. Issue #261
   raised it from the Nether to the fortress; the page follows the file, never a constant of its
   own."
  []
  (:race/until (edn/read-string (slurp race-file))))

(defn repo
  "The repository, owner/name: the workflow's, else this checkout's origin."
  []
  (or (System/getenv "GITHUB_REPOSITORY")
      (some->>
        (p/shell {:out :string :err :string :continue true} "git" "remote" "get-url" "origin")
        :out
        (re-find #"github\.com[:/](.+?)(?:\.git)?\s*$")
        second)))

(defn marks-under
  "Every MILESTONE fact in the race logs under dir."
  [dir]
  (into []
        (comp (filter #(= "race.log" (fs/file-name %)))
              (mapcat #(str/split-lines (slurp (fs/file %))))
              (keep mark-of-line))
        (fs/glob dir "**")))

(defn run-times
  "The run's start and end as ISO instants, from GitHub, or {} without gh: the end is now while
   the run is still going (its report job asks), else when GitHub last updated it."
  [run]
  (let [r (p/shell {:out :string :err :string :continue true}
                   "gh"
                   "api"
                   (str "repos/" (repo) "/actions/runs/" run))]
    (if (zero? (:exit r))
      (let [j (json/parse-string (:out r) true)]
        {:started (:run_started_at j)
         :ended (if (= "completed" (:status j)) (:updated_at j) (str (java.time.Instant/now)))})
      {})))

(defn issue-numbers
  "{signature-string issue-number} for the round's signatures, from the bot-failure issues."
  [runs]
  (let [all (failures/issues)]
    (into {}
          (keep (fn [[sig _]]
                  (when-let [i (failures/issue-for all sig)] [(pr-str sig) (:number i)])))
          (failures/grouped runs))))

(defn quiet!
  "A command's result, {:exit :out :err}, never thrown."
  [opts & args]
  (apply p/shell (merge {:continue true :out :string :err :string} opts) args))

(defn git!
  "git with args in dir; throws when it fails."
  [dir & args]
  (apply p/shell {:dir (str dir)} "git" args))

(defn deploy!
  "Ask GitHub to deploy the site (pages.yml, which builds it from main and this branch's data). A
   failed request is printed, never thrown: the next publish asks again."
  []
  (let [r (quiet! {} "gh" "workflow" "run" "pages.yml" "--repo" (repo) "--ref" "main")]
    (println (if (zero? (:exit r)) "deploy requested" (str "deploy request failed: " (:err r))))))

(defn fetch!
  "Fetch the branch's newest commit alone (no history: the lanes' checkouts are shallow, and a
   publish needs only the tip); true when the branch exists."
  []
  (zero? (:exit (quiet! {} "git" "fetch" "--depth" "1" "origin" branch))))

(defn apply-files!
  "Write files ({path text}, nil text removes the file) under dir."
  [dir files]
  (doseq [[path text] files
          :let [f (fs/path dir path)]]
    (cond (some? text) (do (fs/create-dirs (fs/parent f)) (spit (str f) text))
          (fs/exists? f) (fs/delete f))))

(defn pushed-or-lost
  "A push's result as :pushed, or :lost when git refused it."
  [r]
  (if (zero? (:exit r)) :pushed :lost))

(defn try-push!
  "One attempt in the worktree dir: the branch's tip, the change applied, committed and pushed.
   :pushed, :nothing (no change to make) or :lost (another writer pushed first)."
  [dir message change]
  (let [fetched? (fetch!)]
    (when fetched? (git! dir "reset" "-q" "--hard" (str "origin/" branch)))
    (apply-files! dir
                  (change (fn [path] (let [f (fs/file dir path)] (when (fs/exists? f) (slurp f))))))
    (git! dir "add" "-A")
    (cond (zero? (:exit (quiet! {:dir dir} "git" "diff" "--cached" "--quiet"))) :nothing
          :else
          (do (git! dir
                    "-c" "user.name=github-actions[bot]"
                    "-c" "user.email=41898282+github-actions[bot]@users.noreply.github.com"
                    "commit" "-q"
                    "-m" message)
              (pushed-or-lost (quiet! {:dir dir} "git" "push" "origin" (str "HEAD:" branch)))))))

(defn update-branch!
  "Make change on the gh-pages branch and push it: change is a function of read (a path's text on
   the branch, or nil) to {path text}, nil text removing the file. A push another writer won
   meanwhile (eight lanes and the deploy write here) is retried on the new tip, the change
   recomputed from it, tries times, a random wait growing between. The branch is worked in a
   worktree of its own, label naming it, removed after. Returns :pushed, :nothing or :lost."
  [label message change]
  (let [dir (str (fs/path (fs/parent (fs/absolutize ".")) (str "pages-" label)))]
    (when (fs/exists? dir) (quiet! {} "git" "worktree" "remove" "--force" dir))
    (if (fetch!)
      (git! "." "worktree" "add" "-q" "--detach" dir (str "origin/" branch))
      (do (git! "." "worktree" "add" "-q" "--detach" dir "HEAD")
          (git! dir "checkout" "-q" "--orphan" branch)
          (git! dir "rm" "-rfq" ".")))
    (try (loop [n 1]
           (let [r (try-push! dir message change)]
             (cond (#{:pushed :nothing} r) r
                   (< n tries)
                   (do (println "lost the push to another writer; try" (inc n))
                       (Thread/sleep (long (+ 1000 (rand-int (* n 4000)))))
                       (recur (inc n)))
                   :else :lost)))
         (finally (quiet! {} "git" "worktree" "remove" "--force" dir)))))

(defn publish-rounds!
  "Publish rounds (values, oldest first) as batches on gh-pages (publish-change), then ask for a
   deploy. Returns the rounds not published: none, or all of them when every push was lost or a
   step threw, for the caller to keep and publish with its next."
  [rounds]
  (let [bs (mapv batch rounds)
        {:keys [run kind runner batch commit]} (last bs)
        label (str run "-" kind "-" (or runner 0) "-" (or batch 0))
        message (str "Batch "
                     batch
                     " of run "
                     run
                     " ("
                     kind
                     ", runner "
                     runner
                     ") at "
                     (subs (str commit) 0 (min 7 (count (str commit))))
                     (when (next bs) (str ", with " (dec (count bs)) " held back")))
        r (try (update-branch! label message #(publish-change (str (java.time.Instant/now)) bs %))
               (catch Exception e (println "publish failed:" (ex-message e)) :lost))]
    (cond (= :lost r)
          (do (println "not published:" (count rounds) "batches kept for the next") rounds)
          :else (do (println "published" (count rounds) "batches to" branch) (deploy!) []))))

(defn publish!
  "Publish the round in the EDN file at round-path (publish-rounds!); exits 1 when it could not."
  [round-path]
  (when (seq (publish-rounds! [(edn/read-string {:default (fn [_ v] v)} (slurp round-path))]))
    (System/exit 1)))

(defn -main
  "round <dir> <kind> <run> <commit> <at>, wall <dir>, publish <round.edn>."
  [& [cmd a b c d e]]
  (case cmd
    "round"
    (let [runs (failures/runs-under a)]
      (prn (round runs
                  (marks-under a)
                  (chain (goals) (race-end))
                  (merge {:kind (keyword b)
                          :run (parse-long c)
                          :commit d
                          :at (instant e)
                          :issues (when (repo) (issue-numbers runs))}
                         (run-times c)))))
    "wall"
    (doseq [[stage total sigs] (wall (mapv (fn [[sig rs]] [sig (count rs)])
                                           (failures/grouped (failures/runs-under a)))
                                     (chain (goals) (race-end)))]
      (println (name stage)
               total
               (str/join ", " (for [[sig n] (take 3 sigs)] (str (pr-str sig) " " n)))))
    "publish" (publish! a)
    (do (println
         "usage: bb page round <dir> <kind> <run> <commit> <at> | wall <dir> | publish <round.edn>")
        (System/exit 2))))
