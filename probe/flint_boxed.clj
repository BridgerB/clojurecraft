(ns probe.flint-boxed
  "Issue #105: why every stair up from the flooded cave is refused. Plays each snapshot to its end
   and prints, per heading, each predicate stairs-up asks."
  (:require [clojurecraft.capture :as capture]
            [clojurecraft.intent :as intent]
            [clojurecraft.path :as path]
            [clojurecraft.place :as place]
            [clojurecraft.plan :as plan]
            [clojurecraft.sim :as sim]
            [clojurecraft.simrace :as simrace]
            [clojurecraft.stairs :as stairs]
            [clojurecraft.terrain :as terrain]))

(defn end-world
  [path]
  (let [snap (capture/read-world path)
        w (simrace/world-for! snap)
        m (simrace/moment-of snap w)
        {world' :world} (sim/play-from simrace/step
                                       m
                                       #(or (plan/done? %) (plan/failed? %))
                                       (simrace/window-of snap)
                                       (simrace/go-of w (:snapshot/until snap))
                                       nil)]
    world'))

(defn report
  [path]
  (let [world (end-world path)
        feet (intent/feet world)]
    (println "PROBE" path
             "feet" feet
             "in-water?" (:player/in-water? world)
             "feet-fluid" (terrain/fluid-kind world feet)
             "boxed-at" (:plan/boxed-at world))
    (println "PROBE spot" (take 1 (place/spot world true #{})))
    (println "PROBE away"
             (:path/status (path/plan world
                                      (path/feet-cell (:player/pos world))
                                      {:goal/kind :away :goal/pos feet :goal/range 8})))
    (doseq [dir stairs/directions
            :let [cells (stairs/up-cells feet dir)]]
      (println "PROBE dir" dir
               "cells" cells
               "classes" (mapv #(path/classify world %) cells)
               "clear" (stairs/clear-way? world cells)
               "floor" (path/classify world (let [[x y z] (last cells)] [x (dec y) z]))
               "falls" (stairs/falls-over? world cells)
               "exposes" (mapv #(intent/exposes-fluid? world %) cells)
               "water-beside" (stairs/water-beside world cells)
               "water-over" (stairs/water-over? world cells)
               "sealed" (stairs/up-sealed? world cells)
               "wet-feet" (stairs/wet-feet world cells)
               "seal" (stairs/seal world feet cells)
               "dry-feet" (stairs/dry-feet world feet cells)
               "step-lay" (stairs/step-lay world feet cells)
               "up-dry" (stairs/up-dry? world cells)
               "up-lay" (stairs/up-lay world feet cells)))))

(doseq [p ["test/issues/105/b10180130-R1-31-1.edn.gz" "test/issues/105/b10180130-R3-44-2.edn.gz"]]
  (report p))
