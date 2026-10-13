(ns clojurecraft.race
  "A race (issue #17): many bots on one server, from where each lands to the race's target.

     clojure -M:race --config ci/race.edn --port 25565 --rcon-port 25575 --rcon-pass S \\
       [--record-dir data/race] [--commit sha] [--seed s] [--runner n]

   docs/hickey.md, Conveyance: each bot is its own atom and loop (main/run-bot!, the same life a
   lone bot lives), and bots share nothing but one bounded channel, the claims of landing cells,
   folded by the pure claims reducer. The fixture lands each bot on the cell it claimed for it,
   over RCON, and speaks to that bot only through the bot's own bounded event queue (its :go).
   The board is an observer: once a minute it takes each bot's world as it is and prints a line;
   it never touches a loop. Every bot's run is its own recording and replays alone. The race's
   settings are data (ci/race.edn). Milestones are facts: an observer on each bot's atom turns
   each target newly reached into one {:milestone/bot :milestone/goal :milestone/at ...} line
   (MILESTONE on stdout, milestones.edn), which the race's board (bb race-board) publishes."
  (:require [clojure.core.async :as async]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojurecraft.claims :as claims]
            [clojurecraft.cli :as cli]
            [clojurecraft.dimension :as dimension]
            [clojurecraft.fixture :as fixture]
            [clojurecraft.game :as game]
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
(def milestones-buffer 256)           ; milestone facts waiting for the writer; a bot reaches at most a chain's worth

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

(defn reached-new
  "The targets world reached that before did not: what a chained run's step just added to
   :plan/reached, in no particular order."
  [before world]
  (remove (set (:plan/reached before)) (:plan/reached world)))

(defn milestone
  "The fact that bot name reached goal in world: on which runner and seed, when (:time/now, ms),
   and how long after its plan began (its landing)."
  [{:keys [runner seed]} name world goal]
  (cond-> {:milestone/runner runner
           :milestone/seed seed
           :milestone/bot name
           :milestone/goal goal
           :milestone/at (:time/now world)}
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
   cell was granted or the landing failed. Each search is a locate biome, which the server runs
   in one tick: a race with plain forest searched 40 times per bot from points with none near,
   one search took 60 s and the watchdog killed the server."
  [rc ch name centre]
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
          (take claim-tries (claims/spiral))))

(defn fixture!
  "The race's fixture, on a thread of its own: wait for each bot, keep inventories over deaths,
   land it on its claimed cell, pin its spawn there, and put its :go (every step up to until,
   milestones on, :go/at where it landed) on its queue. A bot whose landing fails starts where
   it stands."
  [{:keys [host rcon-port rcon-pass until]} bots ch]
  (async/thread
   (try (rcon/with-rcon
         host
         rcon-port
         rcon-pass
         (fn [rc]
           (rcon/command! rc "gamerule keep_inventory true")
           (let [first-at (fixture/wait-online! rc (:name (first bots)) fixture/online-timeout)
                 centre [(long (first first-at)) (long (nth first-at 2))]]
             (cli/log "race centre" centre)
             (doseq [{:keys [name queue]} bots]
               (let [at (when (fixture/wait-online! rc name fixture/online-timeout)
                          (rcon/command! rc (str "gamemode survival " name))
                          (land! rc ch name centre))]
                 (when at
                   (rcon/command! rc
                                  (str "spawnpoint " name
                                       " " (long (first at))
                                       " " (long (second at))
                                       " " (long (nth at 2)))))
                 (cli/log name "landed at" at)
                 (async/>!! queue
                            (cond-> {:event/kind :go :go/goals (plan/through until) :go/chain? true}
                              at (assoc :go/at at))))))))
        (catch Exception e (cli/log "race fixture failed:" e)))))

(defn board!
  "The board, on a thread of its own: every board-every-ms until done (a promise) is delivered,
   one BOARD line per bot from its world as it is now, and the server's tick time, the measure
   of how many bots a server can carry."
  [{:keys [host rcon-port rcon-pass]} bots done]
  (async/thread
   (while (= :wait (deref done board-every-ms :wait))
     (let [tick (try (rcon/with-rcon host rcon-port rcon-pass #(rcon/command! % "tick query"))
                     (catch Exception e (str "tick query failed: " (ex-message e))))]
       (println "BOARD" (str/replace (str tick) #"\s+" " "))
       (doseq [{:keys [name world*]} bots]
         (println "BOARD"
                  (try (board-line name @world*)
                       (catch Exception e (str name " unreadable: " (ex-message e))))))
       (flush)))))

(defn milestones!
  "The milestones observer: a watch on every bot's atom that turns each target newly reached into
   a milestone fact, put on a bounded channel and never blocking the bot's loop (a full channel
   drops, as telemetry does); a writer thread prints each as a MILESTONE line and appends it to
   milestones.edn in record-dir. Returns a function that stops watching and finishes the file."
  [race bots record-dir]
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
    (doseq [{:keys [name world*]} bots]
      (add-watch world*
                 ::milestones
                 (fn [_ _ before world]
                   (doseq [goal (reached-new before world)]
                     (async/offer! ch (milestone race name world goal))))))
    (fn []
      (doseq [{:keys [world*]} bots] (remove-watch world* ::milestones))
      (async/close! ch)
      (async/<!! writer))))

(defn truth!
  "The server's word on where a bot is: its Dimension, asked while it is still connected."
  [{:keys [host rcon-port rcon-pass]} name]
  (try (rcon/with-rcon host
                       rcon-port
                       rcon-pass
                       #(str (rcon/command! % (str "data get entity " name " Dimension"))))
       (catch Exception e (str "truth failed: " (ex-message e)))))

(defn -main
  "Run the race ci/race.edn (or --config) describes on the server at --port, its RCON at
   --rcon-port with --rcon-pass: start the bots a stagger apart, land them, show the board, print
   a RESULT line per bot as it ends and a RACE summary at the end, and write the summary to the
   record directory. Exits 0 when any bot reached the target."
  [& args]
  (let [{:keys [config host port rcon-port rcon-pass record-dir commit seed runner]
         :or {config "ci/race.edn" host "127.0.0.1" record-dir "data/race"}}
        (cli/options args)
        {:race/keys [bots prefix until timeout-ms stagger-ms]} (edn/read-string (slurp config))
        server {:host host
                :port (Long/parseLong port)
                :rcon-port (Long/parseLong rcon-port)
                :rcon-pass rcon-pass
                :until until}
        _ (.mkdirs (io/file record-dir))
        ch (claims!)
        racers (mapv (fn [k]
                       (let [name (bot-name prefix k)]
                         {:name name
                          :queue (async/chan events-buffer)
                          :world* (atom (game/init {:host host :port (:port server) :name name}))}))
                     (range 1 (inc bots)))
        done (promise)
        runs (mapv (fn [{:keys [name queue world*]}]
                     (Thread/sleep (long stagger-ms)) ; fresh players generating chunks at once time each other out
                     (async/thread
                      (try (let [truth (promise)
                                 {r :result} (main/run-bot!
                                              {:host host
                                               :port (:port server)
                                               :name name
                                               :until until
                                               :chain true
                                               :timeout-ms timeout-ms
                                               :record (str (io/file record-dir (str name ".edn")))
                                               :commit commit
                                               :events queue
                                               :world* world*
                                               :on-result (fn [r]
                                                            (deliver truth (truth! server name))
                                                            (println "RESULT" name (pr-str r))
                                                            (flush))})]
                             {:name name :result r :truth @truth})
                           (catch Exception e
                             (cli/log name "failed:" e)
                             {:name name
                              :result {:ok false :reason :crashed :error (ex-message e)}}))))
                   racers)]
    (cli/log "race:" bots "bots to" until "seed" seed)
    (let [stop-milestones (milestones! {:runner (some-> runner
                                                        parse-long)
                                        :seed seed}
                                       racers
                                       record-dir)]
      (fixture! server racers ch)
      (board! server racers done)
      (let [results (mapv async/<!! runs)
            s (assoc (summary results) :race/seed seed :race/commit commit :race/until until)]
        (deliver done true)
        (stop-milestones)
        (println "RACE" (pr-str (dissoc s :race/runs)))
        (spit (io/file record-dir "summary.edn") (pr-str s))
        (flush)
        (async/close! ch)
        (shutdown-agents)
        (System/exit (if (pos? (:race/ok s)) 0 1))))))
