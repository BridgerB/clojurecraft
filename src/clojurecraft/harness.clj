(ns clojurecraft.harness
  "Test fixture for the natural-tree goal: land the bot in a forest over RCON. It is an observer
   of the world atom (add-watch: perception without coordination) and talks to the loop only
   through the events channel. Nothing in the game knows it exists."
  (:require [clojure.core.async :as a]
            [clojure.string :as str]
            [clojurecraft.blocks :as blocks]
            [clojurecraft.game :as game]
            [clojurecraft.rcon :as rcon]))

(defn- stamp [& xs]
  (binding [*out* *err*] (println (str (java.time.LocalTime/now)) (str/join " " xs)) (flush)))

(defn wait-for
  "Block until (pred @world*) is truthy or timeout-ms passes; returns the value or nil."
  [world* pred timeout-ms]
  (let [p (promise)
        k (Object.)]
    (add-watch world* k (fn [_ _ _ w] (when-let [v (pred w)] (deliver p v))))
    (try
      (or (pred @world*) (deref p timeout-ms nil))
      (finally (remove-watch world* k)))))

(defn land!
  "Find the nearest forest from the bot and teleport it onto the ground there. Returns [x z] or nil."
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

(defn- standing-on [world]
  (let [[x y z] (:player/pos world)
        at (fn [dy] (blocks/name-of (or (game/block-at world [(long (Math/floor x)) (+ (long (Math/floor y)) dy) (long (Math/floor z))]) -1)))]
    (str "on " (at -1) " in " (at 0) " / " (at 1))))

(defn land-and-go!
  "Once the bot is loaded: land it (when RCON is configured), wait for the teleport and its
   chunks, then send {:event/kind :go}."
  [world* events {:keys [name rcon-host rcon-port rcon-pass]}]
  (wait-for world* :player/loaded? 60000)
  (when rcon-pass
    (let [teleports (:stats/teleports @world*)]
      (rcon/with-rcon rcon-host rcon-port rcon-pass #(land! % name))
      (wait-for world* #(> (:stats/teleports %) teleports) 20000)
      (wait-for world* #(game/chunk-loaded? % (:player/pos %)) 20000)
      (Thread/sleep 1000)))
  (let [w @world*] (stamp "landed at" (:player/pos w) (standing-on w)))
  (a/>!! events {:event/kind :go}))
