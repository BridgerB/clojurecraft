(ns clojurecraft.fortress2-fine
  (:require [clojurecraft.capture :as capture]
            [clojurecraft.intent :as intent]
            [clojurecraft.sim :as sim]
            [clojurecraft.simrace :as simrace]))

(defn -main
  "Plays the snapshot at path for seconds, printing the feet and the tunnel's choice every second."
  [path seconds]
  (let [snap (capture/read-world path)
        w (simrace/world-for! snap)
        m (simrace/moment-of snap w)
        t0 (:t m)
        last-s (volatile! -1)
        stop? (fn [world]
                (let [s (quot (- (:time/now world) t0) 1000)]
                  (when (> s @last-s)
                    (vreset! last-s s)
                    (let [i (:plan/intent world)]
                      (println "FINE" s
                               "feet" (intent/feet world)
                               "move" (:intent/move i)
                               "from" (:intent/from i)
                               "retreats" (:intent/retreats i)
                               "taken" (:intent/taken i)
                               "status" (:intent/status i)
                               "child"
                               (some-> i
                                       :intent/child
                                       (select-keys [:intent/kind :intent/status :intent/reason]))
                               "visited" (count (:intent/tunnel-visited i)))))
                  false))]
    (sim/play-from simrace/step
                   m
                   stop?
                   (* (Long/parseLong seconds) 1000)
                   (simrace/go-of w (:snapshot/until snap))
                   nil)
    nil))
