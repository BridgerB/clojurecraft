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
            [clojurecraft.cli :as cli]
            [clojurecraft.plan :as plan]
            [clojurecraft.rcon :as rcon])
  (:gen-class))

(def online-timeout 120000)           ; ms the fixture waits for the bot to join
(def settle-ms 1000)                  ; after a teleport, before asking about the landing

(def landing-offsets
  "Where to try when a landing will not do: the located point, then two rings of eight nearby
   points in the same forest, nearest first (a CI run needed seven tries around one pond)."
  (into [[0 0]]
        (for [r [24 48]
              [dx dz] [[1 0] [0 1] [-1 0] [0 -1] [1 1] [-1 1] [-1 -1] [1 -1]]]
          [(* r dx) (* r dz)])))

(def checks
  "How the fixture asks the server whether a landing will do: each is an `execute at <bot>`
   test, and a passing one names the reason to try elsewhere. Seen in CI before these existed:
   a pond (the heightmap counts water as surface), a log in an oak canopy (it skips leaves but
   not a trunk top), and inside stone (the heightmap was not ready)."
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

(defn go-event
  "The one message the fixture sends the bot."
  [until at]
  (cond-> {:event/kind :go :go/goals (plan/goals-for until)} at (assoc :go/at at)))

;;;; RCON: everything below talks to the server ;;;;

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
    (cli/log "locate:" r)
    (when fx [(Long/parseLong fx) (Long/parseLong fz)])))

(defn wait-loaded!
  "Block until the server reports the column at x z loaded, polling every 200 ms, up to 30 s;
   true when it loaded."
  [rc x z]
  (loop [i 0]
    (cond (passed? (rcon/command rc (str "execute if loaded " x " 0 " z))) true
          (>= i 150) false
          :else (do (Thread/sleep 200) (recur (inc i))))))

(defn with-forceload
  "Call (f) with the chunks of coords forceloaded ([x z] for one column, [x0 z0 x1 z1] for a
   square), and release them afterwards even when f throws: a forceload left behind keeps its
   chunks ticking for every later run on the world. Returns what f returns."
  [rc coords f]
  (let [where (str/join " " coords)]
    (rcon/command rc (str "forceload add " where))
    (try (f) (finally (rcon/command rc (str "forceload remove " where))))))

(defn teleport!
  "Load the column at x z and teleport the bot onto its surface."
  [rc name [x z]]
  (with-forceload rc
                  [x z]
                  (fn []
                    (wait-loaded! rc x z)
                    (cli/log "tp:"
                             (rcon/command rc
                                           (str "execute positioned "
                                                x
                                                " 0 "
                                                z
                                                " positioned over motion_blocking_no_leaves run tp "
                                                name
                                                " ~0.5 ~ ~0.5"))))))

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
          (do (cli/log "bad landing" why "- trying another spot") (recur more))
          (position rc name))))))

(defn -main
  "clojure -M:harness [--host H] --rcon-port P --rcon-pass S --name N --until U"
  [& args]
  (let [{:keys [host rcon-port rcon-pass until] :as opts} (cli/options args)
        bot (:name opts)
        host (or host "127.0.0.1")
        until (or until "wood")]
    (try (let [at (rcon/with-rcon host
                                  (Long/parseLong rcon-port)
                                  rcon-pass
                                  (fn [rc]
                                    (when-not (wait-online rc bot online-timeout)
                                      (throw (ex-info "bot never came online" {:name bot})))
                                    (land! rc bot)))]
           (cli/log "landed at" at)
           (prn (go-event until at)))
         (catch Throwable e (cli/log "harness failed:" e) (prn (go-event until nil))))
    (flush)
    (shutdown-agents)))
