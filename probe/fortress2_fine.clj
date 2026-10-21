(ns clojurecraft.fortress2-fine
  (:require [clojurecraft.capture :as capture]
            [clojurecraft.fortress2-kit :as kit]
            [clojurecraft.intent :as intent]
            [clojurecraft.sim :as sim]
            [clojurecraft.simrace :as simrace]))

(defn -main
  "Plays the snapshot at path from skip seconds on for seconds, with an iron pickaxe given,
   printing the feet, the step and its child every second."
  [path skip seconds]
  (let [snap (capture/read-world path)
        w (simrace/world-for! snap)
        m (simrace/moment-of snap w)
        m (-> m
              (update-in [:world :player/inventory] (partial kit/swap-slot 0))
              (update-in [:sim :sim/inv] (partial kit/swap-slot 36)))
        t0 (:t m)
        skip (* 1000 (Long/parseLong skip))
        last-s (volatile! -1)
        stop? (fn [world]
                (let [e (- (:time/now world) t0)
                      s (quot e 1000)]
                  (when (and (> e skip) (> s @last-s))
                    (vreset! last-s s)
                    (let [i (:plan/intent world)]
                      (println "FINE" s
                               "feet" (intent/feet world)
                               "move" (:intent/move i)
                               "taken" (:intent/taken i)
                               "child"
                               (some-> i
                                       :intent/child
                                       (select-keys [:intent/kind :intent/status :intent/dig-ms
                                                     :intent/tool :intent/reason])))))
                  false))]
    (sim/play-from simrace/step
                   m
                   stop?
                   (+ skip (* (Long/parseLong seconds) 1000))
                   (simrace/go-of w (:snapshot/until snap))
                   nil)
    nil))
