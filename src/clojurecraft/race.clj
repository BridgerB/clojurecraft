(ns clojurecraft.race
  "A race (issue #17): many bots on one server, from where each lands to the race's target, the
   server always full (issue #127): :race/bots slots, each starting a fresh bot the moment its bot
   ends, for the job's length (run-ms), so no bot waits for another and a slow one plays as long
   as it likes; a bot the job's end stops is cut (failure/cut), never a failure.

     clojure -M:race --config ci/race.edn --port 25565 --rcon-port 25575 --rcon-pass S \\
       [--record-dir data/race] [--commit sha] [--seed s] [--runner n] [--server k]
       [--landings cache/worlds/R1/landings.edn --landing-offset n]

   --server names which of a runner's servers this race is on, when a runner carries more than
   one (:race/servers): each server is its own world, seed and race process, and its facts say
   which (:milestone/server, :sample/server). A runner stays the machine.

   --landings races on a landing set's pregenerated world (ci/gym.edn, world.yml): bot i lands on
   the set's landing offset + i, wrapping (landing-of), and nothing is searched or claimed. Every
   commit then races the same worlds from the same landings, so two commits compare landing by
   landing, and the server neither generates a fresh world round each bot nor runs a locate
   biome per bot in one of its ticks. Without it, the forest search and the claims below.

   docs/hickey.md, Conveyance: each bot is its own atom and loop (main/run-bot!, the same life a
   lone bot lives), and bots share nothing but one bounded channel, the claims of landing cells,
   folded by the pure claims reducer. The fixture lands each bot on the cell it claimed for it,
   over RCON, and speaks to that bot only through the bot's own bounded event queue (its :go).
   The board is an observer: once a minute it takes each bot's world as it is and prints a line;
   it never touches a loop. Every bot's run is its own recording and replays alone. The race's
   settings are data (ci/race.edn). Milestones are facts: an observer on each bot's atom turns
   each target newly reached into one {:milestone/bot :milestone/goal :milestone/at ...} line
   (MILESTONE on stdout, milestones.edn), which the churn's reports read (bb churn)."
  (:require [clojure.core.async :as async]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojurecraft.claims :as claims]
            [clojurecraft.cli :as cli]
            [clojurecraft.dimension :as dimension]
            [clojurecraft.failure :as failure]
            [clojurecraft.fixture :as fixture]
            [clojurecraft.game :as game]
            [clojurecraft.landings :as landings]
            [clojurecraft.main :as main]
            [clojurecraft.plan :as plan]
            [clojurecraft.rcon :as rcon]))

(set! *warn-on-reflection* true)

(def board-every-ms 60000)            ; how often the board prints every bot's line
(def claim-tries 12)                  ; searches a landing makes before the bot starts where it stands
(def search-stride 3)                 ; cells between the points a landing searches from, so searches find different forests
(def forests "#minecraft:is_forest")  ; every forest kind: nearer than plain forest, and so a shorter search
(def claims-buffer 16)                ; requests the claims loop holds before a claimant waits
(def events-buffer 16)                ; events a bot's queue holds before the fixture waits
(def sample-every-ms 15000)            ; how often the sampler records the race as one fact
(def milestones-buffer 256)           ; milestone facts waiting for the writer; a bot reaches at most a chain's worth
(def min-play-ms 300000)              ; a slot starts no bot with less of the job left than this: it would only be cut
(def quick-ms 30000)                  ; a bot that crashed or was cut off sooner than this never played: the server is gone or failing
(def backoff-max-ms 60000)            ; the longest a slot waits before its next bot after quick ends

(defn bot-name
  "The name of racer k (from 1) with prefix: a server name, sixteen characters at most."
  [prefix k]
  (format "%s%02d" prefix k))

(defn standing
  "Where one bot is in the race, read from its world value: the target it works now, how many of
   the chain's targets it has reached (a chained run's milestones), its dimension, its height,
   its deaths and the plan's status."
  [world]
  {:race/target (some-> (and (:plan/status world) (plan/choose world plan/goals))
                        :goal/id)
   :race/reached (count (:plan/reached world))
   :race/dimension (some-> (dimension/of world)
                           (str/replace "minecraft:" ""))
   :race/y (some-> (:player/pos world)
                   second
                   long)
   :race/deaths (:stats/deaths world 0)
   :race/status (:plan/status world)})

(defn board-line
  "One bot's line on the board."
  [name world]
  (let [{:race/keys [target reached dimension y deaths status]} (standing world)]
    (str/join " "
              [name (or status :waiting) (str "at " (or target "-")) (str "reached " reached)
               dimension (str "y " (or y "?")) (str "deaths " deaths)])))

(defn start-at
  "When racer i (from 0) joins, ms after the race begins. With a ramp ({:ramp/start s :ramp/step n
   :ramp/every-ms e}), the first s join at once and n more every e, so one runner measures the
   load from s bots up; without, every racer joins at once. Within a batch, stagger-ms apart."
  [{:race/keys [ramp stagger-ms]} i]
  (let [{:ramp/keys [start step every-ms]} ramp]
    (cond (or (nil? ramp) (< i start)) (* i stagger-ms)
          :else
          (+ (* every-ms (inc (quot (- i start) step))) (* stagger-ms (rem (- i start) step))))))

(defn backoff-ms
  "How long a slot waits before its next bot: none after a bot that played; after one that
   crashed or was cut off (failure/infra-reasons) within quick-ms, double the last wait (1 s the
   first time), at most backoff-max-ms. A server that stopped or fails would otherwise have every
   slot start bot after bot at once, each with its threads, until the machine can fork no more
   (issue #145)."
  [wait life-ms {:keys [reason]}]
  (cond (and (< life-ms quick-ms) (or (= :crashed reason) (contains? failure/infra-reasons reason)))
        (min backoff-max-ms (max 1000 (* 2 wait)))
        :else 0))

(defn run-ms
  "How long a race plays, ms: :race/deadline-ms (the capacity ramp's one deadline), else
   :race/run-minutes (the churn's job, up to GitHub's cap), else :race/timeout-ms."
  [{:race/keys [deadline-ms run-minutes timeout-ms]}]
  (or deadline-ms
      (some-> run-minutes
              (* 60000))
      timeout-ms))

(defn marked
  "A RESULT as the race prints it: marked for bb failures (failure/mark), and cut (failure/cut)
   when the job's end stopped the bot (a :timeout, the only timeout a churn bot has)."
  [r]
  (cond-> (failure/mark r) (= :timeout (:reason r)) failure/cut))

(defn tick-times
  "The server's tick times in ms, {:avg :p50 :p95 :p99}, from its tick query reply; {} when the
   reply has none."
  [reply]
  (let [ms (fn [re]
             (some->> (re-find re (str reply))
                      second
                      parse-double))]
    (into {}
          (remove (comp nil? val))
          {:avg (ms #"Average time per tick: ([\d.]+)ms")
           :p50 (ms #"P50: ([\d.]+)ms")
           :p95 (ms #"P95: ([\d.]+)ms")
           :p99 (ms #"P99: ([\d.]+)ms")})))

(defn quantile
  "The q quantile (0 to 1) of numbers xs, nearest rank; nil when there are none."
  [q xs]
  (let [v (vec (sort xs))] (when (seq v) (nth v (min (dec (count v)) (long (* q (count v))))))))

(defn sample
  "The race at one moment as a fact, from every bot's world now ({name world}) and the tick each
   was at dt-ms before ({name tick}): how many bots have joined, landed, are playing (in the world
   and still racing: a bot that ended stops ticking), ended or were cut off; each playing bot's ticks per second (a bot whose machine is short of CPU falls below
   20 first), as its median, tenth percentile and least; milestones reached in all; the server's
   tick times and the bots' JVM heap."
  [race worlds before dt-ms tick heap-mb now]
  (let [playing (filter (fn [[_ w]]
                          (and (= :play (:bot/phase w))
                               (:player/loaded? w)
                               (not (:bot/closed w))
                               (not (:bot/disconnected w))
                               (not (#{:done :failed} (:plan/status w)))))
                        worlds)
        rates (keep (fn [[name w]]
                      (when-let [t0 (get before name)]
                        (/ (* 1000.0 (- (:time/tick w 0) t0)) (max 1 dt-ms))))
                    playing)]
    {:sample/runner (:runner race)
     :sample/server (:server race)
     :sample/at now
     :sample/joined (count (filter (comp :player/pos val) worlds))
     :sample/landed (count (filter (comp :plan/go-at val) worlds))
     :sample/playing (count playing)
     :sample/ended (count (filter (comp #{:done :failed} :plan/status val) worlds))
     :sample/cut-off (count (filter (fn [[_ w]] (or (:bot/closed w) (:bot/disconnected w))) worlds))
     :sample/tick-rate
     {:median (quantile 0.5 rates) :p10 (quantile 0.1 rates) :min (quantile 0 rates)}
     :sample/milestones (reduce + (map (comp count :plan/reached val) worlds))
     :sample/server-tick tick
     :sample/bot-heap-mb heap-mb}))

(defn reached-new
  "The targets world reached that before did not: what a chained run's step just added to
   :plan/reached, in no particular order."
  [before world]
  (remove (set (:plan/reached before)) (:plan/reached world)))

(defn milestone
  "The fact that bot name reached goal in world: on which runner (and which of its servers, when
   it carries more than one) and seed, when (:time/now, ms), and how long after its plan began
   (its landing)."
  [{:keys [runner server seed]} name world goal]
  (cond-> {:milestone/runner runner
           :milestone/seed seed
           :milestone/bot name
           :milestone/goal goal
           :milestone/at (:time/now world)}
    server (assoc :milestone/server server)
    (:plan/since world)
    (assoc :milestone/after-s (quot (- (:time/now world) (:plan/since world)) 1000))))

(defn summary
  "The race as a value, from each bot's {:name :result :truth}: how many bots, how many reached
   the target (RESULT :ok), why the others stopped, and every bot's reason and milestones."
  [results]
  {:race/bots (count results)
   :race/ok (count (filter (comp :ok :result) results))
   :race/reasons (frequencies (map (comp :reason :result) results))
   :race/runs (mapv (fn [{:keys [name result truth]}]
                      {:name name
                       :ok (:ok result)
                       :reason (:reason result)
                       :reached (:plan/reached (:plan result))
                       :truth truth})
                    results)})

;;;; I/O: the claims loop, the fixture, the board, the bots ;;;;

(defn claims!
  "Start the claims loop: one bounded channel of [request reply-channel], folded by claims/claim
   in arrival order. Returns the channel."
  []
  (let [ch (async/chan claims-buffer)]
    (async/go-loop [held {}]
      (when-let [[request reply] (async/<! ch)]
        (let [[held answer] (claims/claim held request)]
          (async/>! reply answer)
          (recur held))))
    ch))

(defn claim!
  "Ask the claims loop for a cell; blocks for the answer."
  [ch request]
  (let [reply (async/chan 1)]
    (async/>!! ch [request reply])
    (async/<!! reply)))

(defn land!
  "Land the bot named name on a cell of its own: down the spiral from centre, search-stride cells
   apart, the forest nearest each point (any forest kind) is claimed for it by the cell it lies in;
   the first granted is where it lands (fixture/land-at!). Returns where it stands, or nil when no
   cell was granted or the landing failed. Racer offset starts that far down the spiral, so the
   fortieth bot does not search the first bots' points again. Each search is a locate biome, which the server runs
   in one tick: a race with plain forest searched 40 times per bot from points with none near,
   one search took 60 s and the watchdog killed the server."
  [rc ch name centre offset]
  (reduce (fn [_ cell]
            (let [forest (fixture/locate-biome! rc
                                                forests
                                                (claims/middle centre
                                                               (mapv #(* search-stride %) cell)))
                  mine (when forest (claims/cell-of centre forest))]
              (cond (nil? forest) nil
                    (:claim/granted? (claim! ch {:claim/by name :claim/cell mine}))
                    (do (cli/log name "claimed" mine "forest" forest)
                        (reduced (fixture/land-at! rc name forest)))
                    :else nil)))
          nil
          (take claim-tries (drop offset (claims/spiral)))))

(defn landing-of
  "The [x z] racer i (from 0) lands on in a landing set ([[x z y] ...]), offset into it: the
   set's landing offset + i, wrapping, so a runner's servers and the runners sharing a set start
   their bots on different landings, and the same racer always on the same one."
  [set offset i]
  (let [[x z _] (landings/landing-for set (inc (+ offset i)))] [x z]))

(defn set-gamerules!
  "What every race asks of its server once: keep inventories over deaths."
  [{:keys [host rcon-port rcon-pass]}]
  (try (rcon/with-rcon host rcon-port rcon-pass #(rcon/command! % "gamerule keep_inventory true"))
       (catch Exception e (cli/log "gamerule failed:" e))))

(defn land-bot!
  "Land one bot, on a thread of its own with an RCON session of its own: wait for it to be
   online, make it a survival player, land it (on landing i of the set, with landings; else on a
   cell claimed for it round the race's centre, which the first bot online decides), pin its spawn
   there, and put its :go (every step up to until, milestones on, :go/at where it landed) on its
   queue. A bot whose landing fails starts where it stands."
  [{:keys [host rcon-port rcon-pass until landings landing-offset]} ch centre name queue i]
  (async/thread
   (try (rcon/with-rcon
         host
         rcon-port
         rcon-pass
         (fn [rc]
           (let [online (fixture/wait-online! rc name fixture/online-timeout)
                 _ (when online (deliver centre [(long (first online)) (long (nth online 2))]))
                 at (when online
                      (rcon/command! rc (str "gamemode survival " name))
                      (cond landings
                            (fixture/land-at! rc name (landing-of landings (or landing-offset 0) i))
                            :else (land! rc ch name @centre i)))]
             (when at
               (rcon/command! rc
                              (str "spawnpoint " name
                                   " " (long (first at))
                                   " " (long (second at))
                                   " " (long (nth at 2)))))
             (cli/log name "landed at" at)
             (async/>!! queue
                        (cond-> {:event/kind :go :go/goals (plan/through until) :go/chain? true}
                          at (assoc :go/at at))))))
        (catch Exception e (cli/log name "landing failed:" e)))))

(defn board!
  "The board, on a thread of its own: every board-every-ms until done (a promise) is delivered,
   one BOARD line per bot playing now (live, an atom of {name world-atom}) and the server's tick
   time, the measure of how many bots a server can carry."
  [{:keys [host rcon-port rcon-pass]} live done]
  (async/thread
   (while (= :wait (deref done board-every-ms :wait))
     (let [tick (try (rcon/with-rcon host rcon-port rcon-pass #(rcon/command! % "tick query"))
                     (catch Exception e (str "tick query failed: " (ex-message e))))]
       (println "BOARD" (str/replace (str tick) #"\s+" " ") (str "playing " (count @live)))
       (doseq [[name world*] (sort-by key @live)]
         (println "BOARD"
                  (try (board-line name @world*)
                       (catch Exception e (str name " unreadable: " (ex-message e))))))
       (flush)))))

(defn sampler!
  "The sampler, on a thread of its own: every sample-every-ms until done is delivered, the race
   as one fact (sample) from each playing bot's world as it is (live), the server's tick query and
   this JVM's heap and live threads (:sample/threads), printed as a SAMPLE line and appended to samples.edn in record-dir: the
   measure the capacity experiment sizes a runner by."
  [{:keys [host rcon-port rcon-pass]} race live record-dir done]
  (async/thread
   (with-open [w (io/writer (io/file record-dir "samples.edn") :append true)]
     (loop [before {}
            at (System/currentTimeMillis)]
       (when (= :wait (deref done sample-every-ms :wait))
         (let [now (System/currentTimeMillis)
               worlds (into {} (map (fn [[name world*]] [name @world*])) @live)
               tick (tick-times
                     (try (rcon/with-rcon host rcon-port rcon-pass #(rcon/command! % "tick query"))
                          (catch Exception _ nil)))
               heap (quot (.getUsed (.getHeapMemoryUsage
                                     (java.lang.management.ManagementFactory/getMemoryMXBean)))
                          (* 1024 1024))
               threads (.getThreadCount (java.lang.management.ManagementFactory/getThreadMXBean))
               fact
               (assoc (sample race worlds before (- now at) tick heap now) :sample/threads threads)]
           (println "SAMPLE" (pr-str fact))
           (flush)
           (.write w (str (pr-str fact) "\n"))
           (.flush w)
           (recur (into {} (map (fn [[name w]] [name (:time/tick w 0)])) worlds) now)))))))

(defn milestones!
  "The milestones observer: a writer thread prints each milestone fact as a MILESTONE line and
   appends it to milestones.edn in record-dir; facts come on a bounded channel that never blocks
   a bot's loop (a full channel drops, as telemetry does). Returns {:watch (fn [name world*])
   :unwatch (fn [world*]) :stop (fn [])}: watch puts a watch on a bot's atom that turns each
   target newly reached into a fact, unwatch takes it off when the bot ends, stop finishes the
   file."
  [race record-dir]
  (let [ch (async/chan milestones-buffer)
        writer (async/thread
                (with-open [w (io/writer (io/file record-dir "milestones.edn") :append true)]
                  (loop []
                    (when-let [fact (async/<!! ch)]
                      (println "MILESTONE" (pr-str fact))
                      (flush)
                      (.write w (str (pr-str fact) "\n"))
                      (.flush w)
                      (recur)))))]
    {:watch (fn [name world*]
              (add-watch world*
                         ::milestones
                         (fn [_ _ before world]
                           (doseq [goal (reached-new before world)]
                             (async/offer! ch (milestone race name world goal))))))
     :unwatch (fn [world*] (remove-watch world* ::milestones))
     :stop (fn [] (async/close! ch) (async/<!! writer))}))

(defn truth!
  "The server's word on where a bot is: its Dimension, asked while it is still connected."
  [{:keys [host rcon-port rcon-pass]} name]
  (try (rcon/with-rcon host
                       rcon-port
                       rcon-pass
                       #(str (rcon/command! % (str "data get entity " name " Dimension"))))
       (catch Exception e (str "truth failed: " (ex-message e)))))

(defn play-one!
  "One bot's whole life in a slot: the next serial's name (serials, an atom shared by the slots),
   its own atom and queue, on the board (live) and watched for milestones while it plays, landed on
   landing serial - 1 of the set (land-bot!), and played until its goal, its plan failing, a crash
   or the job's end (deadline): no cap of its own. Its RESULT line is printed as it ends, marked
   cut (failure/cut) when the job's end stopped it. Returns {:name :result :truth}."
  [{:keys [server ch centre serials live watcher prefix record-dir commit deadline]}]
  (let [n (swap! serials inc)
        name (bot-name prefix n)
        queue (async/chan events-buffer)
        world* (atom (game/init {:host (:host server) :port (:port server) :name name}))]
    (swap! live assoc name world*)
    ((:watch watcher) name world*)
    (land-bot! server ch centre name queue (dec n))
    (try (let [truth (promise)
               {r :result} (main/run-bot! {:host (:host server)
                                           :port (:port server)
                                           :name name
                                           :until (:until server)
                                           :chain true
                                           :timeout-ms (max 1000
                                                            (- deadline (System/currentTimeMillis)))
                                           :record (str (io/file record-dir (str name ".edn")))
                                           :commit commit
                                           :events queue
                                           :world* world*
                                           :on-result (fn [r]
                                                        (deliver truth (truth! server name))
                                                        (println "RESULT" name (pr-str (marked r))) ; what bb failures groups by
                                                        (flush))})]
           {:name name :result r :truth @truth})
         (catch Exception e
           (cli/log name "failed:" e)
           {:name name :result {:ok false :reason :crashed :error (ex-message e)}})
         (finally ((:unwatch watcher) world*) (swap! live dissoc name)))))

(defn slot!
  "One bot slot, on a thread of its own: it joins at its place in the schedule (start-at), then
   plays bot after bot (play-one!), the next the moment the last ends, or after backoff-ms when
   the last never got to play, until less than min-play-ms is left of the job. Returns a channel
   of its results."
  [{:keys [config t0 deadline] :as ctx} i]
  (async/thread
   (Thread/sleep (long (max 0 (- (+ t0 (start-at config i)) (System/currentTimeMillis)))))
   (loop [results []
          wait 0]
     (cond (< (- deadline (System/currentTimeMillis)) min-play-ms) results
           :else
           (let [_ (Thread/sleep (long wait))
                 t (System/currentTimeMillis)
                 r (play-one! ctx)]
             (recur (conj results r)
                    (backoff-ms wait (- (System/currentTimeMillis) t) (:result r))))))))

(defn -main
  "Run the race ci/race.edn (or --config) describes on the server at --port, its RCON at
   --rcon-port with --rcon-pass, for the job's length (run-ms): :race/bots slots, each refilled
   with a fresh bot the moment its bot ends, so the server always carries its bots (issue #127);
   the board, the sampler and the milestones watch whichever bots play now. A RESULT line per
   bot as it ends, a RACE summary of every bot at the end, written to the record directory.
   Exits 0 when any bot reached the target."
  [& args]
  (let [o (cli/options args)
        {:keys [config host port rcon-port rcon-pass record-dir commit seed landing-offset]
         :or {config "ci/race.edn" host "127.0.0.1" record-dir "data/race"}}
        o
        runner (get o :runner)
        which (get o :server) ; which of the runner's servers (--server); server below is the one connected to
        landings-file (get o :landings)
        config (edn/read-string (slurp config))
        {:race/keys [bots prefix until]} config
        t0 (System/currentTimeMillis)
        server (cond-> {:host host
                        :port (Long/parseLong port)
                        :rcon-port (Long/parseLong rcon-port)
                        :rcon-pass rcon-pass
                        :until until}
                 landings-file
                 (assoc :landings (landings/read-landings landings-file)
                        :landing-offset (parse-long (or landing-offset "0"))))
        _ (.mkdirs (io/file record-dir))
        where {:runner (some-> runner
                               parse-long)
               :server (some-> which
                               parse-long)}
        done (promise)
        live (atom {})
        ctx {:server server
             :ch (claims!)
             :centre (promise)
             :serials (atom 0)
             :live live
             :watcher (milestones! (assoc where :seed seed) record-dir)
             :prefix prefix
             :record-dir record-dir
             :commit commit
             :config config
             :t0 t0
             :deadline (+ t0 (run-ms config))}]
    (cli/log "race:" bots "slots to" until "seed" seed "for" (quot (run-ms config) 60000) "min")
    (set-gamerules! server)
    (board! server live done)
    (sampler! server where live record-dir done)
    (let [slots (mapv #(slot! ctx %) (range bots))
          results (into [] (mapcat async/<!!) slots)
          s (cond-> (assoc (summary results) :race/seed seed :race/commit commit :race/until until)
              (:server where) (assoc :race/server (:server where)))]
      (deliver done true)
      ((:stop (:watcher ctx)))
      (println "RACE" (pr-str (dissoc s :race/runs)))
      (spit (io/file record-dir "summary.edn") (pr-str s))
      (flush)
      (async/close! (:ch ctx))
      (shutdown-agents)
      (System/exit (if (pos? (:race/ok s)) 0 1)))))
