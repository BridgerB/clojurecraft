(ns probe.portalwalk
  "Play each snapshot from its moment and, at each failed child of the build, print the child, its
   route, the route planned again from the feet, and the blocks round the feet."
  (:require [clojurecraft.capture :as capture]
            [clojurecraft.intent :as intent]
            [clojurecraft.path :as path]
            [clojurecraft.plan :as plan]
            [clojurecraft.scene :as scene]
            [clojurecraft.sim :as sim]
            [clojurecraft.simrace :as simrace]))

(defn failure
  "Print the failed child of the running intent of world."
  [world]
  (let [i (:plan/intent world)
        c (:intent/child i)
        feet (path/feet-cell (:player/pos world))
        r (when (= :walk (:intent/kind c)) (path/plan world feet (intent/goal-of c)))]
    (println "FAIL"
             (:time/now world)
             (pr-str (intent/feet world))
             (pr-str (dissoc c :intent/waypoints)))
    (println "  ROUTE at" (:intent/at c) "of" (pr-str (:intent/waypoints c)))
    (when r
      (println "  REPLAN"
               (pr-str (select-keys r [:path/status :path/cost]))
               (pr-str (:path/waypoints r))))
    (doseq [line (scene/slice world)] (println "  SLICE" line))))

(defn trace
  "Play the snapshot at path, printing each failure of a child."
  [path]
  (let [snap (capture/read-world path)
        w (simrace/world-for! snap)
        m (simrace/moment-of snap w)
        seen (volatile! #{})
        stop? (fn [world]
                (let [c (get-in world [:plan/intent :intent/child])
                      k [(:time/now world) (:intent/kind c)]]
                  (when (and (intent/failed? c) (not (contains? @seen k)))
                    (vswap! seen conj k)
                    (failure world))
                  (or (plan/done? world) (plan/failed? world))))]
    (println "TRACE" path (pr-str (:snapshot/signature snap)))
    (sim/play-from simrace/step
                   m
                   stop?
                   (simrace/window-of snap)
                   (simrace/go-of w (:snapshot/until snap))
                   nil)))

(defn -main [& paths] (doseq [p paths] (trace p)) (shutdown-agents))
