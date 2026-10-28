(ns clojurecraft.plans
  "Run one plan file across many jobs. A plan (ci/plan/<label>.edn) is data: how many jobs, how
   wide, and the experiments to run. The namespace is plans because plan is the bot's planner.
   The planner here is pure: it expands experiments into units (a gym unit is k trials sharing
   one server, each its own bot on its own landing; a sim unit is one seeded shard of a
   generated-world property), then schedules units onto jobs longest first. Only job numbers
   cross the job boundary; each job recomputes its own assignment from the plan, so every job
   and the aggregate agree.

   A job is a process speaking data: it starts each bot as its own JVM, lands it and tells it to
   go through its stdin (the gym's fixture), judges it from its log and the server, and leaves a
   result file per trial. The aggregate folds every result into one report."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.math :as math]
            [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [clojurecraft.cli :as cli]
            [clojurecraft.gym :as gym]
            [clojurecraft.rcon :as rcon]))

(set! *warn-on-reflection* true)

;; the shape of a plan file
(s/def :plans/label string?)
(s/def :plans/jobs (s/int-in 1 257))                 ; a matrix holds at most 256 jobs
(s/def :plans/max-parallel (s/int-in 1 21))
(s/def :plans/max-job-s pos-int?)
(s/def :plans/issue pos-int?)
(s/def :plans/salt string?)                         ; a repeated plan's run, so its sims sample fresh worlds
(s/def :exp/name string?)
(s/def :exp/kind #{:gym :sim})
(s/def :exp/goal string?)
(s/def :exp/set string?)
(s/def :exp/runs pos-int?)
(s/def :exp/bots pos-int?)
(s/def :exp/est-s pos-int?)
(s/def :arm/name string?)
(s/def :arm/ref string?)
(s/def :exp/arms (s/coll-of (s/keys :req [:arm/name] :opt [:arm/ref]) :kind vector? :min-count 1))
(s/def :exp/property string?)
(s/def :exp/shards pos-int?)
(s/def :exp/worlds pos-int?)
(s/def ::experiment
  (s/and (s/keys :req [:exp/name :exp/kind :exp/est-s]
                 :opt
                 [:exp/goal :exp/set :exp/runs :exp/bots :exp/arms :exp/property :exp/shards
                  :exp/worlds])
         #(case (:exp/kind %)
            :gym (and (:exp/goal %) (:exp/runs %))
            :sim (and (:exp/property %) (:exp/shards %) (:exp/worlds %)))))
(s/def :plans/experiments (s/coll-of ::experiment :kind vector? :min-count 1))
(s/def ::plan
  (s/keys :req [:plans/label :plans/jobs :plans/experiments]
          :opt [:plans/max-parallel :plans/max-job-s :plans/issue :plans/salt]))

(def default-max-job-s 19800)           ; 5.5 h: a GitHub job may run 6 h
(def server-overhead-s 60)              ; copy the world, start the server, stop it
(def default-bots 1)                    ; bots per server unless the plan or capacity says more
(def default-arms [{:arm/name "head"}]) ; the code being tested, at the plan's own commit

;; ---------------------------------------------------------------- planning

(defn seed-for
  "The seed of one sim shard: a function of the plan, so a shard can always be re-run."
  [label exp-name shard]
  (Math/abs (long (hash [label exp-name shard]))))

(defn gym-units
  "A gym experiment's units: for each arm, runs trials in run order, k to a server."
  [{:exp/keys [name goal set runs bots est-s arms]}]
  (let [k (or bots default-bots)]
    (vec (for [arm (or arms default-arms)
               [i trials] (map-indexed vector (partition-all k (range 1 (inc runs))))]
           {:unit/id (str name "-" (:arm/name arm) "-" (inc i))
            :unit/kind :gym
            :unit/exp name
            :unit/arm arm
            :unit/goal goal
            :unit/set (or set "A")
            :unit/runs (vec trials)
            :unit/est-s (+ est-s server-overhead-s)}))))

(defn sim-units
  "A sim experiment's units: shards of the property, worlds split evenly, each seeded from the
   label (and the salt a repeated plan carries, so each repeat samples fresh worlds)."
  [label {:exp/keys [name property shards worlds est-s]}]
  (vec (for [s (range 1 (inc shards))]
         {:unit/id (str name "-" s)
          :unit/kind :sim
          :unit/exp name
          :unit/property property
          :unit/seed (seed-for label name s)
          :unit/worlds (long (math/ceil (/ worlds shards)))
          :unit/est-s est-s})))

(defn units
  "Every unit a plan asks for, in plan order."
  [{:plans/keys [label salt experiments]}]
  (vec (mapcat (fn [exp]
                 (case (:exp/kind exp)
                   :gym (gym-units exp)
                   :sim (sim-units (str label salt) exp)))
        experiments)))

(defn schedule
  "Units onto jobs, longest first, each to the least loaded job: {job [unit ...]}.
   Throws when a job would pass max-job-s, so a plan that cannot fit fails before it
   starts rather than when a runner is killed at six hours."
  [{:plans/keys [jobs max-job-s] :as plan}]
  (let [limit (or max-job-s default-max-job-s)
        ordered (sort-by (juxt (comp - :unit/est-s) :unit/id) (units plan))
        loads (reduce (fn [loads u]
                        (let [[w {:keys [s]}] (first (sort-by (fn [[w {:keys [s]}]] [s w]) loads))
                              s' (+ s (:unit/est-s u))]
                          (when (> s' limit)
                            (throw (ex-info "plan does not fit its jobs"
                                            {:unit (:unit/id u) :job w :seconds s'})))
                          (update loads
                                  w
                                  (fn [l]
                                    (-> l
                                        (update :s + (:unit/est-s u))
                                        (update :units conj u))))))
                      (into (sorted-map) (for [w (range 1 (inc jobs))] [w {:s 0 :units []}]))
                      ordered)]
    (into (sorted-map) (for [[w {:keys [units]}] loads :when (seq units)] [w units]))))

(defn assignment "The units job n runs." [plan n] (get (schedule plan) n []))

(defn matrix
  "The workflow matrix: only the job numbers that have work."
  [plan]
  (str "{\"job\":[" (str/join "," (keys (schedule plan))) "]}"))

(defn width
  "How many of this plan's jobs to run at once: the plan's :plans/max-parallel, else its job
   count. Never shrunk to the runners free when the plan starts: the width is fixed for the
   whole run, and jobs held back by max-parallel are not queued, so the fleet (which gives a
   lane back only to a queued job) never saw them. A plan begun beside 19 busy jobs ran its
   four jobs one after another for nearly three hours (plan run 39529384355). At full width
   the jobs queue and the fleet gives them runners."
  [{:plans/keys [max-parallel jobs]}]
  (or max-parallel jobs))

(defn read-plan
  "A plan file, read and checked against its spec: a malformed plan fails in the plan job."
  [path]
  (let [plan (edn/read-string (slurp path))]
    (when-not (s/valid? ::plan plan)
      (throw (ex-info (str path " is not a plan: " (s/explain-str ::plan plan)) {})))
    plan))

;; ---------------------------------------------------------------- reporting

(defn sim-line
  "One sim experiment's verdict: worlds run, shards that failed, and each shrunk counterexample
   with the seed that reproduces it."
  [name rows]
  (let [worlds (reduce + (map #(:num-tests % 0) rows))
        failed (remove :pass? rows)]
    (str/join "\n"
              (concat
               [(str "### sim " name) ""
                (str "worlds " worlds " in " (count rows) " shards; failing shards " (count failed))
                ""]
               (for [f failed]
                 (str "- seed " (:seed f) ": smallest failing input `" (pr-str (:smallest f)) "`"))
               [""]))))

(defn report
  "A plan's markdown report: each gym experiment and arm through gym/report, then each sim."
  [gym-rows sim-rows]
  (str/join "\n"
            (concat
             (for [[[exp arm] rows] (sort-by key (group-by (juxt :plans/exp :plans/arm) gym-rows))]
               (str "## " exp " (" arm ")\n\n" (gym/report rows)))
             (for [[exp rows] (sort-by key (group-by :plans/exp sim-rows))] (sim-line exp rows)))))

(defn failures
  "The trials and shards a person should look at: every row that is not a pass, with where it
   lives on disk, gym rows by outcome first."
  [located]
  (sort-by (juxt (comp str :gym/outcome :row) :path)
           (remove (fn [{:keys [row]}] (or (= :pass (:gym/outcome row)) (true? (:pass? row))))
                   located)))

(defn failure-line
  "One failure as a line: what failed, why, and the directory holding its recording and logs."
  [{:keys [row path]}]
  (if (contains? row :gym/outcome)
    (str "- " (:plans/exp row)
         " (" (:plans/arm row)
         ") run " (:gym/run row)
         " " (:gym/outcome row)
         " " (:gym/reason row)
         " at " (pr-str (:gym/landing row))
         ": " path)
    (str "- sim " (:plans/exp row)
         " seed " (:seed row)
         " smallest " (pr-str (:smallest row))
         ": " path)))

(def properties
  "Sim properties a plan can shard, by name: the test var that runs one."
  {"forests" "clojurecraft.sim-test/the-wood-goal-holds-in-generated-forests"})

(defn bot-name
  "A bot's name in a unit: short, unique on its server."
  [unit-index run]
  (str "C" unit-index "r" run))

;;;; I/O: processes, servers, files ;;;;

(defn rcon-pass
  "A fresh RCON password for one server."
  []
  (str/join (repeatedly 32 #(rand-nth "0123456789abcdef"))))

(defn arm-bot
  "The JVM an arm's bots run in: fallback (this checkout) when the arm names no ref, else a
   detached worktree of that ref fetched at depth 1. Returns {:bot-dir :cp :commit}. The gym
   setup still runs in this process, so a column built here meets the other ref's bot."
  [arm fallback log]
  (if-let [ref (:arm/ref arm)]
    (let [dir (io/file ".worktrees" (str/replace ref #"[^A-Za-z0-9._-]" "_"))
          sha (io/file (.getParentFile log) "arm-sha.txt")]
      (when-not (.exists (io/file dir "deps.edn"))
        (.mkdirs (.getParentFile dir))
        (when-not (zero? (cli/exec! ["git" "fetch" "--depth" "1" "origin" ref] {:log log}))
          (throw (ex-info "could not fetch the arm" {:ref ref})))
        (when-not (zero? (cli/exec! ["git" "worktree" "add" "--detach" (str dir) "FETCH_HEAD"]
                                    {:log log}))
          (throw (ex-info "could not check out the arm" {:ref ref :dir (str dir)}))))
      (when-not (zero? (cli/exec! ["git" "-C" (str dir) "rev-parse" "HEAD"] {:log sha}))
        (throw (ex-info "could not read the arm's commit" {:ref ref})))
      {:bot-dir (str dir) :cp (cli/classpath! (str dir)) :commit (str/trim (slurp sha))})
    fallback))

(defn gym-unit!
  "A gym unit: a fresh copy of the set's world, one server, its trials' bots side by side, the
   server stopped; forceload checked empty before and logged after. An arm with :arm/ref runs
   that ref's bot (arm-bot); the landing and its setup are still this process's."
  [unit unit-index {:keys [out worlds cp bot-dir commit heap-mb]}]
  (let [dir (doto (io/file out (:unit/id unit)) .mkdirs)
        world (io/file dir "world")
        pass (rcon-pass)
        log (io/file dir "unit.log")
        bot (arm-bot (:unit/arm unit) {:bot-dir bot-dir :cp cp :commit commit} log)
        env {"PORT" 25565
             "RCON_PORT" 25575
             "RCON_PASS" pass
             "WORLD" (str world)
             "HEAP_MB" (or heap-mb 6144)}]
    (cli/exec! ["cp" "-r" (str (io/file worlds (:unit/set unit))) (str world)] {:log log})
    (try (when-not (zero? (cli/exec! ["bb" "server" "start"] {:env env :log log}))
           (throw (ex-info "server did not start" {:unit (:unit/id unit)})))
         (spit (io/file dir "forceload-start.txt")
               (rcon/with-rcon "127.0.0.1" 25575 pass #(rcon/command! % "forceload query")))
         (let [landings (str (io/file world "landings.edn"))
               rows (mapv deref
                          (mapv (fn [run]
                                  (future (gym/trial! {:goal (:unit/goal unit)
                                                       :run run
                                                       :name (bot-name unit-index run)
                                                       :dir (io/file dir (str "run-" run))
                                                       :cp (:cp bot)
                                                       :bot-dir (:bot-dir bot)
                                                       :pass pass
                                                       :landings landings
                                                       :commit (:commit bot)
                                                       :tags {:plans/exp (:unit/exp unit)
                                                              :plans/arm (:arm/name (:unit/arm
                                                                                     unit))
                                                              :plans/unit (:unit/id unit)}})))
                                (:unit/runs unit)))]
           (spit (io/file dir "forceload-stop.txt")
                 (rcon/with-rcon "127.0.0.1" 25575 pass #(rcon/command! % "forceload query")))
           rows)
         (finally (cli/exec! ["bb" "server" "stop"] {:env env :log log})
                  (cli/exec! ["cp" (str (io/file world "logs" "latest.log"))
                              (str (io/file dir "server.log"))]
                             {:log log})
                  (cli/exec! ["rm" "-rf" (str world)] {:log log})
                  (doseq [f (file-seq dir)
                          :when (= "run.edn" (.getName ^java.io.File f))]
                    (cli/exec! ["zstd" "-19" "-q" "--rm" (str f)] {:log log}))))))

(defn sim-unit!
  "A sim unit: one seeded shard of a property, run as its test with the plan's seed and size;
   the verdict is read back as data."
  [unit {:keys [out]}]
  (let [dir (io/file out (:unit/id unit))
        verdict (io/file dir "sim.edn")]
    (.mkdirs dir)
    (cli/exec! ["clojure" "-M:test" "-v" (properties (:unit/property unit))]
               {:env {"FOREST_TRIALS" (:unit/worlds unit)
                      "FOREST_SEED" (:unit/seed unit)
                      "FOREST_OUT" (str verdict)}
                :log (io/file dir "sim.log")})
    (let [row (assoc (if (.exists verdict)
                       (edn/read-string (slurp verdict))
                       {:pass? false :num-tests 0 :seed (:unit/seed unit)})
                     :plans/exp (:unit/exp unit)
                     :plans/unit (:unit/id unit))]
      (spit verdict (pr-str row))
      row)))

(defn work!
  "Run job n's assignment of a plan, unit after unit, leaving every result under out."
  [plan n opts]
  (doseq [[i unit] (map-indexed vector (assignment plan n))]
    (cli/log "unit" (:unit/id unit))
    (try (case (:unit/kind unit)
           :gym (gym-unit! unit (inc i) opts)
           :sim (sim-unit! unit opts))
         (catch Exception e
           (cli/log "unit failed:" (:unit/id unit) e)
           (spit (io/file (:out opts) (:unit/id unit) "unit-error.txt") (str e))))))

(defn rows-in
  "Every result row under dir, gym rows (result.edn) and sim rows (sim.edn) apart."
  [dir]
  (let [rows (map :row (gym/located-in dir #{"result.edn" "sim.edn"}))]
    [(filterv :gym/outcome rows) (vec (remove :gym/outcome rows))]))

(defn pull!
  "Download a plan run's job artifacts into data/runs/<run-id>/ (gh run download), print its report
   and every failure with the directory that holds its recording."
  [run-id]
  (let [dir (io/file "data" "runs" (str run-id))]
    (.mkdirs dir)
    (cli/exec! ["gh" "run" "download" (str run-id) "--pattern" "plan-*-job-*" "--dir" (str dir)] {}) ; jobs only: the summary repeats their rows
    (let [[g s] (rows-in dir)
          fs (failures (gym/located-in dir #{"result.edn" "sim.edn"}))]
      (println (report g s))
      (println (str "### failures (" (count fs) ")\n"))
      (doseq [f fs] (println (failure-line f))))))

(defn -main
  "clojure -M:plan <command> --key value ...
     plan   --plan ci/plan/x.edn [--salt s]   matrix=..., width=..., label=... for $GITHUB_OUTPUT
     work   --plan ci/plan/x.edn --job N --out dir [--worlds cache/worlds] [--salt s]
     report --dir artifacts
     pull   --run <run id>   download into data/runs/<id>/, print the report and every failure"
  [command & args]
  (let [{:keys [plan job out worlds dir salt run]} (cli/options args)
        read (fn [p] (cond-> (read-plan p) salt (assoc :plans/salt salt)))]
    (case command
      "plan"
      (let [p (read plan)]
        (println (str "matrix=" (matrix p)))
        (println (str "width=" (width p)))
        (println (str "label=" (:plans/label p))))
      "work"
      (work! (read plan)
             (parse-long job)
             {:out out
              :worlds (or worlds "cache/worlds")
              :bot-dir "."
              :cp (cli/classpath! ".")
              :commit (System/getenv "GITHUB_SHA")})
      "report" (let [[g s] (rows-in dir)] (println (report g s)))
      "pull" (pull! run))
    (shutdown-agents)))
