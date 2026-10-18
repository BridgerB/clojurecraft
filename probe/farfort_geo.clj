(ns probe.farfort-geo
  "Issue #338: for each world of a set, where its arrival in the Nether is and where the fortress
   bricks lie, from the arrival: distance, bearing, and the region's edges."
  (:require [clojure.java.io :as io]
            [clojure.math :as math]
            [clojurecraft.capture :as capture]
            [clojurecraft.capture-nether :as cn]
            [clojurecraft.chunk :as chunk]
            [clojurecraft.sim :as sim]
            [clojurecraft.simrace :as sr]))

(defn -main
  "set-dir: print one GEO line a world: arrival, region, brick count, the nearest three clusters."
  [dir]
  (let [files (sort-by #(Long/parseLong (re-find #"\d+" (.getName ^java.io.File %)))
                       (filter #(re-find #"\d+\.edn\.gz$" (.getName ^java.io.File %))
                               (.listFiles (io/file dir))))
        nf (sr/nether-file (str (first files)))
        raw (capture/read-world nf)
        decoded (update-vals (:nether/columns raw) chunk/decode)
        found (vec (cn/bricks decoded))
        [cx0 cz0 cx1 cz1] (:nether/region raw)
        cell (fn [[x _ z]] [(math/floor-div (long x) 64) (math/floor-div (long z) 64)])
        clusters (group-by cell found)]
    (println "REGION" [(* 16 cx0) (* 16 cz0) (* 16 (inc cx1)) (* 16 (inc cz1))]
             "bricks" (count found))
    (println "BRICKCELLS64" (sort (map (fn [[k v]] [k (count v)]) clusters)))
    (doseq [f files]
      (let [w (capture/read-world (str f))
            land (:capture/landing w)
            arr (sim/arrival decoded [(first land) (nth land 2) (second land)])
            [ax ay az] (:at arr)
            ax (long (math/floor ax))
            az (long (math/floor az))
            near (->> found
                      (map (fn [[x y z :as b]] [(long (math/hypot (- x ax) (- z az))) (- x ax)
                                                (- z az) y b]))
                      (sort-by first)
                      (take 1))]
        (println "GEO" (.getName ^java.io.File f)
                 "land" land
                 "arrive" [ax (long ay) az]
                 "toEdge"
                 {:w (- ax (* 16 cx0))
                  :e (- (* 16 (inc cx1)) ax)
                  :n (- az (* 16 cz0))
                  :s (- (* 16 (inc cz1)) az)}
                 "nearest" (mapv #(subvec % 0 4) near))))
    (shutdown-agents)))
