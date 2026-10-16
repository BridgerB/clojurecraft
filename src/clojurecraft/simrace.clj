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
            [clojurecraft.memory :as memory]
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

(defn go-of
  "The :go a run of w to until is given once its bot is loaded: the chain to until and the
   landing (:go/at)."
  [w until]
  {:event/kind :go :go/goals (plan/through until) :go/chain? true :go/at (spawn-of w)})

(defn run
  "One run: the bot from empty hands on world w's landing, playing every step up to until,
   against the model, at most max-ms of simulated time, rolling from seed. Returns its RESULT
   (main/result) with :run/world (its set and landing), :run/seed, :run/ms (simulated time),
   :run/violations (what a real server would have punished), :run/end (the world it stopped
   in), :run/last (the moment of the
   stopping tick) and :run/moments (the moments sim/play-from kept, every half minute, the
   last six minutes), from which a failure's snapshot is chosen (dropped from the lines written). Its
   :go names the landing (:go/at), as race/land-bot! does for a real bot: the plan's
   landing-relative choices (the climb home from deep, a climb's level steps toward the landing,
   how far they may go) are the real race's. Without it, sim race 38659291345 world R4/38 (issue
   #37) climbed under a lake with 32 level steps where a real bot has 32 and the way home, and
   was :boxed five columns short of the shore."
  [w seed until max-ms]
  (let [opts {:host "sim" :port 0 :name "Clj_sim"}
        {:keys [world sim last moments crashed]} (sim/run+ step
                                                           (game/init opts)
                                                           (sim/init {:columns (:capture/columns w)
                                                                      :decoded (:capture/decoded w)
                                                                      :spawn (spawn-of w)
                                                                      :seed seed
                                                                      :tick-seed seed})
                                                           #(or (plan/done? %) (plan/failed? %))
                                                           max-ms
                                                           (go-of w until))
        ok (and (nil? crashed) (main/ok? world until))]
    (assoc (cond-> (main/result world until ok)
             crashed (assoc :reason :crashed :error (str crashed)))
           :run/world (select-keys w [:capture/set :capture/landing])
           :run/seed seed
           :run/ms (:time/now world)
           :run/violations (:sim/violations sim)
           :run/end world
           :run/last last
           :run/moments moments)))

;; ---------------------------------------------------------------- a failing moment, as a value

(def snapshot-budget-ms 120000)       ; simulated ms an old snapshot (no :snapshot/moment) is played on for
(def decision-lead-ms 30000)          ; a snapshot's moment comes at least this long before the choice that failed
(def stall-window-ms 300000)          ; a :timeout is judged over this window: five simulated minutes with no new stage
(def window-tail-ms 60000)            ; a failure's window runs this long past the stop it led to

(defn decision
  "When and why a failed run went wrong, from the world at its stop and its signature:
   {:why :t}. A :timeout is a stall: no new stage for the run's last stall-window-ms. A wait
   that ran out (the plan failed with the reason it was waiting on) went wrong when the wait
   began, :plan/waiting-since. Anything else is an intent that failed max-attempts times in a
   row: the first of those failures started when its intention's :started fact says (memory
   intentions), so the choice that failed is caught, not the tick before the stop. A crash went
   wrong at the tick that threw."
  [world signature]
  (let [now (:time/now world)
        facts (memory/intentions world)
        streak (->> facts
                    (remove #(= :started (:intention/event %)))
                    reverse
                    (take-while #(= :failed (:intention/event %))))
        first-id (:intention/id (last streak))
        started (some #(when (and (= first-id (:intention/id %)) (= :started (:intention/event %)))
                         (:intention/at %))
                      facts)]
    (cond (= :timeout (second signature)) {:why :stall :t (- now stall-window-ms)}
          (= :crashed (second signature)) {:why :crash :t now}
          (and (:plan/waiting-since world) (= (:plan/reason world) (:plan/waiting world)))
          {:why :wait :t (:plan/waiting-since world)}
          :else {:why :intent-failed :t (or started now)})))

(defn chosen-moment
  "The moment a failure is kept from: the newest of the run's moments at least decision-lead-ms
   before its decision, else the oldest kept (the ring holds six minutes), else the stopping
   tick's. With :moment, {:why :t :decision-t :stop-t :window-ms}: the window it is judged over,
   from the moment to window-tail-ms past the stop, or stall-window-ms for a stall."
  [moments stopping {:keys [why t]} stop-t]
  (let [m (or (last (filter #(<= (:t %) (- t decision-lead-ms)) moments)) (first moments) stopping)]
    (assoc m
           :moment
           {:why why
            :t (:t m)
            :decision-t t
            :stop-t stop-t
            :window-ms (cond (= :stall why) stall-window-ms
                             :else (+ (- stop-t (:t m)) window-tail-ms))})))

(defn snapshot
  "A failed run's moment as a value to keep (issue #77): a moment the run kept (chosen-moment:
   before the choice that failed, not the tick before the stop), whole: the bot's world, the
   model's state and what the next tick depends on (:snapshot/next: pending packets, time, tick
   seed state, go sent, packets in flight), so playing it on is the run itself. Also the captured
   world's file (its chunks and columns are not copied: the keys the bot held are, to reattach
   them, moment-of), the seed, the target, the code, the signature and :snapshot/moment, when
   and why and the window it is judged over. The facts are d/serializable, since a DataScript
   value does not print as EDN. Everything else prints as the recordings print (byte arrays as
   #clojurecraft/bytes)."
  [w seed until commit signature {:keys [world sim moment] :as m}]
  {:snapshot/file (:capture/file w)
   :snapshot/seed seed
   :snapshot/until until
   :snapshot/commit commit
   :snapshot/signature signature
   :snapshot/moment moment
   :snapshot/next (select-keys m [:pending :t :rand :went? :inflight])
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

(defn moment-of
  "The moment a snapshot holds, ready to play on (sim/play-from): the captured world's chunks the
   bot held reattached (decoded once, prepared), the model's columns too, the facts a DataScript
   value again, and what the next tick depends on. w is the captured world the snapshot names,
   prepared. A snapshot from before :snapshot/next plays on from its world's time with nothing
   pending and the tick seed restarted; a plan with no landing (a snapshot written before run
   named one, issue #37) gets the captured world's."
  [{:snapshot/keys [chunks world sim next]} w]
  (merge {:pending [] :t (:time/now world) :rand (:sim/tick-seed sim) :went? true :inflight []}
         next
         {:world (-> world
                     (assoc :world/chunks (select-keys (:capture/decoded w) chunks))
                     (update :world/facts d/from-serializable)
                     (cond-> (nil? (:plan/go-at world)) (assoc :plan/go-at (spawn-of w))))
          :sim (assoc sim :sim/columns (:capture/columns w) :sim/decoded (:capture/decoded w))}))

(defn moment
  "The world and model a snapshot holds, [world sim] (moment-of)."
  [snap w]
  ((juxt :world :sim) (moment-of snap w)))

(defn stage-of
  "The stage a snapshot's signature names: the target its run had not reached."
  [snap]
  (first (:snapshot/signature snap)))

(defn window-of
  "The simulated ms a snapshot is judged over: its moment's window, or snapshot-budget-ms for a
   snapshot from before moments were kept."
  [snap]
  (get-in snap [:snapshot/moment :window-ms] snapshot-budget-ms))

(defn story
  "What the bot did from a moment on, from the facts of the world it ended in: each intention
   fact at or after t, [ms event kind target reason], oldest first."
  [world t]
  (for [f (memory/intentions world)
        :when (>= (:intention/at f 0) t)]
    [(:intention/at f) (:intention/event f) (:intention/kind f) (:intention/target f)
     (:intention/reason f)]))

(defn judged
  "A snapshot played on from its moment over its window (window-of) and never longer, never from
   the landing (docs/RICH-HICKEY.md: write the validation, run it against past results, never
   re-run the system from spawn): until the plan is done or failed, or reaches the stage the
   signature names, or the window ends. A moment from before the bot loaded still gets its :go
   (go-of), as the run did. halt? (no arguments, default never) ends the play early too: the
   caller's wall-clock budget, so a judge cannot outlast its job; a play it ended is :run/halted?.
   The RESULT, marked (failure/mark), with :run/ms the time it ended at, :run/from the moment's
   time and :run/story what the bot did between (story)."
  [snap w & [halt?]]
  (let [{:keys [world] :as m} (moment-of snap w)
        stage (stage-of snap)
        until (:snapshot/until snap)
        halt? (or halt? (constantly false))
        halted (volatile! false)
        stop? #(or (plan/done? %)
                   (plan/failed? %)
                   (contains? (:plan/reached %) stage)
                   (vreset! halted (boolean (halt?))))
        {world' :world crashed :crashed}
        (sim/play-from step m stop? (window-of snap) (go-of w until) nil)]
    (assoc (failure/mark
            (cond-> (main/result world' until (and (nil? crashed) (main/ok? world' until)))
              crashed (assoc :reason :crashed :error (str crashed))))
           :run/ms (:time/now world')
           :run/from (:time/now world)
           :run/halted? @halted
           :run/story (vec (story world' (:time/now world))))))

(defn timeout-signature?
  "Is a snapshot's signature a :timeout: the run used up its simulated time, a stall?"
  [snap]
  (= :timeout (second (:snapshot/signature snap))))

(defn recurs?
  "Does a snapshot's failure come back when it is judged (judged)? A stall recurs when the window
   ends with its stage still not reached and the plan not done; any other failure when the plan
   fails again with the same signature. A plan still running at the end of a failure's window,
   or failed otherwise, got past this moment. A play the wall-clock budget halted did not get
   past it in that time: it recurs (the base code that is too slow to pass its own moment fails
   it, and so does a fix that is)."
  [snap played]
  (cond (:run/halted? played) true
        (timeout-signature? snap)
        (not (or (:ok played)
                 (= :done (get-in played [:plan :plan/status]))
                 (contains? (set (get-in played [:plan :plan/reached])) (stage-of snap))))
        :else (= (:snapshot/signature snap) (:run/signature played))))

(defn stage-until
  "The --until name of a signature's stage: the live target row whose :goal/id it is (:kit is
   \"table\"); nil when no live target has that id."
  [stage]
  (some #(when (= stage (:goal/id %)) (:goal/until %)) plan/targets))

(defn replay
  "A snapshot's run again from its landing (run): the same captured world w and seed, to the
   stage its signature stopped on (stage-until; the snapshot's own target when that names none),
   at most max-ms of simulated time. For a probe that wants the whole road to a failure; never
   the issue check, which judges the moment alone (judged). The RESULT, marked, without its
   moments."
  [snap w max-ms]
  (let [until (or (stage-until (first (:snapshot/signature snap))) (:snapshot/until snap))]
    (dissoc (failure/mark (run w (:snapshot/seed snap) until max-ms))
     :run/end
     :run/last
     :run/moments)))

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
   :run/commit) and, when it failed, what bb failures reads (failure/mark) and a moment from
   before the choice that failed kept as a snapshot beside out (:run/snapshot, chosen-moment,
   issue #77); returns it."
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
        sig (:run/signature r)
        m (when (and sig (:run/last r))
            (chosen-moment (:run/moments r) (:run/last r) (decision (:run/end r) sig) (:run/ms r)))
        path (when m
               (let [p
                     (snapshot-name (io/file (.getParent (io/file (str out))) "snapshots") w seed)]
                 (capture/write-world! p (snapshot w seed until commit sig m))
                 p))
        r (cond-> (dissoc r :run/end :run/last :run/moments)
            path (assoc :run/snapshot path :run/moment (:moment m)))]
    (locking out
      (println "SIMRUN" (pr-str (dissoc r :plan :stats)))
      (spit out (str (pr-str r) "\n") :append true)
      (flush))
    r))

(def judge-wall-ms 240000)            ; a judge's wall-clock budget a snapshot: four minutes, inside issue.yml's 15

(defn judge!
  "Judge the snapshot at path as the issue check does (judged, halted after judge-wall-ms of wall
   time: a snapshot whose code plays too slowly to pass the moment recurs, never outlasts the
   check; PR #197's base planner took 862 s on one), its captured world read and prepared: print
   one JUDGE line (the signature, why and when its moment was kept, the window,
   whether it recurs, how it ended, simulated and wall ms) and the STORY of what the bot did over
   the window, one line an intention fact. Returns {:path :recurs? :played}."
  [path]
  (let [t0 (System/currentTimeMillis)
        snap (capture/read-world path)
        w (assoc (prepared (capture/read-world (:snapshot/file snap)))
                 :capture/file
                 (:snapshot/file snap))
        r (judged snap w #(> (- (System/currentTimeMillis) t0) judge-wall-ms))
        recurs (recurs? snap r)]
    (println "JUDGE"
             (pr-str {:path path
                      :signature (:snapshot/signature snap)
                      :moment (:snapshot/moment snap)
                      :window-ms (window-of snap)
                      :recurs? recurs
                      :halted? (:run/halted? r)
                      :run/signature (:run/signature r)
                      :plan/status (get-in r [:plan :plan/status])
                      :sim-ms (- (:run/ms r) (:run/from r))
                      :wall-ms (- (System/currentTimeMillis) t0)}))
    (doseq [line (:run/story r)] (println "STORY" path (pr-str line)))
    {:path path :recurs? recurs :played r}))

(defn from-landing!
  "A probe's look at the whole road to a snapshot's failure: its run again from the landing to the
   stage its signature names (replay), never the check's judgement. Prints one REPLAY line."
  [path]
  (let [snap (capture/read-world path)
        w (assoc (prepared (capture/read-world (:snapshot/file snap)))
                 :capture/file
                 (:snapshot/file snap))
        r (replay snap w (* 60000 default-max-minutes))]
    (println "REPLAY"
             (pr-str {:path path
                      :signature (:snapshot/signature snap)
                      :run/signature (:run/signature r)
                      :plan/reached-after (get-in r [:plan :plan/reached-after])
                      :run/ms (:run/ms r)}))))

(defn race!
  "The sim race -main runs for its options o (cli/options), every option but --judge."
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
   runs an EDN file lists, [[world-file seed] ...] (an issue's reproductions). Prints a SIMRUN
   line per run and the SIMRACE tally; keeps every run in <out>/runs.edn and each failed run's
   snapshot in <out>/snapshots.
   --judge f1,f2 judges snapshots as the issue check does (judge!), uninstrumented: a judge, not
   a spec test. With --expect recur, it exits 1 unless every one recurs (the base code: the
   moment must reproduce the failure); with --expect pass, unless none does (the fix).
   --from-landing f replays a snapshot's run from its landing to its stage (from-landing!): a
   probe's tool for the whole road, up to 90 simulated minutes, never the check's."
  [& args]
  (let [o (cli/options args)]
    (cond (get o :judge)
          (let [results (mapv judge! (str/split (get o :judge) #","))
                expect (get o :expect)
                bad (case expect
                      "recur" (remove :recurs? results)
                      "pass" (filter :recurs? results)
                      [])]
            (doseq [{:keys [path]} bad] (println "UNEXPECTED" path "expected" expect))
            (shutdown-agents)
            (System/exit (if (seq bad) 1 0)))
          (get o :from-landing) (do (from-landing! (get o :from-landing)) (shutdown-agents))
          :else (race! o))))
