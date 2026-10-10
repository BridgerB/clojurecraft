(ns clojurecraft.main
  "The loop: the one place with an atom, a clock, randomness and effects. Reads packets and
   external events, feeds the reducer, drains :bot/effects, prints RESULT.

     clojure -M:run --port 25571 --rcon-port 25581 --rcon-pass S [--name N] [--until play|wood]
                    [--timeout-ms 120000] [--hold-ms 0] [--record run.edn]
     clojure -M:replay run.edn"
  (:require [clojure.core.async :as a]
            [clojure.string :as str]
            [clojurecraft.conn :as conn]
            [clojurecraft.game :as game]
            [clojurecraft.harness :as harness]
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

(def planned
  "--until value → the goals put in play by the :go event."
  {"wood" [:wood] "table" [:kit] "pickaxe" [:pickaxe]})

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

(defn -main [& args]
  (let [{:keys [host port name until timeout-ms hold-ms rcon-host rcon-port rcon-pass record]
         :or {host "127.0.0.1" port "25571" name "Clj_wood" until "wood" timeout-ms "120000" hold-ms "0"}}
        (parse-args args)
        opts {:host host :port (Long/parseLong port) :name name}
        deadline (+ (now) (Long/parseLong timeout-ms))
        hold (Long/parseLong hold-ms)
        goal? (if (planned until) plan/done? :player/loaded?)
        stop? (fn [w] (or (goal? w) (and (planned until) (plan/failed? w)) (> (now) deadline)))
        go {:event/kind :go :go/goals (planned until)}
        tap (some-> record record/tap)
        c (conn/open opts)
        events (a/chan 16)
        world* (atom (game/init opts))]
    (stamp "connected to" host port "as" name)
    (when (planned until)
      (a/thread (try (harness/land-and-go! world* events {:name name :rcon-host (or rcon-host host)
                                                          :rcon-port (some-> rcon-port Long/parseLong)
                                                          :rcon-pass rcon-pass :go go})
                     (catch Throwable e (stamp "harness failed:" e) (a/>!! events go)))))
    (apply-event! world* {:event/kind :start :start/host host :start/port (:port opts) :start/name name} (:out c) tap)
    (let [final (run-loop c events world* stop? tap)
          ok (boolean (and (goal? final) (not (:bot/closed final)) (not (:bot/disconnected final))))]
      (prn 'RESULT (result final until ok))
      (flush)
      (when (and ok (pos? hold))
        (stamp "holding" hold "ms for an outside judge")
        (let [until (+ (now) hold)] (run-loop c events world* (fn [_] (> (now) until)) tap)))
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
