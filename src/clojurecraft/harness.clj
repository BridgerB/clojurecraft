(ns clojurecraft.harness
  "The forest fixture, as its own process. It never touches the bot: it waits until the bot is
   online, finds a forest and lands the bot there using only RCON, judges the landing by asking
   the server, and then speaks to the bot through a queue - one EDN event on stdout, which the
   bot reads from stdin:

     clojure -M:harness --rcon-port 25575 --rcon-pass S --name Clj_wood --until wood \\
       | clojure -M:run --port 25565 --name Clj_wood --until wood --events stdin

   The event is {:event/kind :go :go/goals [...] :go/at [x y z]}; the bot's planner waits in
   :landing until it is at :go/at. Logs go to stderr. What it asks the server is
   clojurecraft.fixture's, shared with the gym."
  (:require [clojurecraft.cli :as cli]
            [clojurecraft.fixture :as fixture]
            [clojurecraft.rcon :as rcon])
  (:gen-class))

(set! *warn-on-reflection* true)

;;;; RCON: everything below talks to the server ;;;;

(defn locate-forest!
  "The nearest forest from the bot, [x z], or nil."
  [rc name]
  (rcon/command! rc (str "gamemode survival " name))
  (rcon/command! rc (str "clear " name))
  (let [r (rcon/command! rc (str "execute at " name " run locate biome minecraft:forest"))]
    (cli/log "locate:" r)
    (fixture/parse-located r)))

(defn land!
  "Land the bot in a forest on ground the fixture can use; returns where it stands, or nil."
  [rc name]
  (when-let [[fx fz] (locate-forest! rc name)]
    (loop [[[ox oz] & more] fixture/landing-offsets]
      (fixture/teleport! rc name [(+ fx ox) (+ fz oz)])
      (Thread/sleep (long fixture/settle-ms))
      (let [why (fixture/bad-landing (mapv (fn [[reason test]]
                                             [reason
                                              (rcon/command! rc (str "execute at " name " " test))])
                                           fixture/checks))]
        (if (and why (seq more))
          (do (cli/log "bad landing" why "- trying another spot") (recur more))
          (fixture/position rc name))))))

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
                                    (when-not (fixture/wait-online! rc bot fixture/online-timeout)
                                      (throw (ex-info "bot never came online" {:name bot})))
                                    (land! rc bot)))]
           (cli/log "landed at" at)
           (prn (fixture/go-event until at)))
         (catch Exception e (cli/log "harness failed:" e) (prn (fixture/go-event until nil))))
    (flush)
    (shutdown-agents)))
