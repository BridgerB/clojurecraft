(require '[clojurecraft.record :as record]
         '[clojurecraft.main :as main]
         '[clojurecraft.game :as game]
         '[clojurecraft.wood :as wood]
         '[clojurecraft.memory :as memory]
         '[clojurecraft.intent :as intent]
         '[clojurecraft.terrain :as terrain]
         '[clojurecraft.stairs :as stairs]
         '[clojurecraft.blocks :as blocks]
         '[clojurecraft.physics :as physics]
         '[clojure.java.io :as io]
         '[clojure.string :as str])
(.mkdirs (io/file "out"))
(defn out
  "One line on stdout and into out/probe.log."
  [& xs]
  (let [s (str/join " " xs)]
    (println s)
    (spit "out/probe.log" (str s "\n") :append true)))
(doseq [p *command-line-args*]
  (let [w (record/replay main/step (game/init {:host "replay" :port 0 :name "Clj_replay"}) p)
        feet (intent/feet w)
        eye (game/eye w)
        logs (memory/positions-now w blocks/log-states)
        trunks (->> logs
                    (remove (wood/skipped w))
                    (filter #(wood/standing-trunk? w %))
                    (memory/nearest-first eye wood/far-radius))]
    (out "==" p)
    (out " feet" feet "pos" (:player/pos w) "eye" eye)
    (out " plan"
         "reason" (:plan/reason w)
         "waiting" (:plan/waiting w)
         "attempts" (:plan/attempts w)
         "boxed-at" (:plan/boxed-at w))
    (out " under-sky?" (terrain/under-sky? w feet)
         "out?" (stairs/out? w feet)
         "pit-rim" (stairs/pit-rim w))
    (out " logs-remembered" (count logs) "standing-trunks-within-256" (count trunks))
    (doseq [t (take 6 trunks)]
      (out "  trunk" t
           "dist" (long (physics/horizontal-distance eye t))
           "target" (wood/trunk-target w t)))
    (doseq [t (take 4 (remove #(wood/standing-trunk? w %) logs))]
      (out "  non-trunk-log" t "dist" (long (physics/horizontal-distance eye t))))
    (out " near-walk" (wood/near-walk w))
    (out " climb-out" (wood/climb-out w))
    (out " hunt" (wood/hunt w))
    (out " gather-next" (wood/gather-next w))
    (out " last-intentions")
    (doseq [i (take 12 (reverse (memory/intentions w)))]
      (out "  "
           (select-keys i
                        [:intention/event :intention/kind :intention/target :intention/reason
                         :intention/at]))))
  (out ""))
(shutdown-agents)
