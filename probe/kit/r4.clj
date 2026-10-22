(ns kit.r4
  (:require [clojure.math :as math]
            [clojurecraft.capture :as capture]
            [clojurecraft.sim :as sim]
            [clojurecraft.simrace :as sr]
            [clojurecraft.terrain :as terrain]))

(defn -main
  "Prints where the bot is and what it does at growing times after the snapshot's moment."
  [& [path]]
  (let [snap (capture/read-world path)
        mom (sr/moment-of snap (sr/world-for! snap))]
    (doseq [ms [1000 5000 20000 60000 200000]]
      (let [wd (:world (sim/play-from sr/step mom (constantly false) ms nil nil))
            [x y z] (mapv #(long (math/floor %)) (:player/pos wd))]
        (println "T" ms
                 "pos" (:player/pos wd)
                 "ground" (:player/on-ground? wd)
                 "ctl" (:player/controls wd)
                 "intent" (pr-str (:plan/intent wd))
                 "below" (terrain/block-at wd [x (dec y) z])
                 "at" (terrain/block-at wd [x y z])
                 "plan" (select-keys wd [:plan/status :plan/wait :plan/failures]))))
    (shutdown-agents)))
