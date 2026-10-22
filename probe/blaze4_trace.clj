(ns probe.blaze4-trace
  (:require [clojure.math :as math]
            [clojurecraft.capture :as capture]
            [clojurecraft.sim :as sim]
            [clojurecraft.simrace :as sr]))

(defn r1 [v] (mapv #(/ (math/round (* 10 (double %))) 10.0) v))

(defn run
  [path t0]
  (let [snap (capture/read-world path)
        w (sr/world-for! snap)
        m (sr/moment-of snap w)
        last-id (atom nil)
        stop? (fn [world]
                (let [i (:plan/intent world)
                      id (:intent/id i)]
                  (when (not= id @last-id)
                    (reset! last-id id)
                    (println "I"
                             (:time/now world)
                             (long (/ (- (:time/now world) t0) 1000))
                             "s"
                             (:intent/kind i)
                             (:intent/target i)
                             (:intent/goal i)
                             (r1 (:player/pos world))
                             (:player/health world)))
                  false))]
    (println "==" path)
    (sim/play-from sr/step m stop? (sr/window-of snap) (sr/go-of w nil) nil)))

(run "test/issues/504/b10221245-R4-14-2.edn.gz" 19470000)
(println "done")
