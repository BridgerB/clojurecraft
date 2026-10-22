(ns probe.early4-why
  (:require [clojurecraft.blocks :as blocks]
            [clojurecraft.capture :as capture]
            [clojurecraft.intent :as intent]
            [clojurecraft.plan :as plan]
            [clojurecraft.sim :as sim]
            [clojurecraft.simrace :as sr]
            [clojurecraft.stone :as stone]
            [clojurecraft.terrain :as terrain]))

(defn column
  [world x y z]
  (vec (for [yy (range (+ y 3) (- y 3) -1)]
         [yy
          (some-> (terrain/block-at world [x yy z])
                  blocks/name-of
                  name)])))

(defn -main
  [path]
  (let [snap (capture/read-world path)
        w (sr/world-for! snap)
        {:keys [world] :as m} (sr/moment-of snap w)
        seen (volatile! nil)
        stop? (fn [wd]
                (when (and (nil? @seen) (= :stairs-up (:intent/kind (:plan/intent wd))))
                  (vreset! seen wd))
                (or @seen (plan/done? wd) (plan/failed? wd)))
        _
        (sim/play-from sr/step m stop? (sr/window-of snap) (sr/go-of w (:snapshot/until snap)) nil)
        wd @seen]
    (println "WHY feet" (intent/feet wd))
    (println "WHY go-at" (:plan/go-at wd))
    (println "WHY boxed-at" (:plan/boxed-at wd) (:plan/boxed-route wd))
    (println "WHY adjoins" (stone/adjoins-boxed? wd (intent/feet wd)))
    (println "WHY walk-home" (stone/walk-home wd))
    (println "WHY blacklist" (:plan/blacklist wd))
    (doseq [t [[3711 21 3217] [3716 15 3217] [3716 14 3217] [3716 11 3217] [3716 10 3217]]]
      (println "WHY routable"
               t
               (intent/routable? wd {:intent/kind :walk :intent/target t :intent/for :retreat})))
    (doseq [[x y z] [[3716 10 3215] [3716 10 3216] [3716 10 3217] [3716 10 3218] [3716 10 3219]
                     [3716 10 3220] [3717 10 3220]]]
      (println "WHY col" [x y z] (column wd x y z)))
    (shutdown-agents)))
