(ns clojurecraft.biomes-probe
  "Which floor each biome registry id has in a captured Nether (issue #263): for every column of
   the set's Nether, the block names at the cells of the 4x4x4 biome grid's ids, tallied by id. No
   name table exists (chunk/biome-at), so the ids are told apart by what they hold: crimson
   nylium for the crimson forest, warped nylium for the warped forest. A probe script: it runs on
   a runner (bb probe run), never the Mac.

     clojure -M:dev -m clojurecraft.biomes-probe R1"
  (:require [clojurecraft.blocks :as blocks]
            [clojurecraft.chunk :as chunk]
            [clojurecraft.simrace :as simrace]))

(set! *warn-on-reflection* true)

(def interesting
  #{:crimson_nylium :warped_nylium :soul_sand :soul_soil :basalt :blackstone :netherrack
    :nether_wart_block :warped_wart_block :magma_block :glowstone :nether_quartz_ore})

(defn -main
  "Print, for each biome id seen under an interesting block of the Nether of set s, the counts of those blocks."
  [s]
  (let [files (simrace/world-files (str "cache/sim-worlds/" s))
        npath (simrace/nether-file (first files))
        nether (simrace/read-nether npath)
        decoded (:decoded nether)
        tally (reduce (fn [m [x y z id]]
                        (let [nm (blocks/name-of id)
                              b (chunk/biome-at decoded [x y z])]
                          (if (and b (interesting nm)) (update-in m [b nm] (fnil inc 0)) m)))
                      {}
                      (chunk/find-blocks decoded #(contains? interesting (blocks/name-of %))))]
    (doseq [[b m] (sort tally)] (println "BIOME" b (pr-str (into (sorted-map) m))))
    (shutdown-agents)))
