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

(defn locate-forest
  "The nearest forest from the bot, [x z], or nil."
  [rc name]
  (rcon/command rc (str "gamemode survival " name))
  (rcon/command rc (str "clear " name))
  (let [r (rcon/command rc (str "execute at " name " run locate biome minecraft:forest"))
        [_ fx fz] (re-find #"\[(-?\d+), (?:~|-?\d+), (-?\d+)\]" r)]
    (stamp "locate:" r)
    (when fx [(Long/parseLong fx) (Long/parseLong fz)])))

(defn teleport!
  "Load the column at x z and teleport the bot onto its surface. The motion_blocking_no_leaves
   heightmap counts water as a surface, so this can land the bot on a pond."
  [rc name [x z]]
  (rcon/command rc (str "forceload add " x " " z))
  (loop [i 0]
    (let [r (rcon/command rc (str "execute if loaded " x " 0 " z))]
      (when (and (not (str/includes? r "passed")) (< i 150))
        (Thread/sleep 200)
        (recur (inc i)))))
  (stamp "tp:" (rcon/command rc (str "execute positioned " x " 0 " z
                                     " positioned over motion_blocking_no_leaves run tp " name " ~0.5 ~ ~0.5")))
  (rcon/command rc (str "forceload remove " x " " z)))

(def landing-offsets
  "Where to try when a landing will not do: the located point, then two rings of eight nearby
   points in the same forest, nearest first (a CI run needed five tries around one pond)."
  (into [[0 0]] (for [r [24 48] [dx dz] [[1 0] [0 1] [-1 0] [0 -1] [1 1] [-1 1] [-1 -1] [1 -1]]]
                  [(* r dx) (* r dz)])))

(defn buried?
  "Is there a solid block where the player's feet or head should be? Seen in CI: the teleport
   onto a forest point's surface put the bot at y=58 inside stone (the heightmap was not ready),
   and every walk failed."
  [world]
  (let [[x y z] (:player/pos world)
        fx (long (Math/floor x)) fy (long (Math/floor y)) fz (long (Math/floor z))]
    (boolean (some #(some-> (game/block-at world [fx % fz]) blocks/solid?) [fy (inc fy)]))))

(defn perched?
  "Is the player standing on or inside a tree? The motion_blocking_no_leaves heightmap skips
   leaves but not the top of a trunk, so a forest point can land the bot on a log in the canopy
   (seen in CI: on oak leaves at y=91, unable to come down)."
  [world]
  (let [[x y z] (:player/pos world)
        fx (long (Math/floor x)) fy (long (Math/floor y)) fz (long (Math/floor z))
        tree? (fn [id] (and id (or (blocks/log? id) (blocks/leaves? id))))]
    (boolean (or (tree? (game/block-at world [fx (dec fy) fz])) (tree? (game/block-at world [fx fy fz]))))))

(defn wet?
  "Is the player standing in or on a liquid? (Water physics is issue #5; the fixture avoids it.)"
  [world]
  (let [[x y z] (:player/pos world)
        fx (long (Math/floor x)) fy (long (Math/floor y)) fz (long (Math/floor z))]
    (boolean (some #(= :liquid (some-> (game/block-at world [fx % fz]) blocks/type-of)) [fy (dec fy)]))))

(defn bad-landing
  "Why a landing will not do for this fixture, or nil when it is good: dry, not in a tree, not
   inside the ground."
  [world]
  (cond (wet? world) :water
        (perched? world) :tree
        (buried? world) :buried
        :else nil))

(defn- standing-on [world]
  (let [[x y z] (:player/pos world)
        at (fn [dy] (blocks/name-of (or (game/block-at world [(long (Math/floor x)) (+ (long (Math/floor y)) dy) (long (Math/floor z))]) -1)))]
    (str "on " (at -1) " in " (at 0) " / " (at 1))))

(defn land-and-go!
  "Once the bot is loaded: land it (when RCON is configured), wait for the teleport and its
   chunks, then send {:event/kind :go}."
  [world* events {:keys [name rcon-host rcon-port rcon-pass go]}]
  (wait-for world* :player/loaded? 60000)
  (when rcon-pass
    (rcon/with-rcon rcon-host rcon-port rcon-pass
      (fn [rc]
        (when-let [[fx fz] (locate-forest rc name)]
          (loop [[[ox oz] & more] landing-offsets]
            (let [teleports (:stats/teleports @world*)]
              (teleport! rc name [(+ fx ox) (+ fz oz)])
              (wait-for world* #(> (:stats/teleports %) teleports) 20000)
              (wait-for world* #(game/chunk-loaded? % (:player/pos %)) 20000)
              (Thread/sleep 1000)
              (when-let [why (and (seq more) (bad-landing @world*))]
                (stamp "bad landing" why "- trying another spot")
                (recur more))))))))
  (let [w @world*] (stamp "landed at" (:player/pos w) (standing-on w)))
  (a/>!! events (or go {:event/kind :go})))
