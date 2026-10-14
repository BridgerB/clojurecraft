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
            [clojurecraft.sim :as sim]))

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
   (main/result) with :run/world (its set and landing), :run/seed, :run/ms (simulated time) and
   :run/violations (what a real server would have punished)."
  [w seed until max-ms]
  (let [opts {:host "sim" :port 0 :name "Clj_sim"}
        [world sim] (sim/run step
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
           :run/violations (:sim/violations sim))))

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
   :run/commit) and, when it failed, what bb failures reads (failure/mark); returns it."
  [w seed until max-ms commit out]
  (let [r (try (run w seed until max-ms)
               (catch Exception e
                 {:ok false
                  :until until
                  :reason :crashed
                  :error (str e)
                  :run/world (select-keys w [:capture/set :capture/landing])
                  :run/seed seed}))
        r (failure/mark (assoc r :run/commit commit :run/file (:capture/file w)))]
    (locking out
      (println "SIMRUN" (pr-str (dissoc r :plan :stats)))
      (spit out (str (pr-str r) "\n") :append true)
      (flush))
    r))

(defn -main
  "Play the worlds under --worlds (directories, comma-separated; this runner's share of them with
   --runner k --runners n) on --threads threads (the cores when absent): each world with seeds
   from --seed (1) up, round the worlds, until --budget-minutes of wall time have passed (or
   --seeds each when no budget is given). --world f plays one file. --runs f plays exactly the
   runs an EDN file lists, [[world-file seed] ...] (an issue's reproductions, bb issue-check).
   Prints a SIMRUN line per run and the SIMRACE tally; keeps every run in <out>/runs.edn."
  [& args]
  (let [o (cli/options args)
        {:keys [worlds seed seeds until threads out runner runners commit]
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
