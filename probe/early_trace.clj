(ns probe.early-trace
  (:require [clojurecraft.capture :as capture]
            [clojurecraft.plan :as plan]
            [clojurecraft.scene :as scene]
            [clojurecraft.sim :as sim]
            [clojurecraft.simrace :as sr]))

(def ks
  [:intent/kind :intent/stage :intent/level-steps :intent/to-y :intent/for :intent/level-dir
   :intent/dir :intent/turns])

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
                    (println "TRACE"
                             t
                             (mapv #(format "%.1f" %) (:player/pos wd))
                             (pr-str (select-keys (scene/facts wd) [:held]))
                             (pr-str (select-keys (:plan/intent wd) ks))))
                  (or (plan/done? wd) (plan/failed? wd))))
        _
        (sim/play-from sr/step m stop? (sr/window-of snap) (sr/go-of w (:snapshot/until snap)) nil)]
    (println "TRACE-END")
    (shutdown-agents)))
