(ns probe.moment-trace
  "A snapshot's moment played on, the running intent printed every few simulated seconds."
  (:require [clojurecraft.capture :as capture]
            [clojurecraft.plan :as plan]
            [clojurecraft.scene :as scene]
            [clojurecraft.sim :as sim]
            [clojurecraft.simrace :as sr]))

(defn brief
  "The small values of an intent, children too."
  [i]
  (when i
    (-> (into {}
              (filter (fn [[_ v]]
                        (or (keyword? v)
                            (number? v)
                            (boolean? v)
                            (and (vector? v) (< (count v) 5) (every? number? v)))))
              i)
        (assoc :child (brief (:intent/child i))))))

(defn -main
  "snapshot seconds every-s: play the snapshot's moment on for seconds, print the bot each every-s."
  [path seconds every]
  (let [snap (capture/read-world path)
        w (sr/world-for! snap)
        m (sr/moment-of snap w)
        t0 (:t m)
        every (* 1000 (Long/parseLong every))
        last-at (volatile! -1)
        stop? (fn [world]
                (let [k (quot (- (:time/now world) t0) every)]
                  (when (not= k @last-at)
                    (vreset! last-at k)
                    (println "AT"
                             (quot (- (:time/now world) t0) 1000)
                             (mapv #(/ (long (* 10.0 %)) 10.0) (:player/pos world))
                             (pr-str (brief (:plan/intent world)))
                             (pr-str (select-keys world
                                                  [:plan/status :plan/attempts :plan/boxed-at])))
                    (when (zero? k) (doseq [l (scene/slice world)] (println "SLICE" l))))
                  (or (plan/done? world) (plan/failed? world))))
        r (sim/play-from sr/step
                         m
                         stop?
                         (* 1000 (Long/parseLong seconds))
                         (sr/go-of w (:snapshot/until snap))
                         nil)]
    (println "END"
             (:plan/status (:world r))
             (:plan/reason (:world r))
             (pr-str (brief (:plan/last (:world r)))))
    (shutdown-agents)))
