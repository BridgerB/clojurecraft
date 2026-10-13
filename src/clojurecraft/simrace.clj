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
   (signature: the target it was on and why it stopped) is what groups failures into one
   issue however many runs hit it.

   A world is read once and its columns decoded once (prepared); every run of it shares that
   value. The model holds the whole world: nothing loads or unloads."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojurecraft.capture :as capture]
            [clojurecraft.chunk :as chunk]
            [clojurecraft.cli :as cli]
            [clojurecraft.game :as game]
            [clojurecraft.main :as main]
            [clojurecraft.plan :as plan]
            [clojurecraft.sim :as sim])
  (:import (java.util.concurrent Callable Executors ExecutorService Future)))

(set! *warn-on-reflection* true)

(def default-seeds 8)                 ; runs of each world
(def default-until "nether")          ; the target a run plays every step up to
(def default-max-minutes 90)          ; simulated minutes a run may take

(def step "The bot's reducer, as a live run composes it." (game/compose game/step plan/step))

;; ---------------------------------------------------------------- a run

(defn prepared
  "A world file's value with its columns decoded once (:capture/decoded), for every run of it to
   share."
  [w]
  (assoc w
         :capture/decoded
         (into {} (map (fn [[k bytes]] [k (chunk/decode bytes)])) (:capture/columns w))))

(defn spawn-of
  "Where a run starts: on the landing ([x z y], its surface) at the column's centre."
  [{[x z y] :capture/landing}]
  [(+ x 0.5) (double y) (+ z 0.5)])

(defn stage
  "The target a run was on when it stopped: the first of until's chain it had not reached."
  [until reached]
  (first (remove (set reached) (plan/through until))))

(defn signature
  "What groups failures: [the target the run was on, why it stopped, the last intent's kind]."
  [{:keys [until reason plan]}]
  [(stage until (:plan/reached plan)) reason (get-in plan [:plan/last :intent/kind])])

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
                          (map signature)
                          frequencies
                          (sort-by (comp - val))
                          vec)})

;;;; I/O: the world files, the threads, the lines ;;;;

(defn world-files
  "The world files under dir, in their order in the set (1, 2, ...)."
  [dir]
  (let [file-name (fn [^java.io.File f] (.getName f))]
    (->> (.listFiles (io/file dir))
         (filter #(str/ends-with? (file-name %) ".edn.gz"))
         (sort-by #(parse-long (first (str/split (file-name %) #"\."))))
         vec)))

(defn play-world!
  "Every seed of one world on the pool, each run's RESULT printed as it ends and appended to
   out; returns the runs."
  [^ExecutorService pool w seeds until max-ms out]
  (let [futures (mapv (fn [seed]
                        (.submit pool
                                 ^Callable
                                 (fn []
                                   (let [r (try (run w seed until max-ms)
                                                (catch Exception e
                                                  {:ok false
                                                   :until until
                                                   :reason :crashed
                                                   :error (str e)
                                                   :run/world
                                                   (select-keys w [:capture/set :capture/landing])
                                                   :run/seed seed}))]
                                     (locking out
                                       (println "SIMRUN" (pr-str (dissoc r :plan)))
                                       (spit out (str (pr-str r) "\n") :append true)
                                       (flush))
                                     r))))
                      seeds)]
    (mapv #(.get ^Future %) futures)))

(defn -main
  "Play --seeds runs of every world under --worlds (or of the one --world, from --seed) on
   --threads threads (the cores when absent); print a SIMRUN line per run and the SIMRACE
   tally, keep every run in <out>/runs.edn."
  [& args]
  (let [o (cli/options args)
        {:keys [worlds seed seeds until threads out] minutes :max-minutes :or {out "data/simrace"}}
        o
        world (get o :world)
        until (or until default-until)
        max-ms (* 60000 (Long/parseLong (or minutes (str default-max-minutes))))
        n (Long/parseLong (or seeds (str default-seeds)))
        first-seed (Long/parseLong (or seed "1"))
        files (if world [(io/file world)] (world-files worlds))
        pool (Executors/newFixedThreadPool
              (int (Long/parseLong (or threads (str (.availableProcessors (Runtime/getRuntime)))))))
        runs-file (str (io/file out "runs.edn"))]
    (io/make-parents runs-file)
    (let [runs (into []
                     (mapcat (fn [f]
                               (cli/log "world" (str f))
                               (play-world! pool
                                            (prepared (capture/read-world f))
                                            (range first-seed (+ first-seed n))
                                            until
                                            max-ms
                                            runs-file)))
                     files)]
      (println "SIMRACE" (pr-str (tally runs))))
    (.shutdown pool)
    (shutdown-agents)))
