(ns clojurecraft.harness
  "The test fixture, as its own process. It never touches the bot: it waits until the bot is
   online, finds a forest and lands the bot there using only RCON, judges the landing by asking
   the server, and then speaks to the bot through a queue - one EDN event on stdout, which the
   bot reads from stdin:

     clojure -M:harness --rcon-port 25575 --rcon-pass S --name Clj_wood --until wood \\
       | clojure -M:run --port 25565 --name Clj_wood --until wood --events stdin

   The event is {:event/kind :go :go/goals [...] :go/at [x y z]}; the bot's planner waits in
   :landing until it is at :go/at. Logs go to stderr."
  (:require [clojure.string :as str]
            [clojurecraft.plan :as plan]
            [clojurecraft.rcon :as rcon])
  (:gen-class))

(def online-timeout 120000)           ; ms the fixture waits for the bot to join
(def settle-ms 1000)                  ; after a teleport, before asking about the landing

(def landing-offsets
  "Where to try when a landing will not do: the located point, then two rings of eight nearby
   points in the same forest, nearest first (a CI run needed seven tries around one pond)."
  (into [[0 0]] (for [r [24 48] [dx dz] [[1 0] [0 1] [-1 0] [0 -1] [1 1] [-1 1] [-1 -1] [1 -1]]]
                  [(* r dx) (* r dz)])))

(def checks
  "How the fixture asks the server whether a landing will do: each is an `execute at <bot>`
   test, and a passing one names the reason to try elsewhere. Seen in CI before these existed:
   a pond (the heightmap counts water as surface), a log in an oak canopy (it skips leaves but
   not a trunk top), and inside stone (the heightmap was not ready)."
  [[:water "if block ~ ~ ~ minecraft:water"]
   [:water "if block ~ ~-1 ~ minecraft:water"]
   [:lava "if block ~ ~-1 ~ minecraft:lava"]
   [:tree "if block ~ ~-1 ~ #minecraft:logs"]
   [:tree "if block ~ ~-1 ~ #minecraft:leaves"]
   [:tree "if block ~ ~ ~ #minecraft:leaves"]
   [:buried "unless block ~ ~ ~ #minecraft:replaceable"]
   [:buried "unless block ~ ~1 ~ #minecraft:replaceable"]])

(defn passed? "Did an `execute ... if/unless` test pass?" [reply] (str/includes? (str reply) "Test passed"))

(defn bad-landing
  "The first reason a landing will not do, from [[reason reply] ...] answers to `checks`, or nil."
  [answers]
  (some (fn [[reason reply]] (when (passed? reply) reason)) answers))

(defn parse-pos
  "[x y z] from a `data get entity <name> Pos` reply, or nil."
  [reply]
  (when-let [[_ x y z] (re-find #"\[(-?[\d.E-]+)d, (-?[\d.E-]+)d, (-?[\d.E-]+)d\]" (str reply))]
    (mapv #(Double/parseDouble %) [x y z])))

(defn go-event
  "The one message the fixture sends the bot."
  [until at]
  (cond-> {:event/kind :go :go/goals (plan/goals-for until)} at (assoc :go/at at)))

;;;; RCON: everything below talks to the server ;;;;

(defn log
  "One timestamped line on stderr; stdout is reserved for the event the bot reads."
  [& xs]
  (binding [*out* *err*] (println (str (java.time.LocalTime/now)) (str/join " " xs)) (flush)))

(defn position
  "Where the server says the player stands, [x y z], or nil when it is not in the world."
  [rc name]
  (parse-pos (rcon/command rc (str "data get entity " name " Pos"))))

(defn wait-online
  "Block until the server knows the player (it is in the world), up to ms."
  [rc name ms]
  (let [until (+ (System/currentTimeMillis) ms)]
    (loop []
      (or (position rc name)
          (when (< (System/currentTimeMillis) until) (Thread/sleep 250) (recur))))))

(defn locate-forest
  "The nearest forest from the bot, [x z], or nil."
  [rc name]
  (rcon/command rc (str "gamemode survival " name))
  (rcon/command rc (str "clear " name))
  (let [r (rcon/command rc (str "execute at " name " run locate biome minecraft:forest"))
        [_ fx fz] (re-find #"\[(-?\d+), (?:~|-?\d+), (-?\d+)\]" r)]
    (log "locate:" r)
    (when fx [(Long/parseLong fx) (Long/parseLong fz)])))

(defn teleport!
  "Load the column at x z and teleport the bot onto its surface."
  [rc name [x z]]
  (rcon/command rc (str "forceload add " x " " z))
  (loop [i 0]
    (let [r (rcon/command rc (str "execute if loaded " x " 0 " z))]
      (when (and (not (passed? r)) (< i 150))
        (Thread/sleep 200)
        (recur (inc i)))))
  (log "tp:" (rcon/command rc (str "execute positioned " x " 0 " z
                                   " positioned over motion_blocking_no_leaves run tp " name " ~0.5 ~ ~0.5")))
  (rcon/command rc (str "forceload remove " x " " z)))

(defn land!
  "Land the bot in a forest on ground the fixture can use; returns where it stands, or nil."
  [rc name]
  (when-let [[fx fz] (locate-forest rc name)]
    (loop [[[ox oz] & more] landing-offsets]
      (teleport! rc name [(+ fx ox) (+ fz oz)])
      (Thread/sleep (long settle-ms))
      (let [why (bad-landing (for [[reason test] checks]
                               [reason (rcon/command rc (str "execute at " name " " test))]))]
        (if (and why (seq more))
          (do (log "bad landing" why "- trying another spot") (recur more))
          (position rc name))))))

(defn -main
  "clojure -M:harness [--host H] --rcon-port P --rcon-pass S --name N --until U"
  [& args]
  (let [{:strs [--host --rcon-port --rcon-pass --name --until]} (apply hash-map args)
        host (or --host "127.0.0.1")
        until (or --until "wood")]
    (try
      (let [at (rcon/with-rcon host (Long/parseLong --rcon-port) --rcon-pass
                 (fn [rc]
                   (when-not (wait-online rc --name online-timeout) (throw (ex-info "bot never came online" {:name --name})))
                   (land! rc --name)))]
        (log "landed at" at)
        (prn (go-event until at)))
      (catch Throwable e
        (log "harness failed:" e)
        (prn (go-event until nil))))
    (flush)
    (shutdown-agents)))
