(ns probe.early-trace
  (:require [clojurecraft.capture :as capture]
            [clojurecraft.plan :as plan]
            [clojurecraft.sim :as sim]
            [clojurecraft.simrace :as sr]))

(defn -main
  [path every-ms from-ms]
  (let [snap (capture/read-world path)
        w (sr/world-for! snap)
        {:keys [world] :as m} (sr/moment-of snap w)
        last-t (volatile! 0)
        every (parse-long every-ms)
        from (parse-long from-ms)
        stop? (fn [wd]
                (let [t (- (:time/now wd) (:time/now world))]
                  (when (and (>= t from) (>= (- t @last-t) every))
                    (vreset! last-t t)
                    (println "TRACE" t (pr-str (:player/pos wd)) (pr-str (:plan/intent wd))))
                  (or (plan/done? wd) (plan/failed? wd))))
        _
        (sim/play-from sr/step m stop? (sr/window-of snap) (sr/go-of w (:snapshot/until snap)) nil)]
    (println "TRACE-END")
    (shutdown-agents)))
