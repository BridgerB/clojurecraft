(ns probe.castsite
  "Step each snapshot from its moment, printing what changes."
  (:require [clojurecraft.capture :as capture]
            [clojurecraft.intent :as intent]
            [clojurecraft.plan :as plan]
            [clojurecraft.sim :as sim]
            [clojurecraft.simrace :as simrace]))

(defn view
  [w]
  (let [i (:plan/intent w)
        c (:intent/child i)]
    [(mapv long (intent/feet w))
     (select-keys i [:intent/kind :intent/for :intent/stage :intent/dir :intent/status])
     (select-keys c
                  [:intent/kind :intent/for :intent/target :intent/status :intent/reason
                   :intent/stage]) (:plan/wait w)]))

(defn trace
  [path]
  (let [snap (capture/read-world path)
        w (simrace/world-for! snap)
        m (simrace/moment-of snap w)
        prev (volatile! nil)
        n (volatile! 0)
        stop? (fn [world]
                (let [v (view world)]
                  (when (and (not= v @prev) (< @n 300))
                    (vswap! n inc)
                    (println (:time/now world) (pr-str v)))
                  (vreset! prev v)
                  (or (plan/done? world) (plan/failed? world))))]
    (println "TRACE" path (pr-str (:snapshot/signature snap)))
    (sim/play-from simrace/step
                   m
                   stop?
                   (simrace/window-of snap)
                   (simrace/go-of w (:snapshot/until snap))
                   nil)))

(defn -main [& paths] (doseq [p paths] (trace p)) (shutdown-agents))
