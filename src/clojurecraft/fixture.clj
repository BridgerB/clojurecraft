(ns clojurecraft.fixture
  "What every fixture asks the server, over RCON, and the one thing it tells the bot. The forest
   harness (clojure -M:harness), the gym's land and judge, the landing-set builder and a plan
   are all fixtures: each waits for a player, loads columns, teleports onto a surface, judges a
   landing by the server's own answers, and hands the bot a :go event. The pure half reads the
   server's replies; the I/O half sends the commands."
  (:require [clojure.string :as str]
            [clojurecraft.cli :as cli]
            [clojurecraft.plan :as plan]
            [clojurecraft.rcon :as rcon]))

(set! *warn-on-reflection* true)

(def online-timeout 120000)           ; ms a fixture waits for the bot to join
(def settle-ms 1000)                  ; after a teleport, before asking about the landing

(def landing-offsets
  "Where to try when a landing will not do: the located point, then two rings of eight nearby
   points in the same biome, nearest first (a CI run needed seven tries around one pond)."
  (into [[0 0]]
        (for [r [24 48]
              [dx dz] [[1 0] [0 1] [-1 0] [0 -1] [1 1] [-1 1] [-1 -1] [1 -1]]]
          [(* r dx) (* r dz)])))

(def checks
  "How a fixture asks the server whether a landing will do: each is an `execute at <bot>` (or
   `execute positioned ...`) test, and a passing one names the reason to try elsewhere. Seen in
   CI before these existed: a pond (the heightmap counts water as surface), a log in an oak
   canopy (it skips leaves but not a trunk top), and inside stone (the heightmap was not ready)."
  [[:water "if block ~ ~ ~ minecraft:water"] [:water "if block ~ ~-1 ~ minecraft:water"]
   [:water "if block ~ ~1 ~ minecraft:water"]
   ;; the heightmap stops on sea plants, which are water to the physics (gym shore-w1 run 6 landed on one)
   [:water "if block ~ ~ ~ #minecraft:underwater_bonemeals"]
   [:water "if block ~ ~-1 ~ #minecraft:underwater_bonemeals"]
   [:lava "if block ~ ~-1 ~ minecraft:lava"] [:tree "if block ~ ~-1 ~ #minecraft:logs"]
   [:tree "if block ~ ~-1 ~ #minecraft:leaves"] [:tree "if block ~ ~ ~ #minecraft:leaves"]
   [:buried "unless block ~ ~ ~ #minecraft:replaceable"]
   [:buried "unless block ~ ~1 ~ #minecraft:replaceable"]])

(defn passed?
  "Did an `execute ... if/unless` test pass?"
  [reply]
  (str/includes? (str reply) "Test passed"))

(defn bad-landing
  "The first reason a landing will not do, from [[reason reply] ...] answers to `checks`, or nil."
  [answers]
  (some (fn [[reason reply]] (when (passed? reply) reason)) answers))

(defn parse-pos
  "[x y z] from a `data get entity <name> Pos` reply, or nil."
  [reply]
  (when-let [[_ x y z] (re-find #"\[(-?[\d.E-]+)d, (-?[\d.E-]+)d, (-?[\d.E-]+)d\]" (str reply))]
    (mapv #(Double/parseDouble %) [x y z])))

(defn parse-located
  "[x z] from a `locate` reply, or nil when nothing was found."
  [reply]
  (when-let [[_ x z] (re-find #"\[(-?\d+), (?:~|-?\d+), (-?\d+)\]" (str reply))]
    [(parse-long x) (parse-long z)]))

(defn on-surface
  "The command that runs cmd at the surface of column x z: the highest motion-blocking block
   that is not leaves (the heightmap the server keeps)."
  [x z cmd]
  (str "execute positioned " x " 0 " z " positioned over motion_blocking_no_leaves run " cmd))

(defn go-event
  "The one message a fixture sends the bot."
  [until at]
  (cond-> {:event/kind :go :go/goals (plan/goals-for until)} at (assoc :go/at at)))

;;;; RCON: everything below talks to the server ;;;;

(defn position
  "Where the server says the player stands, [x y z], or nil when it is not in the world."
  [rc name]
  (parse-pos (rcon/command! rc (str "data get entity " name " Pos"))))

(defn wait-online!
  "Block until the server knows the player (it is in the world), up to ms."
  [rc name ms]
  (let [until (+ (System/currentTimeMillis) ms)]
    (loop []
      (or (position rc name)
          (when (< (System/currentTimeMillis) until) (Thread/sleep 250) (recur))))))

(defn wait-loaded!
  "Block until the server reports the column at x z loaded, polling every 200 ms, up to 30 s;
   true when it loaded."
  [rc x z]
  (loop [i 0]
    (cond (passed? (rcon/command! rc (str "execute if loaded " x " 0 " z))) true
          (>= i 150) false
          :else (do (Thread/sleep 200) (recur (inc i))))))

(defn with-forceload
  "Call (f) with the chunks of coords forceloaded ([x z] for one column, [x0 z0 x1 z1] for a
   square), and release them afterwards even when f throws: a forceload left behind keeps its
   chunks ticking for every later run on the world. Returns what f returns."
  [rc coords f]
  (let [where (str/join " " coords)]
    (rcon/command! rc (str "forceload add " where))
    (try (f) (finally (rcon/command! rc (str "forceload remove " where))))))

(defn teleport!
  "Load the column at x z and teleport the bot onto its surface."
  [rc name [x z]]
  (with-forceload rc
                  [x z]
                  (fn []
                    (wait-loaded! rc x z)
                    (cli/log "tp:"
                             (rcon/command! rc (on-surface x z (str "tp " name " ~0.5 ~ ~0.5")))))))

(defn land-at!
  "Teleport the bot onto the surface at [x z] and judge the landing by the server's own answers
   (checks), trying the landing-offsets round it until one will do; returns where it stands, or
   nil when it is not in the world. The forest harness and the race land this way."
  [rc name [x z]]
  (loop [[[ox oz] & more] landing-offsets]
    (teleport! rc name [(+ x ox) (+ z oz)])
    (Thread/sleep (long settle-ms))
    (let [why (bad-landing (mapv (fn [[reason test]]
                                   [reason (rcon/command! rc (str "execute at " name " " test))])
                                 checks))]
      (if (and why (seq more))
        (do (cli/log "bad landing" why "- trying another spot") (recur more))
        (position rc name)))))

(defn locate-forest!
  "The nearest forest to column [x z], [x z], or nil."
  [rc [x z]]
  (parse-located
   (rcon/command! rc (str "execute positioned " x " 64 " z " run locate biome minecraft:forest"))))
