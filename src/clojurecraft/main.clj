(ns clojurecraft.main
  "Wiring: socket ↔ reducer loop ↔ effects, the RCON harness, the RESULT line.
   The only namespace with a loop, an atom holding game state, and a clock."
  (:require [clojure.core.async :as a]
            [clojure.string :as str]
            [clojurecraft.blocks :as blocks]
            [clojurecraft.conn :as conn]
            [clojurecraft.game :as game]
            [clojurecraft.rcon :as rcon]
            [clojurecraft.wood :as wood])
  (:gen-class))

(defn parse-args [args]
  (into {} (map (fn [[k v]] [(keyword (subs k 2)) v]) (partition 2 args))))

(defn- now [] (System/currentTimeMillis))

(defn- stamp [& xs]
  (binding [*out* *err*]
    (println (str (java.time.LocalTime/now)) (str/join " " xs))
    (flush)))

(defn start!
  "Send the handshake and login packets."
  [{:keys [out]} state* step]
  (let [{:keys [state effects]} (step @state* [:start])]
    (reset! state* state)
    (doseq [[kind x] effects] (when (= kind :send) (a/>!! out x)))))

(defn run-loop
  "Drive step over socket packets, 50 ms ticks and external events until (stop? state) or the
   socket closes. state* is the one atom; only this loop writes it."
  [{:keys [in out]} events state* step stop?]
  (do
    (loop [next-tick (+ (now) 50)]
      (let [[v ch] (a/alts!! [in events (a/timeout (max 0 (- next-tick (now))))])
            event (cond (= ch in) (if (nil? v) [:closed "socket closed"] [:packet v])
                        (= ch events) v
                        :else [:tick (now)])
            {:keys [state effects]} (step @state* event)]
        (reset! state* state)
        (doseq [[kind x] effects]
          (case kind
            :send (a/>!! out x)
            :log (stamp x)))
        (if (or (stop? state) (:closed state) (:disconnected state))
          state
          (recur (if (= :tick (first event)) (+ next-tick 50) next-tick)))))))

(defn- wait-for
  "Block until (pred @state*) or timeout-ms; returns the truthy value or nil."
  [state* pred timeout-ms]
  (let [until (+ (now) timeout-ms)]
    (loop []
      (or (pred @state*)
          (when (< (now) until) (Thread/sleep 200) (recur))))))

(defn land!
  "RCON fixture: find the nearest forest from the bot and teleport it onto the ground there.
   Returns [x z] of the landing, or nil when no forest was found."
  [rc name]
  (rcon/command rc (str "gamemode survival " name))
  (rcon/command rc (str "clear " name))
  (let [r (rcon/command rc (str "execute at " name " run locate biome minecraft:forest"))
        [_ fx fz] (re-find #"\[(-?\d+), (?:~|-?\d+), (-?\d+)\]" r)]
    (stamp "locate:" r)
    (when fx
      (rcon/command rc (str "forceload add " fx " " fz))
      (loop [i 0]
        (let [r (rcon/command rc (str "execute if loaded " fx " 0 " fz))]
          (when (and (not (str/includes? r "passed")) (< i 150))
            (Thread/sleep 200)
            (recur (inc i)))))
      (stamp "tp:" (rcon/command rc (str "execute positioned " fx " 0 " fz
                                         " positioned over motion_blocking_no_leaves run tp " name " ~0.5 ~ ~0.5")))
      (rcon/command rc (str "forceload remove " fx " " fz))
      [(Long/parseLong fx) (Long/parseLong fz)])))

(defn- harness!
  "Once the bot is loaded: land it in a forest (when RCON is configured), wait for the
   teleport and its chunks, then send [:go]."
  [state* events {:keys [name rcon-host rcon-port rcon-pass]}]
  (wait-for state* #(get-in % [:player :loaded?]) 60000)
  (when rcon-pass
    (let [teleports (get-in @state* [:stats :teleports])]
      (rcon/with-rcon rcon-host rcon-port rcon-pass #(land! % name))
      (wait-for state* #(> (get-in % [:stats :teleports]) teleports) 20000)
      (wait-for state* #(game/chunk-loaded? % (get-in % [:player :pos])) 20000)
      (Thread/sleep 1000)))
  (let [s @state* [x y z] (get-in s [:player :pos]) at (fn [dy] (blocks/name-of (or (game/block-at s [(long (Math/floor x)) (+ (long (Math/floor y)) dy) (long (Math/floor z))]) -1)))]
    (stamp "landed at" (get-in s [:player :pos]) "on" (at -1) "in" (at 0) "/" (at 1)))
  (a/>!! events [:go]))

(defn -main [& args]
  (let [{:keys [host port name until timeout-ms hold-ms rcon-host rcon-port rcon-pass]
         :or {host "127.0.0.1" port "25571" name "Clj_wood" until "wood" timeout-ms "120000" hold-ms "0"}}
        (parse-args args)
        opts {:host host :port (Long/parseLong port) :name name}
        deadline (+ (now) (Long/parseLong timeout-ms))
        hold (Long/parseLong hold-ms)
        goal? (case until
                "play" (fn [s] (get-in s [:player :loaded?]))
                "wood" wood/done?)
        stop? (fn [s] (or (goal? s) (and (= until "wood") (wood/failed? s)) (> (now) deadline)))
        c (conn/open opts)
        events (a/chan 16)
        state* (atom (game/init opts))
        _ (stamp "connected to" host port "as" name)
        _ (when (= until "wood")
            (a/thread (try (harness! state* events {:name name :rcon-host (or rcon-host host)
                                                     :rcon-port (some-> rcon-port Long/parseLong)
                                                     :rcon-pass rcon-pass})
                           (catch Throwable e (stamp "harness failed:" e) (a/>!! events [:go])))))
        step (game/compose game/step wood/step)
        _ (start! c state* step)
        final (run-loop c events state* step stop?)
        ok (boolean (and (goal? final) (not (:closed final)) (not (:disconnected final))))]
    (prn 'RESULT (merge {:ok ok :until until
                         :reason (cond ok :goal
                                       (:disconnected final) :disconnected
                                       (:closed final) :closed
                                       (wood/failed? final) (get-in final [:task :reason])
                                       :else :timeout)}
                        (game/summary final)
                        (when (= until "wood") {:task (wood/summary final)})))
    (flush)
    (when (and ok (pos? hold))
      (stamp "holding" hold "ms for an outside judge")
      (let [until (+ (now) hold)]
        (run-loop c events state* step (fn [_] (> (now) until)))))
    ((:close! c))
    (shutdown-agents)
    (System/exit (if ok 0 1))))
