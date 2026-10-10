(ns clojurecraft.fleet
  "The fleet: many runners kept busy at once, from one plan file. A plan (ci/fleet/<label>.edn)
   is data: how many workers, how wide, and the experiments to run. The planner is pure: it
   expands experiments into units (a gym unit is k trials sharing one server, each its own bot on
   its own landing; a sim unit is one seeded shard of a generated-world property), then schedules
   units onto workers longest first. Only worker numbers cross the job boundary; each worker
   recomputes its own assignment from the plan, so every worker and the aggregate agree.

   Workers are processes speaking data: the worker starts each bot as its own JVM, lands it and
   tells it to go through its stdin (the gym's fixture), judges it from its log and the server,
   and leaves a result file per trial. The aggregate folds every result into one report."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [clojurecraft.gym :as gym]
            [clojurecraft.harness :as harness]
            [clojurecraft.rcon :as rcon]
            [clojurecraft.spec]))

(def default-max-worker-s 19800)        ; 5.5 h: a GitHub job may run 6 h
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
          :unit/worlds (long (Math/ceil (/ worlds shards)))
          :unit/est-s est-s})))

(defn units
  "Every unit a plan asks for, in plan order."
  [{:fleet/keys [label salt experiments]}]
  (vec (mapcat (fn [exp]
                 (case (:exp/kind exp)
                   :gym (gym-units exp)
                   :sim (sim-units (str label salt) exp)))
               experiments)))

(defn schedule
  "Units onto workers, longest first, each to the least loaded worker: {worker [unit ...]}.
   Throws when a worker would pass max-worker-s, so a plan that cannot fit fails before it
   starts rather than when a job is killed at six hours."
  [{:fleet/keys [workers max-worker-s] :as plan}]
  (let [limit (or max-worker-s default-max-worker-s)
        ordered (sort-by (juxt (comp - :unit/est-s) :unit/id) (units plan))
        loads (reduce (fn [loads u]
                        (let [[w {:keys [s]}] (first (sort-by (fn [[w {:keys [s]}]] [s w]) loads))
                              s' (+ s (:unit/est-s u))]
                          (when (> s' limit)
                            (throw (ex-info "plan does not fit its workers" {:unit (:unit/id u) :worker w :seconds s'})))
                          (update loads w (fn [l] (-> l (update :s + (:unit/est-s u)) (update :units conj u))))))
                      (into (sorted-map) (for [w (range 1 (inc workers))] [w {:s 0 :units []}]))
                      ordered)]
    (into (sorted-map) (for [[w {:keys [units]}] loads :when (seq units)] [w units]))))

(defn assignment "The units worker w runs." [plan w] (get (schedule plan) w []))

(defn matrix
  "The workflow matrix: only the worker numbers that have work."
  [plan]
  (str "{\"worker\":[" (str/join "," (keys (schedule plan))) "]}"))

(defn width
  "How many of this fleet's workers to run at once: the plan's width, no more than the free
   runners (cap minus busy jobs elsewhere), and at least one."
  [{:fleet/keys [max-parallel workers]} cap busy]
  (max 1 (min (or max-parallel workers) (- cap busy))))

(defn read-plan
  "A plan file, read and checked against its spec: a malformed plan fails in the plan job."
  [path]
  (let [plan (edn/read-string (slurp path))]
    (when-not (s/valid? :clojurecraft.spec/fleet-plan plan)
      (throw (ex-info (str path " is not a fleet plan: " (s/explain-str :clojurecraft.spec/fleet-plan plan)) {})))
    plan))

;; ---------------------------------------------------------------- reporting

(defn sim-line
  "One sim experiment's verdict: worlds run, shards that failed, and each shrunk counterexample
   with the seed that reproduces it."
  [name rows]
  (let [worlds (reduce + (map #(:num-tests % 0) rows))
        failed (remove :pass? rows)]
    (str/join "\n"
              (concat [(str "### sim " name) ""
                       (str "worlds " worlds " in " (count rows) " shards; failing shards " (count failed)) ""]
                      (for [f failed]
                        (str "- seed " (:seed f) ": smallest failing input `" (pr-str (:smallest f)) "`"))
                      [""]))))

(defn report
  "The fleet's markdown report: each gym experiment and arm through gym/report, then each sim."
  [gym-rows sim-rows]
  (str/join
   "\n"
   (concat
    (for [[[exp arm] rows] (sort-by key (group-by (juxt :fleet/exp :fleet/arm) gym-rows))]
      (str "## " exp " (" arm ")\n\n" (gym/report rows)))
    (for [[exp rows] (sort-by key (group-by :fleet/exp sim-rows))]
      (sim-line exp rows)))))

(defn failures
  "The trials and shards a person should look at: every row that is not a pass, with where it
   lives on disk, gym rows by outcome first."
  [located]
  (sort-by (juxt (comp str :gym/outcome :row) :path)
           (remove (fn [{:keys [row]}] (or (= :pass (:gym/outcome row)) (true? (:pass? row)))) located)))

(defn failure-line
  "One failure as a line: what failed, why, and the directory holding its recording and logs."
  [{:keys [row path]}]
  (if (contains? row :gym/outcome)
    (str "- " (:fleet/exp row) " (" (:fleet/arm row) ") run " (:gym/run row) " " (:gym/outcome row)
         " " (:gym/reason row) " at " (pr-str (:gym/landing row)) ": " path)
    (str "- sim " (:fleet/exp row) " seed " (:seed row) " smallest " (pr-str (:smallest row)) ": " path)))

(def properties
  "Sim properties a fleet can shard, by name: the test var that runs one."
  {"forests" "clojurecraft.sim-test/the-wood-goal-holds-in-generated-forests"})

(defn bot-name "A bot's name in a unit: short, unique on its server." [unit-index run] (str "C" unit-index "r" run))

;;;; I/O: processes, servers, files ;;;;

(defn exec!
  "Run a command to completion in dir with extra env; its output goes to log (a file). Returns
   the exit code."
  [cmd {:keys [dir env log]}]
  (let [pb (ProcessBuilder. ^java.util.List (mapv str cmd))]
    (when dir (.directory pb (io/file dir)))
    (doseq [[k v] env] (.put (.environment pb) (str k) (str v)))
    (.redirectErrorStream pb true)
    (if log (.redirectOutput pb (java.lang.ProcessBuilder$Redirect/appendTo (io/file log)))
        (.redirectOutput pb java.lang.ProcessBuilder$Redirect/INHERIT))
    (.waitFor (.start pb))))

(defn classpath!
  "The bot's classpath in dir (`clojure -Spath`), computed once per worker so each bot starts as
   a plain java process."
  [dir]
  (let [f (java.io.File/createTempFile "classpath" ".txt")]
    (exec! ["clojure" "-Spath"] {:dir dir :log f})
    (str/trim (last (str/split-lines (slurp f))))))

(defn rcon-pass "A fresh RCON password for one server." []
  (apply str (repeatedly 32 #(rand-nth "0123456789abcdef"))))

(defn attempt!
  "One bot on a running server: start it as its own JVM, land it (the gym's fixture) and send
   its :go through its stdin, judge it from its log and the server, and leave result.edn,
   landed.edn, bot.log and run.edn in dir. Returns the result row, tagged with the experiment
   and arm."
  [{:keys [unit unit-index run dir cp bot-dir pass landings commit]}]
  (.mkdirs (io/file dir))
  (let [row (gym/gym (:unit/goal unit))
        name (bot-name unit-index run)
        bot-log (io/file dir "bot.log")
        ^java.util.List cmd ["java" "-Xmx1g" "-cp" cp "clojure.main" "-m" "clojurecraft.main"
                             "--host" "127.0.0.1" "--port" "25565" "--name" name "--until" (:gym/until row)
                             "--events" "stdin" "--timeout-ms" (str (:gym/timeout-ms row)) "--hold-ms" "60000"
                             "--record" (str (io/file dir "run.edn"))]
        pb (doto (ProcessBuilder. cmd)
             (.directory (io/file bot-dir))
             (.redirectErrorStream true)
             (.redirectOutput bot-log))
        p (.start pb)
        landed (gym/landing! {:goal (:unit/goal unit) :run run :name name :rcon-port 25575 :rcon-pass pass
                              :landings landings})
        landed-file (io/file dir "landed.edn")]
    (spit landed-file (pr-str landed))
    (with-open [w (io/writer (.getOutputStream p))]
      (.write w (str (pr-str (harness/go-event (:gym/until row) (:gym/at landed))) "\n")))
    (let [judged (gym/judge! {:goal (:unit/goal unit) :run run :name name :rcon-port 25575 :rcon-pass pass
                              :bot-log (str bot-log) :landed (str landed-file) :out (str (io/file dir "result.edn"))
                              :commit commit})
          tagged (assoc judged :fleet/exp (:unit/exp unit) :fleet/arm (:arm/name (:unit/arm unit)) :fleet/unit (:unit/id unit))]
      (.destroy p)
      (.waitFor p)
      (spit (io/file dir "result.edn") (pr-str tagged))
      tagged)))

(defn trial!
  "One trial, as gym-run.sh runs it: a run that loses its connection says nothing about the goal,
   so it is attempted once more on the same landing. A trial that throws is a :harness row, never
   a lost unit."
  [{:keys [unit run dir] :as t}]
  (try
    (let [row (attempt! t)]
      (if (= :disconnect (:gym/outcome row))
        (attempt! (assoc t :dir (io/file dir "retry")))
        row))
    (catch Throwable e
      (harness/log "trial failed:" e)
      (let [row {:gym/goal (:gym/goal (gym/gym (:unit/goal unit))) :gym/run run :gym/outcome :harness
                 :gym/reason :harness-error :gym/error (ex-message e)
                 :fleet/exp (:unit/exp unit) :fleet/arm (:arm/name (:unit/arm unit)) :fleet/unit (:unit/id unit)}]
        (.mkdirs (io/file dir))
        (spit (io/file dir "result.edn") (pr-str row))
        row))))

(defn gym-unit!
  "A gym unit: a fresh copy of the set's world, one server, its trials' bots side by side, the
   server stopped; forceload checked empty before and logged after."
  [unit unit-index {:keys [out worlds cp bot-dir commit heap-mb]}]
  (let [dir (io/file out (:unit/id unit))
        world (io/file dir "world")
        pass (rcon-pass)
        log (io/file dir "unit.log")
        env {"PORT" 25565 "RCON_PORT" 25575 "RCON_PASS" pass "WORLD" (str world) "HEAP_MB" (or heap-mb 6144)}]
    (.mkdirs dir)
    (exec! ["cp" "-r" (str (io/file worlds (:unit/set unit))) (str world)] {:log log})
    (try
      (when-not (zero? (exec! ["scripts/server.sh" "start"] {:env env :log log}))
        (throw (ex-info "server did not start" {:unit (:unit/id unit)})))
      (spit (io/file dir "forceload-start.txt")
            (rcon/with-rcon "127.0.0.1" 25575 pass #(rcon/command % "forceload query")))
      (let [landings (str (io/file world "landings.edn"))
            rows (mapv deref (doall (for [run (:unit/runs unit)]
                                      (future (trial! {:unit unit :unit-index unit-index :run run
                                                       :dir (io/file dir (str "run-" run)) :cp cp :bot-dir bot-dir
                                                       :pass pass :landings landings :commit commit})))))]
        (spit (io/file dir "forceload-stop.txt")
              (rcon/with-rcon "127.0.0.1" 25575 pass #(rcon/command % "forceload query")))
        rows)
      (finally
        (exec! ["scripts/server.sh" "stop"] {:env env :log log})
        (exec! ["cp" (str (io/file world "logs" "latest.log")) (str (io/file dir "server.log"))] {:log log})
        (exec! ["rm" "-rf" (str world)] {:log log})
        (doseq [f (file-seq dir) :when (= "run.edn" (.getName ^java.io.File f))]
          (exec! ["zstd" "-19" "-q" "--rm" (str f)] {:log log}))))))

(defn sim-unit!
  "A sim unit: one seeded shard of a property, run as its test with the fleet's seed and size;
   the verdict is read back as data."
  [unit {:keys [out]}]
  (let [dir (io/file out (:unit/id unit))
        verdict (io/file dir "sim.edn")]
    (.mkdirs dir)
    (exec! ["clojure" "-M:test" "-v" (properties (:unit/property unit))]
           {:env {"FOREST_TRIALS" (:unit/worlds unit) "FOREST_SEED" (:unit/seed unit) "FOREST_OUT" (str verdict)}
            :log (io/file dir "sim.log")})
    (let [row (assoc (if (.exists verdict) (edn/read-string (slurp verdict)) {:pass? false :num-tests 0 :seed (:unit/seed unit)})
                     :fleet/exp (:unit/exp unit) :fleet/unit (:unit/id unit))]
      (spit verdict (pr-str row))
      row)))

(defn work!
  "Run worker w's assignment of a plan, unit after unit, leaving every result under out."
  [plan w opts]
  (doseq [[i unit] (map-indexed vector (assignment plan w))]
    (harness/log "unit" (:unit/id unit))
    (try
      (case (:unit/kind unit)
        :gym (gym-unit! unit (inc i) opts)
        :sim (sim-unit! unit opts))
      (catch Throwable e
        (harness/log "unit failed:" (:unit/id unit) e)
        (spit (io/file (:out opts) (:unit/id unit) "unit-error.txt") (str e))))))

(defn located-in
  "Every result row under dir with the directory it came from: [{:row :path}]. A retried trial
   keeps only its retry."
  [dir]
  (let [files (filter #(#{"result.edn" "sim.edn"} (.getName ^java.io.File %)) (file-seq (io/file dir)))
        retried (set (for [^java.io.File f files :when (= "retry" (.getName (.getParentFile f)))]
                       (str (.getParentFile (.getParentFile f)))))]
    (vec (for [^java.io.File f files
               :let [d (str (.getParentFile f))]
               :when (not (retried d))]
           {:row (edn/read-string (slurp f)) :path d}))))

(defn rows-in
  "Every result row under dir, gym rows (result.edn) and sim rows (sim.edn) apart."
  [dir]
  (let [rows (map :row (located-in dir))]
    [(vec (filter :gym/outcome rows)) (vec (remove :gym/outcome rows))]))

(defn pull!
  "Download a fleet run's worker artifacts into data/runs/<run-id>/ (gh run download), print its report
   and every failure with the directory that holds its recording."
  [run-id]
  (let [dir (io/file "data" "runs" (str run-id))]
    (.mkdirs dir)
    (exec! ["gh" "run" "download" (str run-id) "--pattern" "fleet-*-w*" "--dir" (str dir)] {}) ; workers only: the summary repeats their rows
    (let [[g s] (rows-in dir)
          fs (failures (located-in dir))]
      (println (report g s))
      (println (str "### failures (" (count fs) ")\n"))
      (doseq [f fs] (println (failure-line f))))))

(defn -main
  "clojure -M:fleet <command> --key value ...
     plan   --plan ci/fleet/x.edn [--busy N] [--salt s]   matrix=..., width=..., label=... for $GITHUB_OUTPUT
     work   --plan ci/fleet/x.edn --worker N --out dir [--worlds cache/worlds] [--salt s]
     report --dir artifacts
     pull   --run <run id>   download into data/runs/<id>/, print the report and every failure"
  [command & args]
  (let [{:keys [plan worker out worlds dir busy salt run]} (gym/args->map args)
        read (fn [p] (cond-> (read-plan p) salt (assoc :fleet/salt salt)))]
    (case command
      "plan" (let [p (read plan)]
               (println (str "matrix=" (matrix p)))
               (println (str "width=" (width p 20 (parse-long (or busy "0")))))
               (println (str "label=" (:fleet/label p))))
      "work" (work! (read plan) (parse-long worker)
                    {:out out :worlds (or worlds "cache/worlds") :bot-dir "." :cp (classpath! ".")
                     :commit (System/getenv "GITHUB_SHA")})
      "report" (let [[g s] (rows-in dir)] (println (report g s)))
      "pull" (pull! run))
    (shutdown-agents)))
