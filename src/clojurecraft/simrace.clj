(ns clojurecraft.simrace
  "The sim race: the whole bot, from empty hands at a landing to the race's target, against the
   server model (sim.clj) in captured real worlds (capture.clj), as many runs as a machine's
   cores can play, with no Java server.

     clojure -M:simrace --worlds cache/sim-worlds/R1 [--seeds 8] [--until nether]
       [--max-minutes 90] [--threads n] [--out data/simrace]

   A run is a pure function of three inputs: the world file, the seed (the model's loot rolls
   and the bot's tick randomness, sim/tick-rand) and the code. So a run is never saved to be
   kept: its world, seed and commit are the reproduction (--world f --seed s replays one), and
   it is the same run on any machine. Each run ends in a RESULT the way a real run does
   (main/result), printed as a SIMRUN line and kept in <out>/runs.edn; a failure's signature
   (failure/signature: the target it was on and why it stopped) is what groups failures into one
   issue however many runs hit it.

   A world is read once and its columns decoded once (prepared); every run of it shares that
   value. The model holds the whole world: nothing loads or unloads."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojurecraft.capture :as capture]
            [clojurecraft.chunk :as chunk]
            [clojurecraft.cli :as cli]
            [clojurecraft.failure :as failure]
            [clojurecraft.game :as game]
            [clojurecraft.main :as main]
            [clojurecraft.plan :as plan]
            [clojurecraft.sim :as sim]
            [datascript.core :as d]))

(set! *warn-on-reflection* true)

(def default-seeds 8)                 ; runs of each world
(def default-until "nether")          ; the target a run plays every step up to
(def default-max-minutes 90)          ; simulated minutes a run may take

(def step "The bot's reducer, as a live run composes it." (game/compose game/step plan/step))

;; ---------------------------------------------------------------- a run

(defn prepared
  "A world file's value with its columns decoded once (:capture/decoded), for every run of it to
   share. Throws when it is not a world (no landing, no columns): every run of it would crash,
   as fast as a run can start."
  [w]
  (when-not (and (:capture/landing w) (seq (:capture/columns w)))
    (throw (ex-info "not a captured world" {:keys (keys w)})))
  (assoc w
         :capture/decoded
         (into {} (map (fn [[k bytes]] [k (chunk/decode bytes)])) (:capture/columns w))))

(defn spawn-of
  "Where a run starts: on the landing ([x z y], its surface) at the column's centre."
  [{[x z y] :capture/landing}]
  [(+ x 0.5) (double y) (+ z 0.5)])

(defn run
  "One run: the bot from empty hands on world w's landing, playing every step up to until,
   against the model, at most max-ms of simulated time, rolling from seed. Returns its RESULT
   (main/result) with :run/world (its set and landing), :run/seed, :run/ms (simulated time),
   :run/violations (what a real server would have punished) and :run/before, the world and
   model one tick before the stop (a snapshot's moment; dropped from the lines written)."
  [w seed until max-ms]
  (let [opts {:host "sim" :port 0 :name "Clj_sim"}
        {:keys [world sim before]}
        (sim/run+ step
                  (game/init opts)
                  (sim/init {:columns (:capture/columns w)
                             :decoded (:capture/decoded w)
                             :spawn (spawn-of w)
                             :seed seed
                             :tick-seed seed})
                  #(or (plan/done? %) (plan/failed? %))
                  max-ms
                  {:event/kind :go :go/goals (plan/through until) :go/chain? true})
        ok (main/ok? world until)]
    (assoc (main/result world until ok)
           :run/world (select-keys w [:capture/set :capture/landing])
           :run/seed seed
           :run/ms (:time/now world)
           :run/violations (:sim/violations sim)
           :run/before before)))

;; ---------------------------------------------------------------- a failing moment, as a value

(def snapshot-budget-ms 120000)       ; simulated ms a snapshot is played on for: two minutes

(defn snapshot
  "A failed run's moment as a value to keep (issue #77): the bot's world and the model's state
   one tick before the stop (run+'s :before), the captured world's file (its chunks and columns
   are not copied: the keys the bot held are, to reattach them, load-snapshot), the seed, the
   target, the code and the signature the run stopped with. The facts are d/serializable, since a DataScript
   value does not print as EDN. Everything else prints as the recordings print (byte arrays as
   #clojurecraft/bytes)."
  [w seed until commit signature [world sim]]
  {:snapshot/file (:capture/file w)
   :snapshot/seed seed
   :snapshot/until until
   :snapshot/commit commit
   :snapshot/signature signature
   :snapshot/chunks (set (keys (:world/chunks world)))
   :snapshot/world (-> world
                       (dissoc :world/chunks)
                       (update :world/facts d/serializable))
   :snapshot/sim (dissoc sim :sim/columns :sim/decoded)})

(defn snapshot-name
  "The file a run's snapshot goes to under dir: <set>-<n>-<seed>.edn.gz, n the world file's
   number in its set."
  [dir w seed]
  (str (io/file dir
                (str (:capture/set w)
                     "-"
                     (first (str/split (.getName (io/file (str (:capture/file w)))) #"\."))
                     "-"
                     seed
                     ".edn.gz"))))

(defn moment
  "The world and model a snapshot holds, ready to play on: the captured world's chunks the bot
   held reattached (decoded once, prepared), the model's columns too, the facts a DataScript
   value again. w is the captured world the snapshot names, prepared."
  [{:snapshot/keys [chunks world sim]} w]
  [(-> world
       (assoc :world/chunks (select-keys (:capture/decoded w) chunks))
       (update :world/facts d/from-serializable))
   (assoc sim :sim/columns (:capture/columns w) :sim/decoded (:capture/decoded w))])

(defn play-snapshot
  "A snapshot played on for budget-ms of simulated time, from the moment it holds, until the plan
   is done or failed: the RESULT, with :run/signature when it failed (failure/mark) and
   :run/ms the time it ended at."
  [snap w budget-ms]
  (let [[world sim] (moment snap w)
        until (:snapshot/until snap)
        {world' :world} (sim/play-on step
                                     world
                                     sim
                                     #(or (plan/done? %) (plan/failed? %))
                                     budget-ms
                                     (:time/now world)
                                     []
                                     nil)]
    (assoc (failure/mark (main/result world' until (main/ok? world' until)))
           :run/ms
           (:time/now world'))))

(defn recurs?
  "Does a snapshot's signature recur when it is played on: the plan failed with it again? A
   plan still running at the budget, or failed otherwise, got past this moment."
  [snap played]
  (= (:snapshot/signature snap) (:run/signature played)))

(defn tally
  "The runs as a value: how many, how many reached the target, and the failures counted by
   signature, most first."
  [runs]
  {:simrace/runs (count runs)
   :simrace/ok (count (filter :ok runs))
   :simrace/failures (->> (remove :ok runs)
                          (map failure/signature)
                          frequencies
                          (sort-by (comp - val))
                          vec)})

(defn shard
  "Runner k's share of n runners' worth of items: every n-th, from the k-th (1-based)."
  [items k n]
  (vec (take-nth n (drop (dec k) items))))

(defn jobs
  "The runs to play, [world-index seed] each: every world with one seed, then every world with
   the next, from first-seed; seeds of them when given, else without end (a time budget ends it)."
  [n-worlds first-seed seeds]
  (for [s (if seeds (range first-seed (+ first-seed seeds)) (iterate inc first-seed))
        i (range n-worlds)]
    [i s]))

(defn given-jobs
  "The runs a list names, [[world-file seed] ...], as jobs [world-index seed] over files (the
   distinct world files of the list, in its order)."
  [given files]
  (let [index (into {} (map-indexed (fn [i f] [(str f) i])) files)]
    (mapv (fn [[f s]] [(index (str f)) s]) given)))

;;;; I/O: the world files, the threads, the lines ;;;;

(defn world-files
  "The world files under dir, in their order in the set (1, 2, ...)."
  [dir]
  (let [file-name (fn [^java.io.File f] (.getName f))]
    (->> (.listFiles (io/file dir))
         (filter #(str/ends-with? (file-name %) ".edn.gz"))
         (sort-by #(parse-long (first (str/split (file-name %) #"\."))))
         vec)))

(defn play!
  "One run, never throwing (a run that throws is a :crashed RESULT), its RESULT printed as a
   SIMRUN line and appended to out as it ends, with what reproduces it (:run/file, :run/seed,
   :run/commit) and, when it failed, what bb failures reads (failure/mark) and its moment kept
   as a snapshot beside out (:run/snapshot, issue #77); returns it."
  [w seed until max-ms commit out]
  (let [r (try (run w seed until max-ms)
               (catch Exception e
                 {:ok false
                  :until until
                  :reason :crashed
                  :error (str e)
                  :run/world (select-keys w [:capture/set :capture/landing])
                  :run/seed seed}))
        r (failure/mark (assoc r :run/commit commit :run/file (:capture/file w)))
        path
        (when (and (:run/signature r) (:run/before r))
          (let [p (snapshot-name (io/file (.getParent (io/file (str out))) "snapshots") w seed)]
            (capture/write-world! p
                                  (snapshot w seed until commit (:run/signature r) (:run/before r)))
            p))
        r (cond-> (dissoc r :run/before) path (assoc :run/snapshot path))]
    (locking out
      (println "SIMRUN" (pr-str (dissoc r :plan :stats)))
      (spit out (str (pr-str r) "\n") :append true)
      (flush))
    r))

(defn play-snapshot!
  "Play the snapshot at path on (play-snapshot), its captured world read and prepared, and print
   whether its signature recurs; returns the RESULT."
  [path budget-ms]
  (let [snap (capture/read-world path)
        w (assoc (prepared (capture/read-world (:snapshot/file snap)))
                 :capture/file
                 (:snapshot/file snap))
        r (play-snapshot snap w budget-ms)]
    (println "SNAPSHOT"
             (pr-str {:snapshot/signature (:snapshot/signature snap)
                      :run/signature (:run/signature r)
                      :recurs? (recurs? snap r)
                      :reason (:reason r)
                      :plan/status (get-in r [:plan :plan/status])
                      :run/ms (:run/ms r)}))
    r))

(defn race!
  "The sim race -main runs for its options o (cli/options), every option but --snapshot."
  [o]
  (let [{:keys [worlds seed seeds until threads out runner runners commit]
         minutes :max-minutes
         :or {out "data/simrace"}}
        o
        world (get o :world)
        budget (get o :budget-minutes)
        given (some-> (get o :runs)
                      slurp
                      edn/read-string)
        until (or until default-until)
        max-ms (* 60000 (Long/parseLong (or minutes (str default-max-minutes))))
        first-seed (Long/parseLong (or seed "1"))
        files (cond given (mapv io/file (distinct (map first given)))
                    world [(io/file world)]
                    :else
                    (shard (into [] (mapcat world-files) (str/split worlds #","))
                           (Long/parseLong (or runner "1"))
                           (Long/parseLong (or runners "1"))))
        ws (mapv (fn [f]
                   (cli/log "world" (str f))
                   (assoc (prepared (capture/read-world f)) :capture/file (str f)))
                 files)
        deadline (when budget (+ (System/currentTimeMillis) (* 60000 (Long/parseLong budget))))
        todo (atom (if given
                     (given-jobs given files)
                     (jobs (count ws)
                           first-seed
                           (when-not budget (Long/parseLong (or seeds (str default-seeds)))))))
        take! (fn [] (let [[old _] (swap-vals! todo rest)] (first old)))
        runs-file (str (io/file out "runs.edn"))
        n-threads (Long/parseLong (or threads (str (.availableProcessors (Runtime/getRuntime)))))
        worker (fn []
                 (loop [done []]
                   (let [job (when (or (nil? deadline) (< (System/currentTimeMillis) deadline))
                               (take!))]
                     (if-let [[i s] job]
                       (recur (conj done (play! (ws i) s until max-ms commit runs-file)))
                       done))))]
    (io/make-parents runs-file)
    (cli/log "simrace:" (count ws) "worlds," n-threads "threads, until" until)
    (let [workers (mapv (fn [_] (future (worker))) (range n-threads))
          runs (into [] (mapcat deref) workers)]
      (println "SIMRACE" (pr-str (tally runs))))
    (shutdown-agents)))

(defn -main
  "Play the worlds under --worlds (directories, comma-separated; this runner's share of them with
   --runner k --runners n) on --threads threads (the cores when absent): each world with seeds
   from --seed (1) up, round the worlds, until --budget-minutes of wall time have passed (or
   --seeds each when no budget is given). --world f plays one file. --runs f plays exactly the
   runs an EDN file lists, [[world-file seed] ...] (an issue's reproductions). --snapshot f
   plays the failing moment a snapshot holds on for two simulated minutes (play-snapshot!) and
   prints one SNAPSHOT line: whether its signature recurs. Prints a SIMRUN line per run and
   the SIMRACE tally; keeps every run in <out>/runs.edn and each failed run's snapshot in
   <out>/snapshots."
  [& args]
  (let [o (cli/options args)]
    (cond (get o :snapshot)
          (do (play-snapshot! (get o :snapshot) snapshot-budget-ms) (shutdown-agents))
          :else (race! o))))
