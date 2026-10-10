(ns clojurecraft.main
  "The loop: the one place with an atom, a clock, randomness and effects. Reads packets and
   external events, feeds the reducer, drains :bot/effects, prints RESULT.

     clojure -M:run --port 25571 [--name N] [--until play|wood|table|pickaxe] [--events stdin]
                    [--timeout-ms 120000] [--hold-ms 0] [--record run.edn]
     clojure -M:replay run.edn

   With --events stdin the bot reads EDN events (one per line) from stdin: that is how the
   fixture (clojure -M:harness, its own process) tells it to go. Without it, a planned --until
   starts where the bot stands, once it is loaded."
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
            [clojurecraft.wood])
  (:gen-class))

(def step
  "The whole bot: the world reducer, then the planner. Requiring clojurecraft.wood and
   clojurecraft.make registers the goals."
  (game/compose game/step plan/step))

(defn parse-args [args]
  (into {} (map (fn [[k v]] [(keyword (subs k 2)) v]) (partition 2 args))))

(defn- now [] (System/currentTimeMillis))

(defn- stamp [& xs]
  (binding [*out* *err*] (println (str (java.time.LocalTime/now)) (str/join " " xs)) (flush)))

(defn- perform [{:effect/keys [kind packet message]} out]
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

(defn result [world until ok]
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
         (when-let [t (memory/nearest world (game/eye world) 32 memory/crafting-table?)]
           {:table/pos t})
         (when (:plan/status world) {:plan (plan/summary world)})))

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

(defn -main [& args]
  (let [{:keys [host port name until timeout-ms hold-ms events record]
         :or {host "127.0.0.1" port "25571" name "Clj_wood" until "wood" timeout-ms "120000" hold-ms "0"}}
        (parse-args args)
        opts {:host host :port (Long/parseLong port) :name name}
        deadline (+ (now) (Long/parseLong timeout-ms))
        hold (Long/parseLong hold-ms)
        planned (plan/goals-for until)
        goal? (if planned plan/done? :player/loaded?)
        stop? (fn [w] (or (goal? w) (and planned (plan/failed? w)) (> (now) deadline)))
        tap (some-> record record/tap)
        c (conn/open opts)
        queue (a/chan 16)
        world* (atom (game/init opts))]
    (stamp "connected to" host port "as" name)
    (cond
      (= events "stdin") (read-events! *in* queue)                     ; a fixture speaks through the pipe
      planned (go-when-loaded! world* queue {:event/kind :go :go/goals planned})) ; no fixture: start where we stand
    (apply-event! world* {:event/kind :start :start/host host :start/port (:port opts) :start/name name} (:out c) tap)
    (let [final (run-loop c queue world* stop? tap)
          ok (boolean (and (goal? final) (not (:bot/closed final)) (not (:bot/disconnected final))))]
      (prn 'RESULT (result final until ok))
      (flush)
      (when (and ok (pos? hold))
        (stamp "holding" hold "ms for an outside judge")
        (let [until (+ (now) hold)] (run-loop c queue world* (fn [_] (> (now) until)) tap)))
      (some-> tap :close (apply []))
      ((:close! c))
      (shutdown-agents)
      (System/exit (if ok 0 1)))))

(defn replay
  "clojure -M:replay run.edn → RESULT of folding the reducer over the recording."
  [& [path]]
  (let [world0 (game/init {:host "replay" :port 0 :name "Clj_replay"})
        final (record/replay step world0 path)
        check (record/verify step world0 path)]
    (prn 'RESULT (assoc (result final "replay" (plan/done? final))
                        :replay/effects (cond (nil? check) :identical
                                              (= check :record/no-effects) :not-recorded
                                              :else check)))
    (shutdown-agents)))
