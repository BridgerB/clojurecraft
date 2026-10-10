(ns clojurecraft.main
  "The loop: the one place with an atom, a clock, randomness and effects. Reads packets and
   external events, feeds the reducer, drains :bot/effects, prints RESULT.

     clojure -M:run --port 25571 [--name N] [--until play|wood|table|pickaxe] [--events stdin]
                    [--timeout-ms 120000] [--hold-ms 0] [--record run.edn] [--telemetry t.edn]
     clojure -M:replay run.edn

   With --events stdin the bot reads EDN events (one per line) from stdin: that is how the
   fixture (clojure -M:harness, its own process) tells it to go. Without it, a planned --until
   starts where the bot stands, once it is loaded. --telemetry writes one EDN line per change of
   the world, from a watch on the atom (clojurecraft.watch) that never slows the loop."
  (:require [clojure.core.async :as a]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [clojurecraft.conn :as conn]
            [clojurecraft.game :as game]
            [clojurecraft.plan :as plan]
            [clojurecraft.record :as record]
            [clojurecraft.make]
            [clojurecraft.memory :as memory]
            [clojurecraft.recipe :as recipe]
            [clojurecraft.watch :as watch]
            [clojurecraft.wood])
  (:gen-class))

(def step
  "The whole bot: the world reducer, then the planner. Requiring clojurecraft.wood and
   clojurecraft.make registers the goals."
  (game/compose game/step plan/step))

(defn parse-args
  "--key value pairs → {:key \"value\"}; values stay strings, a trailing lone flag is dropped."
  [args]
  (into {} (map (fn [[k v]] [(keyword (subs k 2)) v]) (partition 2 args))))

(defn goal-fn
  "What counts as done for a --until name: the plan done when it names goals, else being loaded."
  [until]
  (if (plan/goals-for until) plan/done? :player/loaded?))

(defn ok?
  "Did a run that ended in world succeed, for --until until: its goal holds and the socket is
   neither closed nor disconnected. The live run and its replay judge with this one rule."
  [world until]
  (boolean (and ((goal-fn until) world) (not (:bot/closed world)) (not (:bot/disconnected world)))))

(defn result
  "The RESULT map: :ok, :until, the reason, the game summary, held items, the nearest
   remembered table and the plan summary (once a plan began)."
  [world until ok]
  (merge {:ok ok
          :until until
          :reason (cond ok :goal
                        (:bot/disconnected world) :disconnected
                        (:bot/closed world) :closed
                        (plan/failed? world) (:plan/reason world)
                        :else :timeout)}
         (game/summary world)
         (let [held (recipe/counts (:player/inventory world))]
           {:held held
            :planks (recipe/have held (recipe/tags :planks))
            :sticks (get held :stick 0)
            :tables (get held :crafting_table 0)})
         (when-let [t (and (:player/pos world) (memory/nearest world (game/eye world) 32 memory/crafting-table?))]
           {:table/pos t})                     ; no position yet (an early end): no table to speak of
         (when (:plan/status world) {:plan (plan/summary world)})))

;;;; I/O: the clock, stderr, the socket, the atom ;;;;

(defn- now [] (System/currentTimeMillis))

(defn stamp "One timestamped line on stderr; stdout carries only RESULT." [& xs]
  (binding [*out* *err*] (println (str (java.time.LocalTime/now)) (str/join " " xs)) (flush)))

(defn perform
  "Do one effect: :send puts the packet on the socket's out channel (blocking), :log stamps it."
  [{:effect/keys [kind packet message]} out]
  (case kind
    :send (a/>!! out packet)
    :log (stamp message)))

(defn apply-event!
  "The epochal write: swap the world, then perform and clear what it asked for."
  [world* event out tap]
  (when tap ((:write tap) event))
  (swap! world* step event)
  (let [effects (:bot/effects @world*)]
    (when tap ((:effects tap) effects))
    (swap! world* assoc :bot/effects [])
    (doseq [e effects] (perform e out))))

(defn run-loop
  "Drive the reducer over socket packets, 50 ms ticks and external events until (stop? world)
   or the socket closes. Returns the final world."
  [{:keys [in out]} events world* stop? tap]
  (loop [next-tick (+ (now) 50)]
    (let [[v ch] (a/alts!! [in events (a/timeout (max 0 (- next-tick (now))))])
          event (cond (= ch in) (if (nil? v)
                                  {:event/kind :closed :event/reason "socket closed"}
                                  {:event/kind :packet :event/packet v})
                      (= ch events) v
                      :else {:event/kind :tick :event/now (now) :event/rand (rand)})]
      (apply-event! world* event out tap)
      (let [w @world*]
        (if (or (stop? w) (:bot/closed w) (:bot/disconnected w))
          w
          (recur (if (= :tick (:event/kind event)) (+ next-tick 50) next-tick)))))))

(defn read-events!
  "Feed EDN events from a reader (the fixture's stdout, piped in) onto the events channel until
   end of input. The only way anything outside the bot reaches it."
  [reader events]
  (a/thread
    (doseq [line (line-seq (java.io.BufferedReader. reader))
            :let [line (str/trim line)]
            :when (seq line)]
      (try (a/>!! events (edn/read-string {:readers record/readers} line))
           (catch Exception e (stamp "unreadable event:" line (.getMessage e)))))))

(defn go-when-loaded!
  "Put a :go on the queue the first time the world says the player is loaded: the run without
   a fixture starts where the bot stands, as soon as it stands anywhere."
  [world* queue go]
  (add-watch world* ::go (fn [k r _ w]
                           (when (:player/loaded? w)
                             (remove-watch r k)
                             (a/put! queue go)))))

(defn -main
  "Connect, run until the goal, a failed plan, the deadline or a closed socket, print RESULT,
   hold for an outside judge when ok, and exit 0 when ok else 1."
  [& args]
  (let [{:keys [host port name until timeout-ms hold-ms events record telemetry]
         :or {host "127.0.0.1" port "25571" name "Clj_wood" until "wood" timeout-ms "120000" hold-ms "0"}}
        (parse-args args)
        opts {:host host :port (Long/parseLong port) :name name}
        deadline (+ (now) (Long/parseLong timeout-ms))
        hold (Long/parseLong hold-ms)
        planned (plan/goals-for until)
        goal? (goal-fn until)
        stop? (fn [w] (or (goal? w) (and planned (plan/failed? w)) (> (now) deadline)))
        tap (some-> record record/tap)
        c (conn/open opts)
        queue (a/chan 16)
        world* (atom (game/init opts))
        watcher (some->> telemetry (watch/telemetry! world*))]
    (stamp "connected to" host port "as" name)
    (cond
      (= events "stdin") (read-events! *in* queue)                     ; a fixture speaks through the pipe
      planned (go-when-loaded! world* queue {:event/kind :go :go/goals planned})) ; no fixture: start where we stand
    (apply-event! world* {:event/kind :start :start/host host :start/port (:port opts) :start/name name} (:out c) tap)
    (let [final (run-loop c queue world* stop? tap)
          ok (ok? final until)
          r (result final until ok)]
      (prn 'RESULT r)
      (flush)
      (when tap ((:result tap) r) ((:close tap)))     ; the recording is the run: it ends at RESULT
      (when (and ok (pos? hold))
        (stamp "holding" hold "ms for an outside judge")
        (let [until (+ (now) hold)] (run-loop c queue world* (fn [_] (> (now) until)) nil)))
      (when watcher ((:close watcher)) (stamp "telemetry dropped" ((:dropped watcher)) "lines"))
      ((:close! c))
      (shutdown-agents)
      (System/exit (if ok 0 1)))))

(defn replayed
  "The RESULT of folding the reducer over a recording, judged as the run judged itself (its
   recorded --until), plus :replay/effects (every effect identical, or the first mismatch) and
   :replay/result (:identical when the fold reaches exactly the RESULT the run printed)."
  [path]
  (let [world0 (game/init {:host "replay" :port 0 :name "Clj_replay"})
        final (record/replay step world0 path)
        check (record/verify step world0 path)
        recorded (record/recorded-result path)
        until (:until recorded "replay")
        r (result final until (ok? final until))]
    (assoc r
           :replay/effects (cond (nil? check) :identical
                                 (= check :record/no-effects) :not-recorded
                                 :else check)
           :replay/result (cond (nil? recorded) :not-recorded
                                (= r recorded) :identical
                                :else {:recorded recorded}))))

(defn replay
  "clojure -M:replay run.edn → print the replayed RESULT (see replayed)."
  [& [path]]
  (prn 'RESULT (replayed path))
  (shutdown-agents))
