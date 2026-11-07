(ns clojurecraft.forest-reach
  "Can a warped forest be reached at all in the sim's captured Nethers (issue #263)? For every
   world of the sets given, the start the checkpoint run uses (simrace/with-fortress) and the
   nearest chunk of warped nylium (memory/forest-min or more blocks) in that set's captured
   Nether, by the horizontal distance from the start. A probe script: it runs on a runner
   (bb probe run), never the Mac.

     clojure -M:dev -m clojurecraft.forest-reach R1 R2 R3 R4"
  (:require [clojure.java.io :as io]
            [clojure.math :as math]
            [clojure.pprint :as pp]
            [clojurecraft.blocks :as blocks]
            [clojurecraft.capture :as capture]
            [clojurecraft.chunk :as chunk]
            [clojurecraft.memory :as memory]
            [clojurecraft.simrace :as simrace]))

(set! *warn-on-reflection* true)

(defn forest-chunks
  "The [cx cz count] of every chunk of decoded (a map of [cx cz] to column) with forest-min or more
   warped nylium blocks."
  [decoded]
  (into []
        (keep (fn [[k col]]
                (let [n (count (chunk/find-blocks {k col} #(= :warped_nylium (blocks/name-of %))))]
                  (when (>= n memory/forest-min) [(first k) (second k) n]))))
        decoded))

(defn dist
  "The horizontal distance in blocks from [x z] to the middle of chunk [cx cz]."
  [[x _ z] [cx cz _]]
  (let [dx (- (+ (* 16 cx) 8) x) dz (- (+ (* 16 cz) 8) z)] (math/sqrt (+ (* dx dx) (* dz dz)))))

(defn median
  "The median of the numbers in xs, nil when empty."
  [xs]
  (when (seq xs) (nth (sort xs) (quot (count xs) 2))))

(defn -main
  "Print, per set, the Nether's extent and forest chunk count and, per world, the distance from its start to the nearest forest."
  [& sets]
  (doseq [s sets]
    (let [files (simrace/world-files (str "cache/sim-worlds/" s))
          npath (simrace/nether-file (first files))
          nether (when (.exists (io/file npath)) (simrace/read-nether npath))
          decoded (:decoded nether)
          cols (keys decoded)
          xs (map first cols)
          zs (map second cols)
          forests (when decoded (forest-chunks decoded))]
      (println "SET" s
               "worlds" (count files)
               "nether-chunks" (count cols)
               "extent-chunks"
               (when (seq cols) [(apply min xs) (apply max xs) (apply min zs) (apply max zs)])
               "forest-chunks" (count forests))
      (when nether
        (let [rows (for [f files
                         :let [w (-> (simrace/prepared (capture/read-world f))
                                     (assoc :capture/file (str f))
                                     (simrace/with-nether nether npath)
                                     (simrace/with-fortress {:blaze_rod 7}))
                               start (get-in w [:capture/fortress :spawn])]
                         :when start
                         :let [ds (map #(dist start %) forests)]]
                     {:world (str s "/" (.getName ^java.io.File f))
                      :start (mapv long start)
                      :nearest (some-> (seq ds)
                                       (->> (apply min)
                                            long))
                      :within-100 (count (filter #(<= % 100) ds))
                      :within-200 (count (filter #(<= % 200) ds))})
              ns (keep :nearest rows)]
          (println "SUMMARY" s
                   "with-start" (count rows)
                   "no-forest" (count (remove :nearest rows))
                   "median-nearest" (median ns)
                   "max-nearest" (when (seq ns) (apply max ns))
                   "within-100" (count (filter #(<= % 100) ns))
                   "within-200" (count (filter #(<= % 200) ns))
                   "within-400" (count (filter #(<= % 400) ns)))
          (doseq [r rows] (println "WORLD" (pr-str r)))))))
  (pp/pprint :done)
  (shutdown-agents))
