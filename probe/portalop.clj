(ns probe.portalop
  "Play each snapshot to its end and print, from the last world that still ran a build, the op it
   worked, the child that op gets and the blocks round the feet."
  (:require [clojurecraft.build :as build]
            [clojurecraft.capture :as capture]
            [clojurecraft.intent :as intent]
            [clojurecraft.plan :as plan]
            [clojurecraft.scene :as scene]
            [clojurecraft.sim :as sim]
            [clojurecraft.simrace :as simrace]))

(defn explain
  "Print the op, its child and the slice for the build intent i at world."
  [world]
  (let [i (:plan/intent world)
        p (build/plan-for (:intent/build i)
                          (:intent/origin i)
                          (:intent/facing i)
                          (:intent/access i 0))
        op (build/next-op world p)]
    (println "BUILD" (pr-str (dissoc i :intent/child)))
    (println "FEET" (pr-str (intent/feet world)) "pos" (pr-str (:player/pos world)))
    (println "OP" (pr-str op))
    (println "UNSATISFIED"
             (pr-str (mapv (juxt :build/step :build/op :build/pos)
                           (remove #(build/satisfied? world %) p))))
    (println "CHILD" (pr-str (build/op-child world i op)))
    (doseq [line (scene/slice world)] (println "  SLICE" line))))

(defn trace
  "Play the snapshot at path and explain the last build world."
  [path]
  (let [snap (capture/read-world path)
        w (simrace/world-for! snap)
        m (simrace/moment-of snap w)
        last-build (volatile! nil)
        stop? (fn [world]
                (when (= :build (get-in world [:plan/intent :intent/kind]))
                  (vreset! last-build world))
                (or (plan/done? world) (plan/failed? world)))]
    (println "TRACE" path (pr-str (:snapshot/signature snap)))
    (sim/play-from simrace/step
                   m
                   stop?
                   (simrace/window-of snap)
                   (simrace/go-of w (:snapshot/until snap))
                   nil)
    (when @last-build (explain @last-build))))

(defn -main [& paths] (doseq [p paths] (trace p)) (shutdown-agents))
