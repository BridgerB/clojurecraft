(ns kit.r4
  (:require [clojurecraft.capture :as capture]
            [clojurecraft.intent :as intent]
            [clojurecraft.place :as place]
            [clojurecraft.sim :as sim]
            [clojurecraft.simrace :as sr]
            [clojurecraft.stone :as stone]))

(defn -main
  "Prints, tick by tick from the snapshot's moment, the intent with its child, and what the placing planners answer."
  [& [path]]
  (let [snap (capture/read-world path)
        mom (sr/moment-of snap (sr/world-for! snap))
        w0 (:world mom)]
    (println "REDIG-PLACE" (pr-str (stone/redig-place w0 :gravel)))
    (println "FRESH-ROCK" (pr-str (place/fresh-rock w0)))
    (println "FEET" (intent/feet w0) "inwater" (:player/in-water? w0))
    (doseq [ms (range 50 1500 50)]
      (let [wd (:world (sim/play-from sr/step mom (constantly false) ms nil nil))]
        (println "T" ms (pr-str (:plan/intent wd)) (:player/controls wd))))
    (shutdown-agents)))
