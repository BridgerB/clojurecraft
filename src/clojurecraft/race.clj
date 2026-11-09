(ns clojurecraft.race
  "A race: many bots on one server, from where each lands to the race's target, the server always
   full: :race/bots slots, each starting a fresh bot the moment its bot ends, for the job's length
   (run-ms), so no bot waits for another and a slow one plays as long as it likes; a bot the job's
   end stops is cut (failure/cut), never a failure.

     clojure -M:race --config ci/race.edn --port 25565 --rcon-port 25575 --rcon-pass S \\
       [--record-dir data/race] [--commit sha] [--seed s] [--runner n] [--server k]
       [--landings cache/worlds/R1/landings.edn --landing-offset n] [--start id]

   --start is where each bot starts, an id in resources/clojurecraft/starts.edn (clojurecraft.start):
   spawn, or a start in the Nether at the open pad beside its landing's fortress or in the warped
   forest nearest it, with the start's kit, playing only the stages from its goal. Without it, the
   table's default.

   --server names which of a runner's servers this race is on, when a runner carries more than
   one (:race/servers): each server is its own world, seed and race process, and its facts say
   which (:milestone/server, :sample/server). A runner stays the machine.

   --landings races on a landing set's pregenerated world (ci/gym.edn, world.yml): bot i lands on
   the set's landing offset + i, wrapping (landing-of), and nothing is searched or claimed. Every
   commit then races the same worlds from the same landings, so two commits compare landing by
   landing, and the server neither generates a fresh world round each bot nor runs a locate
   biome per bot in one of its ticks. Without it, the forest search and the claims below.

   docs/RICH-HICKEY.md, Conveyance: each bot is its own atom and loop (main/run-bot!, the same life a
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
            [clojure.math :as math]
            [clojure.string :as str]
            [clojurecraft.claims :as claims]
            [clojurecraft.cli :as cli]
            [clojurecraft.dimension :as dimension]
            [clojurecraft.fixture :as fixture]
            [clojurecraft.game :as game]
            [clojurecraft.gym :as gym]
            [clojurecraft.land :as land]
            [clojurecraft.landings :as landings]
            [clojurecraft.main :as main]
            [clojurecraft.plan :as plan]
            [clojurecraft.race-facts :as facts :refer
             [backoff-ms board-line bot-name bot-timeout-ms capped dead-after death-class health
              landing-slot marked milestone near-commands next-start reached-new recovered run-ms
              sample serverlog-lines spawn-command start-at summary tick-times]]
            [clojurecraft.rcon :as rcon]
            [clojurecraft.senses :as senses]
            [clojurecraft.servers :as servers]
            [clojurecraft.start :as start]))

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
(def probe-every-ms 30000)            ; how often a slot asks a down server whether it accepts again

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

(def team
  "The scoreboard team every racer joins: friendly fire off, so a bot's sweep or a retaliation never hurts another."
  "racers")

(def team-commands
  "What a race asks of its server once beside the gamerule: the team of racers, friendly fire off. A
   sword's sweep hits the players beside its target; at a spawner several bots stand together, the
   one struck fought back (combat: the striker is answered), and 47 of 344 deaths of lanes 1 to 6 of
   race 9d15bd6 were a bot killed by a bot. A team with friendly fire off takes no damage from its
   own."
  [(str "team add " team) (str "team modify " team " friendlyFire false")])

(defn team-join-command
  "The RCON command that puts bot name in the team of racers."
  [name]
  (str "team join " team " " name))

(defn set-gamerules!
  "What every race asks of its server once: keep inventories over deaths, the team of racers with
   friendly fire off (team-commands; an existing team answers an error, which is no matter)."
  [{:keys [host rcon-port rcon-pass]}]
  (try (rcon/with-rcon host
                       rcon-port
                       rcon-pass
                       (fn [rc]
                         (rcon/command! rc "gamerule keep_inventory true")
                         (doseq [c team-commands] (rcon/command! rc c))))
       (catch Exception e (cli/log "gamerule failed:" e))))

(defn land-bot!
  "Land one bot, on a thread of its own with an RCON session of its own: wait for it to be
   online, make it a survival player, land it (on landing i of the set, with landings; else on a
   cell claimed for it round the race's centre, which the first bot online decides), pin its spawn
   there, and put its :go (every step up to until, milestones on, :go/at where it landed) on its
   queue. A bot whose landing fails starts where it stands."
  [{:keys [host rcon-port rcon-pass until landings landing-offset bots] begin :start} ch centre name
   queue i slot]
  (async/thread
   (try
     (rcon/with-rcon
      host
      rcon-port
      rcon-pass
      (fn [rc]
        (let [online (fixture/wait-online! rc name fixture/online-timeout)
              _ (when online (deliver centre [(long (first online)) (long (nth online 2))]))
              at
              (when online
                (rcon/command! rc (str "gamemode survival " name))
                (rcon/command! rc (team-join-command name))
                (cond (start/nether? begin)
                      (let [k (landing-slot slot i bots)]
                        ((if (start/forest? begin) land/land-forest! land/land-fortress!)
                         rc
                         name
                         (if landings (land/landing-of landings (or landing-offset 0) k) @centre)
                         k
                         (start/kit-commands begin)))
                      landings
                      (fixture/land-at! rc name (land/landing-of landings (or landing-offset 0) i))
                      :else (land! rc ch name @centre i)))]
          (when at
            (rcon/command! rc
                           (spawn-command
                            name
                            at
                            (if (start/nether? begin) dimension/nether dimension/overworld))))
          (cli/log name "landed at" at)
          (async/>!! queue
                     (cond-> {:event/kind :go :go/goals (start/goals begin until) :go/chain? true}
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

(defn near-truth!
  "The server's answers to near-commands for bot name ended at pos in dim, one block of text a
   command, or why it could not be asked."
  [{:keys [host rcon-port rcon-pass]} name pos dim]
  (try (rcon/with-rcon
        host
        rcon-port
        rcon-pass
        (fn [rc]
          (str/join "\n\n"
                    (for [c (near-commands name (mapv #(long (math/floor (double %))) pos) dim)]
                      (str "> " c "\n" (rcon/command! rc c))))))
       (catch Exception e (str "near truth failed: " (ex-message e)))))

(defn with-death-message
  "RESULT r with the server's death line for bot name (gym/death-message from the server's log,
   the file :server-log names) as :death-message, when the run ended :death, and its :reason the
   class of that line (:death-blaze, :death-lava ..., death-class; :death-other with no line):
   the signature [stage reason] names the cause. r otherwise. The cause the bot cannot tell: it is
   the server's word."
  [{:keys [server-log]} name r]
  (if (= :death (:reason r))
    (let [f (some-> server-log
                    io/file)
          msg (when (and f (.exists f))
                (try (gym/death-message (slurp f) name) (catch Exception _ nil)))]
      (cond-> (assoc r :reason (keyword (str "death-" (clojure.core/name (death-class msg)))))
        msg (assoc :death-message msg)))
    r))

(def serverlog-bytes 524288)              ; the last half megabyte of the log file is read, not all of a five-hour log

(defn file-tail
  "The last n bytes of file f as text (UTF-8), the whole file when it is shorter; the first line
   may be cut."
  [^java.io.File f n]
  (with-open [r (java.io.RandomAccessFile. f "r")]
    (let [len (.length r)
          start (max 0 (- len (long n)))
          buf (byte-array (- len start))]
      (.seek r start)
      (.readFully r buf)
      (String. buf "UTF-8"))))

(defn serverlog-text
  "The text of bot name's <name>.serverlog.txt: its serverlog-lines of the log file at path joined
   by newlines, or why they could not be read (a missing file, an error), as one line. Never
   throws."
  [path name]
  (let [f (some-> path
                  io/file)]
    (try (cond (nil? f) "no server log named"
               (not (.exists f)) (str "no server log at " path)
               :else
               (str (str/join "\n" (serverlog-lines (file-tail f serverlog-bytes) name)) "\n"))
         (catch Exception e (str "server log not read: " (ex-message e))))))

(defn play-one!
  "One bot's whole life in a slot: the next serial's name (serials, an atom shared by the slots),
   its own atom and queue, on the board (live) and watched for milestones while it plays, landed on
   landing serial - 1 of the set (land-bot!), and played until its goal, its plan failing, a crash
   or the job's end (deadline); a bot whose start has a cap (start/cap-ticks, game time: the
   server ticks it saw) also stops at it, a :bot-timeout, filed (capped), where the job's end is a
   cut. Its RESULT line is printed as it ends, marked
   cut (failure/cut) when the job's end stopped it. Its senses (senses/senses!: a line a second of
   what it sensed and decided, and the loop's slowest event) are written beside its recording as
   <name>.senses.edn the moment before its RESULT, so a batch that counts the RESULT has them,
   and its ledger beside them (senses/ledger!: every change, its hits, swings, intents and the
   entities round it) as <name>.events.edn, its mind at the end (senses/moment) as
   <name>.moment.edn and the minutes before (the ring) as <name>.ring.edn, and the server log's
   lines about it (serverlog-lines: its death line, a kick, a server that can't keep up) as
   <name>.serverlog.txt.
   Returns {:name :result :truth}."
  [{:keys [server ch centre serials live watcher prefix record-dir commit deadline slot]}]
  (let [n (swap! serials inc)
        start (System/currentTimeMillis)
        cap (start/cap-ticks (:start server))
        name (bot-name prefix n)
        queue (async/chan events-buffer)
        world* (atom (game/init {:host (:host server) :port (:port server) :name name}))
        slowest (atom 0)
        senses (senses/senses! world* slowest)
        senses-file (str (io/file record-dir (str name ".senses.edn")))
        ledger (senses/ledger! world*)
        events-file (str (io/file record-dir (str name ".events.edn")))
        write-senses! (fn []
                        (try
                          (senses/write! senses-file ((:lines senses)))
                          (senses/write-events! events-file ((:events ledger)) ((:dropped ledger)))
                          (senses/write-moments! (str (io/file record-dir (str name ".moment.edn")))
                                                 @world*
                                                 (str (io/file record-dir (str name ".ring.edn")))
                                                 ((:ring senses)))
                          (catch Exception e (cli/log name "senses not written:" e))))]
    (swap! live assoc name world*)
    ((:watch watcher) name world*)
    (land-bot! server ch centre name queue (dec n) slot)
    (try
      (let [truth (promise)
            {r :result}
            (main/run-bot!
             {:host (:host server)
              :port (:port server)
              :name name
              :until (:until server)
              :chain true
              :end-on-death? (start/nether? (:start server))
              :timeout-ms (bot-timeout-ms deadline start)
              :cap-ticks cap
              :record (str (io/file record-dir (str name ".edn")))
              :commit commit
              :events queue
              :world* world*
              :on-ms (fn [ms] (swap! slowest max ms))
              :on-result (fn [r]
                           (write-senses!)
                           (try (spit (str (io/file record-dir (str name ".serverlog.txt")))
                                      (serverlog-text (:server-log server) name))
                                (catch Exception e (cli/log name "server log not kept:" e)))
                           (deliver truth (truth! server name))
                           (when-let [pos (:pos r)]
                             (try (spit (str (io/file record-dir (str name ".server.txt")))
                                        (near-truth! server
                                                     name
                                                     pos
                                                     (or (:player/dimension @world*)
                                                         "minecraft:overworld")))
                                  (catch Exception e (cli/log name "near truth not written:" e))))
                           (println "RESULT"
                                    name
                                    (pr-str (marked (capped (with-death-message server name r)
                                                            (game/server-ticks @world*)
                                                            cap)))) ; what bb failures groups by
                           (flush))})]
        {:name name :result (capped r (game/server-ticks @world*) cap) :truth @truth})
      (catch Exception e
        (cli/log name "failed:" e)
        (write-senses!)
        (let [r {:ok false
                 :reason :crashed
                 :error (ex-message e)
                 :until (:until server)
                 :plan (plan/summary @world*)}]
          (println "RESULT" name (pr-str (marked r))) ; filed at the stage it crashed in
          (flush)
          {:name name :result r}))
      (finally ((:unwatch watcher) world*)
               ((:close senses))
               ((:close ledger))
               (swap! live dissoc name)))))

(defn accepts?
  "Does the server take a connection on its game port now (within 2 s)?"
  [{:keys [^String host port]}]
  (try (with-open [s (java.net.Socket.)]
         (.connect s (java.net.InetSocketAddress. host (int port)) 2000)
         true)
       (catch Exception _ false)))

(defn ended!
  "Fold a bot's RESULT r into the server's health (its atom :health*, by health); the slot whose
   bot turns it down prints it once, loudly, on the board (the job's log shows BOARD lines)."
  [{:keys [health*]} r]
  (let [[before after] (swap-vals! health* health r)]
    (when (and (:race/down? after) (not (:race/down? before)))
      (println "BOARD SERVER DOWN:"
               dead-after
               "bots in a row never got into play; its slots start none until it accepts again,"
               "asking every"
               (quot probe-every-ms 1000)
               "s")
      (flush))))

(defn probe!
  "Ask a down server whether it accepts again; when it does, its health is recovered, and the
   slot that finds it so says it on the board and sets the game rules again (a restarted server,
   race.yml's watch, may have lost them)."
  [{:keys [server health*]}]
  (when (accepts? server)
    (let [[before _] (swap-vals! health* (constantly recovered))]
      (when (:race/down? before)
        (println "BOARD SERVER BACK: it accepts again; its slots start bots")
        (flush)
        (set-gamerules! server)))))

(defn start-turn!
  "Take this slot's turn on the server's start clock (an atom of the last start, next-start) and
   sleep until it comes."
  [{:keys [starts]}]
  (let [now (System/currentTimeMillis)
        at (swap! starts next-start now)]
    (Thread/sleep (long (max 0 (- at now))))))

(defn slot!
  "One bot slot, on a thread of its own: it joins at its place in the schedule (start-at), then
   plays bot after bot (play-one!), the next the moment the last ends, or after backoff-ms when
   the last never got to play, never sooner than its turn on the server's start clock
   (start-turn!), until less than min-play-ms is left of the job. While the server is down
   (health: dead-after bots in a row never got into play) it starts none and asks every
   probe-every-ms whether the server accepts again. Returns a channel of its results."
  [{:keys [config t0 deadline health*] :as ctx} i]
  (async/thread
   (Thread/sleep (long (max 0 (- (+ t0 (start-at config i)) (System/currentTimeMillis)))))
   (loop [results []
          wait 0]
     (cond (< (- deadline (System/currentTimeMillis)) min-play-ms) results
           (:race/down? @health*)
           (do (Thread/sleep (long probe-every-ms)) (probe! ctx) (recur results 0))
           :else
           (let [_ (Thread/sleep (long wait))
                 _ (start-turn! ctx)
                 t (System/currentTimeMillis)
                 r (play-one! (assoc ctx :slot i))]
             (ended! ctx (:result r))
             (recur (conj results r)
                    (long (backoff-ms wait (- (System/currentTimeMillis) t) (:result r)))))))))

(def known-options
  "The options -main reads (cli/options rejects any other)."
  #{:config :host :port :rcon-port :rcon-pass :record-dir :commit :seed :landing-offset :runner
    :server :landings :start})

(defn -main
  "Run the race ci/race.edn (or --config) describes on the server at --port, its RCON at --rcon-port
   with --rcon-pass, for the job's length (run-ms): :race/bots slots, each refilled with a fresh bot
   the moment its bot ends, so the server always carries its bots; the board, the sampler and the
   milestones watch whichever bots play now. A RESULT line per bot as it ends, a RACE summary of
   every bot at the end, written to the record directory. Exits 0 when any bot reached the target."
  [& args]
  (let [o (cli/options args known-options)
        {:keys [config host port rcon-port rcon-pass record-dir commit seed landing-offset]
         :or {config "ci/race.edn" host "127.0.0.1" record-dir "data/race"}}
        o
        runner (get o :runner)
        which (get o :server) ; which of the runner's servers (--server); server below is the one connected to
        landings-file (get o :landings)
        config (servers/for-lane (edn/read-string (slurp config))
                                 (some-> runner
                                         parse-long))
        {:race/keys [bots prefix until]} config
        t0 (System/currentTimeMillis)
        server (cond-> {:host host
                        :port (Long/parseLong port)
                        :rcon-port (Long/parseLong rcon-port)
                        :rcon-pass rcon-pass
                        :until until
                        :start (or (start/start-of (get o :start))
                                   (throw (ex-info "no such start" {:start (get o :start)})))
                        :bots bots
                        :server-log (str "work/world-" (or which 1) "/logs/latest.log")}
                 landings-file
                 (assoc :landings (landings/read-landings landings-file)
                        :landing-offset (parse-long (or landing-offset "0"))))
        _ (.mkdirs (io/file record-dir))
        where {:runner (some-> runner
                               parse-long)
               :server (some-> which
                               parse-long)}
        where (assoc where
                     :simulation-distance (servers/simulation-distance config (:server where))
                     :runner-servers (servers/servers-of config)
                     :runner-bots (servers/bots-of config))
        done (promise)
        live (atom {})
        ctx {:server server
             :ch (claims!)
             :centre (promise)
             :serials (atom 0)
             :health* (atom recovered)
             :starts (atom 0)
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
    (when (start/nether? (:start server)) (land/prepare! server bots))
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
