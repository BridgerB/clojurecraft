(ns clojurecraft.forest-chunks
  "Every warped-forest chunk of a set's captured Nether (issue #263), one FOREST line each with the
   chunk's middle block and its nylium count, for the nearest-forest question of the stage-18
   search. A probe script: it runs on a runner (bb probe run), never the Mac.

     clojure -M:dev -m clojurecraft.forest-chunks R1"
  (:require [clojurecraft.blocks :as blocks]
            [clojurecraft.chunk :as chunk]
            [clojurecraft.memory :as memory]
            [clojurecraft.simrace :as simrace]))

(set! *warn-on-reflection* true)

(defn -main
  "Print a FOREST x z count line for every chunk of set s's Nether with forest-min or more warped nylium."
  [s]
  (let [files (simrace/world-files (str "cache/sim-worlds/" s))
        nether (simrace/read-nether (simrace/nether-file (first files)))]
    (doseq [[k col] (:decoded nether)
            :let [n (count (chunk/find-blocks {k col} #(= :warped_nylium (blocks/name-of %))))]
            :when (>= n memory/forest-min)]
      (println "FOREST" (+ 8 (* 16 (first k))) (+ 8 (* 16 (second k))) n))
    (shutdown-agents)))
