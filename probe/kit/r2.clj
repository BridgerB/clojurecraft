(ns kit.r2
  (:require [clojurecraft.capture :as capture]
            [clojurecraft.hunt :as hunt]
            [clojurecraft.intent :as intent]
            [clojurecraft.path :as path]
            [clojurecraft.sim :as sim]
            [clojurecraft.simrace :as sr]))

(defn -main
  "Prints, at chosen times from the moment, the route to the sheep and what the hunt makes of it."
  [& [path]]
  (let [snap (capture/read-world path)
        mom (sr/moment-of snap (sr/world-for! snap))]
    (doseq [ms [36000 46000 48000 50000 58000]]
      (let [wd (:world (sim/play-from sr/step mom (constantly false) ms nil nil))
            e (get-in wd [:world/entities 218])
            r (when e
                (path/plan wd
                           (path/feet-cell (:player/pos wd))
                           (intent/goal-of {:intent/kind :walk
                                            :intent/target (hunt/cell (:entity/pos e))})))]
        (println "T" ms
                 "pos" (:player/pos wd)
                 "e" (:entity/pos e)
                 "status" (:path/status r)
                 "end" (last (:path/waypoints r))
                 "n" (count (:path/waypoints r))
                 "fightable" (when e (hunt/fightable-from-route? wd e))
                 "game" (first (hunt/game wd)))))
    (shutdown-agents)))
