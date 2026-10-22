(ns probe.early-trace
  (:require [clojurecraft.capture :as capture]
            [clojurecraft.plan :as plan]
            [clojurecraft.scene :as scene]
            [clojurecraft.sim :as sim]
            [clojurecraft.simrace :as sr]))

(defn -main
  [path every-ms]
  (let [snap (capture/read-world path)
        w (sr/world-for! snap)
        {:keys [world] :as m} (sr/moment-of snap w)
        last-t (volatile! 0)
        every (parse-long every-ms)
        stop? (fn [wd]
                (let [t (:time/now wd)]
                  (when (>= (- t @last-t) every)
                    (vreset! last-t t)
                    (let [f (scene/facts wd)]
                      (println "TRACE"
                               (- t (:time/now world))
                               (pr-str (select-keys f [:pos :held :intent])))))
                  (or (plan/done? wd) (plan/failed? wd))))
        _
        (sim/play-from sr/step m stop? (sr/window-of snap) (sr/go-of w (:snapshot/until snap)) nil)]
    (println "TRACE-END")
    (shutdown-agents)))
