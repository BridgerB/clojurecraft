(ns clojurecraft.simrace
  "The sim race: the whole bot, from empty hands at a landing to the race's target, against the
   server model (sim.clj) in captured real worlds (capture.clj), as many runs as a machine's
   cores can play, with no Java server.

     clojure -M:simrace --worlds cache/sim-worlds/R1 [--seeds 8] [--until nether]
       [--max-minutes 330] [--threads n] [--out data/simrace]

   A run is a pure function of three inputs: the world file, the seed (the model's loot rolls
   and the bot's tick randomness, sim/tick-rand) and the code. So a run is never saved to be
   kept: its world, seed and commit are the reproduction (--world f --seed s replays one), and
   it is the same run on any machine. Each run ends in a RESULT the way a real run does
   (main/result), printed as a SIMRUN line and kept in <out>/runs.edn; a failure's signature
   (failure/signature: the target it was on and why it stopped) is what groups failures into one
   issue however many runs hit it.

   A world is read once and its columns decoded once (prepared); every run of it shares that
   value. The model holds the whole world: nothing loads or unloads. A portal leads to the set's
   captured Nether (capture_nether.clj, cache/sim-nether/<set>.edn.gz beside the set's worlds),
   read and decoded once for every world of the set, streamed round the player as a server's
   view (issue #353); a set with none captured keeps the overworld's columns past the portal."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojurecraft.capture :as capture]
            [clojurecraft.chunk :as chunk]
            [clojurecraft.cli :as cli]
            [clojurecraft.dimension :as dimension]
            [clojurecraft.failure :as failure]
            [clojurecraft.game :as game]
            [clojurecraft.main :as main]
            [clojurecraft.memory :as memory]
            [clojurecraft.plan :as plan]
            [clojurecraft.scene :as scene]
            [clojurecraft.sim :as sim]
            [datascript.core :as d]))

(set! *warn-on-reflection* true)

(def default-seeds 8)                 ; runs of each world
(def default-until "nether")          ; the target a run plays every step up to
(def default-max-minutes 330)         ; simulated minutes a run may take: a real bot's horizon, a lane's job (#252)
(def difficulty :normal)              ; the model's difficulty, as every real server's since #257: hunger and mobs (issue #260)
(def day-step 3000)                   ; game ticks between the times of day a run may start at, by its seed
(def nether-view 6)                   ; chunks a race server shows round a player (view-distance, bb/clojurecraft/server.clj)

(defn day-time-of
  "The time of day a run of seed starts at: one of eight, three hours of the game's day apart, so a
   world's seeds land some at night as a real bot joins a running server at any hour."
  [seed]
  (* day-step (mod (long seed) (quot 24000 day-step))))

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

(defn nether-file
  "The captured Nether of the set a world file belongs to, by the caches' layout: world
   cache/sim-worlds/R1/7.edn.gz is set R1's, whose Nether capture-nether wrote to
   cache/sim-nether/R1.edn.gz."
  [world-file]
  (let [set-dir (.getParentFile (io/file (str world-file)))
        cache (some-> set-dir
                      .getParentFile
                      .getParentFile)]
    (str (io/file cache
                  "sim-nether"
                  (str (some-> set-dir
                               .getName)
                       ".edn.gz")))))

(defn sim-nether
  "A captured Nether (capture-nether's file's value) as the model's :sim/nether: its columns,
   decoded once for every run of its set to share, streamed nether-view chunks round the player
   (a captured overworld goes to the client whole; a set's whole Nether at once would show the
   bot fortresses no server showed it), and no :at, so a run arrives where the server would link
   its portal (sim/arrival)."
  [n]
  {:columns (:nether/columns n)
   :decoded (into {} (map (fn [[k bytes]] [k (chunk/decode bytes)])) (:nether/columns n))
   :view-distance nether-view})

(defn with-nether
  "World w with its set's Nether (sim-nether's value) as every run's portal destination, and the
   file it came from, which a snapshot names to read it again."
  [w nether file]
  (assoc w :capture/nether nether :capture/nether-file (str file)))

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
   :run/violations (what a real server would have punished), :run/respawns and :run/eaten (the
   lives the model gave after deaths and the foods it let the player eat), :run/backoff-ms (how
   long the intent was :back-off), :run/death-causes (the cause of each death: a mob's kind,
   :starve, :drown, :scheduled or :ender_dragon), :run/end (the world it stopped
   in), :run/last (the moment of the
   stopping tick) and :run/moments (the moments sim/play-from kept, every half minute, the
   last six minutes), from which a failure's snapshot is chosen (dropped from the lines written).
   The two counters do not change a decision. Its
   :go names the landing (:go/at), as race/land-bot! does for a real bot: the plan's
   landing-relative choices (the climb home from deep, a climb's level steps toward the landing,
   how far they may go) are the real race's. Without it, sim race 38659291345 world R4/38 (issue
   #37) climbed under a lake with 32 level steps where a real bot has 32 and the way home, and
   was :boxed five columns short of the shore. The model runs at difficulty from a time of day
   the seed picks (day-time-of), as the real servers
   do (issue #260: before it the sim lanes had no hunger and were easier than the real ones). Its
   portal leads to w's Nether (:capture/nether, with-nether) when w has one: before issue #353
   none had, and a bot through its portal tunnelled the overworld's stone for bricks. With food
   (0-20) the player's bar starts there instead of full (the lane's --food): a hungry
   start, for the hunger rows (:eat, :hunt) that a lane from a full bar seldom reaches (issue
   #260: 21 lane runs of up to 330 simulated minutes on 8f50709a ended at food 19 or 20 but one,
   as a bot that never sprints and is seldom hurt drains under a point an hour)."
  ([w seed until max-ms] (run w seed until max-ms nil))
  ([w seed until max-ms food]
   (let [opts {:host "sim" :port 0 :name "Clj_sim"}
         backoff (atom 0)
         counting (fn [before event]
                    (let [t0 (:time/now before 0)
                          after (step before event)]
                      (when (= :back-off (get-in after [:plan/intent :intent/kind]))
                        (swap! backoff + (max 0 (- (:time/now after 0) t0))))
                      after))
         {:keys [world sim last moments crashed]}
         (sim/run+ counting
                   (game/init opts)
                   (sim/init (cond-> {:columns (:capture/columns w)
                                      :decoded (:capture/decoded w)
                                      :spawn (spawn-of w)
                                      :seed seed
                                      :tick-seed seed
                                      :difficulty difficulty
                                      :day-time (day-time-of seed)}
                               (:capture/nether w) (assoc :nether (:capture/nether w))
                               food (assoc :food food)))
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
            :run/respawns (:sim/respawns sim 0)
            :run/eaten (:sim/eaten sim 0)
            :run/backoff-ms @backoff
            :run/death-causes (:sim/death-causes sim [])
            :run/end world
            :run/last last
            :run/moments moments))))

;; ---------------------------------------------------------------- a failing moment, as a value

(def snapshot-budget-ms 120000)       ; simulated ms an old snapshot (no :snapshot/moment) is played on for
(def decision-lead-ms 30000)          ; a snapshot's moment comes at least this long before the choice that failed
(def stall-window-ms 300000)          ; a :timeout is a stall: five simulated minutes with no new stage, where its moment is kept
(def stall-judge-ms 900000)           ; and the window a stall is judged over: fifteen simulated minutes (issue #62)
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
            :window-ms (cond (= :stall why) stall-judge-ms
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
   #clojurecraft/bytes). A world with a captured Nether (with-nether) is named by its file too
   (:snapshot/nether-file), the model's Nether kept without its columns, as the world is."
  [w seed until commit signature {:keys [world sim moment] :as m}]
  (cond-> {:snapshot/file (:capture/file w)
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
           :snapshot/sim (cond-> (dissoc sim :sim/columns :sim/decoded)
                           (:capture/nether-file w) (update :sim/nether dissoc :columns :decoded)
                           (:sim/overworld sim)
                           (update :sim/overworld dissoc :sim/columns :sim/decoded))}
    (:capture/nether-file w) (assoc :snapshot/nether-file (:capture/nether-file w))))

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

(defn stranded?
  "Is a snapshot's player in the overworld holding none of its columns after a death: a respawn
   from the Nether written before issue #504, when the model left the Nether's columns over the
   overworld's, the bot's chunks none and the view streamed from them? Its view is streamed
   again. A bot that never died (a moment before it has loaded) is not."
  [chunks world sim]
  (boolean (and (empty? chunks)
                (pos? (:stats/deaths world 0))
                (not= dimension/nether (:sim/dimension sim)))))

(defn moment-of
  "The moment a snapshot holds, ready to play on (sim/play-from): the captured world's chunks the
   bot held reattached (decoded once, prepared), the model's columns too, the facts a DataScript
   value again, and what the next tick depends on. w is the captured world the snapshot names,
   prepared. A snapshot from before :snapshot/next plays on from its world's time with nothing
   pending and the tick seed restarted; a plan with no landing (a snapshot written before run
   named one, issue #37) gets the captured world's. With w's captured Nether (:capture/nether),
   the model's Nether gets its columns back, and a moment past the portal holds the Nether's
   columns, the bot's chunks among them, where one before it holds the overworld's. The overworld
   set aside while the player is in another dimension (:sim/overworld, sim/leave-overworld) gets
   w's columns back."
  [{:snapshot/keys [chunks world sim next]} w]
  (let [nether (:capture/nether w)
        here (if (and nether (= dimension/nether (:sim/dimension sim)))
               nether
               {:columns (:capture/columns w) :decoded (:capture/decoded w)})]
    (merge {:pending [] :t (:time/now world) :rand (:sim/tick-seed sim) :went? true :inflight []}
           next
           {:world (-> world
                       (assoc :world/chunks (select-keys (:decoded here) chunks))
                       (update :world/facts d/from-serializable)
                       (cond-> (nil? (:plan/go-at world)) (assoc :plan/go-at (spawn-of w))))
            :sim (cond-> (assoc sim :sim/columns (:columns here) :sim/decoded (:decoded here))
                   (and nether (:sim/nether sim)) (update :sim/nether merge nether)
                   (:sim/overworld sim)
                   (update :sim/overworld assoc
                           :sim/columns (:capture/columns w)
                           :sim/decoded (:capture/decoded w))
                   (stranded? chunks world sim)
                   (-> (assoc :sim/view #{})
                       (dissoc :sim/view-at)
                       sim/stream))})))

(defn own-world
  "The world a self-contained snapshot is played in, prepared: the columns it carries
   (:snapshot/columns, a real failure's, issue #299: what the real server had sent the bot, with
   no captured world file to name) and its landing; nil for a snapshot that names a captured
   world's file instead (a sim run's), which the caller reads."
  [snap]
  (when-let [columns (:snapshot/columns snap)]
    (prepared
     {:capture/set "real" :capture/landing (:snapshot/landing snap) :capture/columns columns})))

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
   snapshot from before moments were kept; a stall's at least stall-judge-ms. A stall is kept
   from its run's last five minutes with no new stage, but a stage can take longer than that by
   rights (iron mined and smelted): issue #62's two stall snapshots, the bot no longer dying on
   one stair, smelted their ingots 8 and 11 minutes on, and a five-minute judge called both
   recurring. Kept snapshots carry five minutes; they are judged over fifteen."
  [snap]
  (let [w (get-in snap [:snapshot/moment :window-ms] snapshot-budget-ms)]
    (if (= :stall (get-in snap [:snapshot/moment :why])) (max w stall-judge-ms) w)))

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
   time, :run/story what the bot did between (story), and :run/scenes the bot's world at the
   moment and where the play ended ({:start {:facts :slice} :end ...}, scene/facts and
   scene/slice)."
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
           :run/story (vec (story world' (:time/now world)))
           :run/scenes
           {:start {:facts (scene/facts world) :slice (scene/slice world)}
            :end {:facts (scene/facts world') :slice (scene/slice world')}})))

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
  "The runs as a value: how many, how many reached the target, the lives given after deaths and
   the foods eaten over them all (issue #260: the lanes show deaths and eating), and the failures
   counted by
   signature, most first."
  [runs]
  {:simrace/runs (count runs)
   :simrace/ok (count (filter :ok runs))
   :simrace/respawns (reduce + (keep :run/respawns runs))
   :simrace/eaten (reduce + (keep :run/eaten runs))
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
   issue #77). Also prints a GATE line: deaths by cause, minutes in :back-off, and minutes
   to the kit, the stone pickaxe, the iron kit and the portal kit. Returns the run."
  [w seed until max-ms commit out food]
  (let [r (try (run w seed until max-ms food)
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
      (let [reached (get-in r [:plan :plan/reached-after])]
        (println "GATE"
                 (pr-str {:world (:run/world r)
                          :seed (:run/seed r)
                          :ok (:ok r)
                          :reason (:reason r)
                          :deaths (frequencies (:run/death-causes r []))
                          :deaths-n (get-in r [:stats :stats/deaths])
                          :backoff-min (/ (:run/backoff-ms r 0) 60000.0)
                          :ms (:run/ms r)
                          :reached (into {}
                                         (keep (fn [id]
                                                 (when-let [ms (get reached id)]
                                                   [id (/ ms 60000.0)])))
                                         [:kit :stone-pickaxe :iron-kit :portal-kit])})))
      (println "SIMRUN" (pr-str (dissoc r :plan :stats)))
      (spit out (str (pr-str r) "\n") :append true)
      (flush))
    r))

(defn read-nether
  "The captured Nether at path as a world's (sim-nether), read and decoded."
  [path]
  (sim-nether (capture/read-world path)))

(defn world-for!
  "The world a snapshot is played in, prepared: its own columns (own-world, a real failure's),
   else the captured world file it names, read, with the captured Nether it names
   (:snapshot/nether-file), which must be there: judged without it, a portal would lead into the
   overworld's columns."
  [snap]
  (or (own-world snap)
      (cond-> (assoc (prepared (capture/read-world (:snapshot/file snap)))
                     :capture/file
                     (:snapshot/file snap))
        (:snapshot/nether-file snap)
        (with-nether (read-nether (:snapshot/nether-file snap)) (:snapshot/nether-file snap)))))

(def judge-wall-ms 240000)            ; a judge's wall-clock budget a snapshot: four minutes, inside issue.yml's 15
(def slow-ms (quot judge-wall-ms 2))  ; a judge slower than this is no evidence: on a slower runner it would halt (issue #299)

(defn judge!
  "Judge the snapshot at path as the issue check does (judged, halted after judge-wall-ms of wall
   time: a snapshot whose code plays too slowly to pass the moment recurs, never outlasts the
   check; PR #197's base planner took 862 s on one), its captured world read and prepared: print
   one JUDGE line (the signature, why and when its moment was kept, the window, whether it
   recurs, how it ended, simulated and wall ms, and :slow? past slow-ms: a verdict that took half
   the budget could halt on a slower runner, so the churn files no such snapshot) and the STORY of
   what the bot did over the window, one line an intention fact, then a SCENE line and SLICE lines
   for the moment (start) and where the play ended (end): the bot's facts and the blocks round its
   feet. Returns {:path :recurs? :played}."
  [path]
  (let [t0 (System/currentTimeMillis)
        snap (capture/read-world path)
        w (world-for! snap)
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
                      :wall-ms (- (System/currentTimeMillis) t0)
                      :slow? (> (- (System/currentTimeMillis) t0) slow-ms)}))
    (doseq [line (:run/story r)] (println "STORY" path (pr-str line)))
    (doseq [[at {:keys [facts slice]}] (:run/scenes r)]
      (println "SCENE" path (name at) (pr-str facts))
      (doseq [line slice] (println "SLICE" path (name at) line)))
    {:path path :recurs? recurs :played r}))

(defn from-landing!
  "A probe's look at the whole road to a snapshot's failure: its run again from the landing to the
   stage its signature names (replay), never the check's judgement. Prints one REPLAY line."
  [path]
  (let [snap (capture/read-world path)
        w (world-for! snap)
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
        food (some-> (get o :food)
                     Long/parseLong)
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
        ;; one Nether a set, read once, shared by every world of it
        nether-of (memoize (fn [path]
                             (cond (.exists (io/file path))
                                   (do (cli/log "nether" path) (read-nether path))
                                   :else (cli/log "no captured Nether" path))))
        ws (mapv (fn [f]
                   (cli/log "world" (str f))
                   (let [w (assoc (prepared (capture/read-world f)) :capture/file (str f))
                         path (nether-file f)
                         nether (nether-of path)]
                     (cond-> w nether (with-nether nether path))))
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
                       (recur (conj done (play! (ws i) s until max-ms commit runs-file food)))
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
   runs an EDN file lists, [[world-file seed] ...] (an issue's reproductions). --food n starts
   every run's bar at n instead of full (run), a hungry start for the hunger rows. Prints a SIMRUN
   line per run and the SIMRACE tally; keeps every run in <out>/runs.edn and each failed run's
   snapshot in <out>/snapshots. A portal leads to the set's captured Nether when there is one
   (nether-file: cache/sim-nether/<set>.edn.gz beside cache/sim-worlds/<set>).
   --judge f1,f2 judges snapshots as the issue check does (judge!), uninstrumented: a judge, not
   a spec test. With --expect recur, it exits 1 unless every one recurs (the base code: the
   moment must reproduce the failure); with --expect pass, unless none does (the fix).
   --from-landing f replays a snapshot's run from its landing to its stage (from-landing!): a
   probe's tool for the whole road, up to default-max-minutes, never the check's.
   --from-recording f1,f2 turns real races' recordings into snapshots of their failures
   (real/from-recordings!: --signature, --out, --name n1,n2, --seed, --round), each kept only when
   it recurs."
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
          ;; real.clj builds on this namespace (decision, chosen-moment, judged), so its entry
          ;; is resolved here rather than required: one command line, no cycle
          (get o :from-recording)
          (do ((requiring-resolve 'clojurecraft.real/from-recordings!) o) (shutdown-agents))
          :else (race! o))))
