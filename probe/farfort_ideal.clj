(ns probe.farfort-ideal
  "Issue #338: how far a searcher walks before a fortress brick is in view, in the real bricks of
   a set's captured Nether, on straight ground (no rock, no lava): the spiral against searches
   that know where a Nether complex can be. One IDEAL line a world."
  (:require [clojure.java.io :as io]
            [clojure.math :as math]
            [clojurecraft.capture :as capture]
            [clojurecraft.capture-nether :as cn]
            [clojurecraft.chunk :as chunk]
            [clojurecraft.sim :as sim]
            [clojurecraft.simrace :as sr]))

(def view 6)                           ; chunks a server shows round the player
(def region 432)                       ; blocks a nether complex's region is: 27 chunks
(def zone 368)                         ; blocks of it where a start chunk can lie: 23 chunks
(def cell 32)

(defn chunk-of "The chunk coordinate of block coordinate b." [b] (math/floor-div (long b) 16))

(defn seen?
  "Is a brick chunk (in bricks, a set of [cx cz]) within view chunks of the block [x z]?"
  [bricks [x z]]
  (let [cx (chunk-of x)
        cz (chunk-of z)]
    (some (fn [dx]
            (some (fn [dz] (contains? bricks [(+ cx dx) (+ cz dz)])) (range (- view) (inc view))))
          (range (- view) (inc view)))))

(defn walk
  "Walk from a to b in steps of 8 blocks: [found? length] with the length walked until a brick
   is in view (found? true) or all the way."
  [bricks [ax az] [bx bz] walked]
  (let [d (math/hypot (- bx ax) (- bz az))
        n (max 1 (long (math/ceil (/ d 8))))]
    (reduce
     (fn [w i]
       (let [t (/ (double i) n)
             p [(+ ax (* t (- bx ax))) (+ az (* t (- bz az)))]]
         (if (seen? bricks p) (reduced [true (+ walked (* t d)) p]) [false (+ walked d) [bx bz]])))
     [false walked [ax az]]
     (range 1 (inc n)))))

(defn spiral
  "Path length of the square spiral from start: legs 160, 160, 320, 320, ..., east then right."
  [bricks start limit]
  (loop [pos start
         leg 0
         dir [1 0]
         walked 0.0]
    (let [n (* 160 (inc (quot leg 2)))
          to [(+ (first pos) (* n (first dir))) (+ (second pos) (* n (second dir)))]
          [found w p] (walk bricks pos to walked)]
      (cond found (long w)
            (> w limit) nil
            :else (recur p (inc leg) [(- (second dir)) (first dir)] w)))))

(defn zone-cells
  "The cells (centres) of every zone within radius blocks of start."
  [[sx sz] radius]
  (let [r0x (math/floor-div (long (- sx radius)) region)
        r1x (math/floor-div (long (+ sx radius)) region)
        r0z (math/floor-div (long (- sz radius)) region)
        r1z (math/floor-div (long (+ sz radius)) region)]
    (for [rx (range r0x (inc r1x))
          rz (range r0z (inc r1z))
          cx (range (* rx region) (+ (* rx region) zone) cell)
          cz (range (* rz region) (+ (* rz region) zone) cell)]
      [(+ cx (/ cell 2)) (+ cz (/ cell 2))])))

(defn greedy
  "Path length of the search that always walks to the nearest cell of a zone it has not seen,
   marking every cell within view of its path seen."
  [bricks start limit]
  (let [cells (set (zone-cells start 2500))
        reach (* 16 view)]
    (loop [pos start
           unseen cells
           walked 0.0]
      (let [near (fn [[x z]] (math/hypot (- x (first pos)) (- z (second pos))))
            unseen (into #{}
                         (remove #(and (<= (abs (- (first %) (first pos))) reach)
                                       (<= (abs (- (second %) (second pos))) reach)))
                         unseen)]
        (cond (or (empty? unseen) (> walked limit)) nil
              :else
              (let [target (apply min-key near unseen)
                    [found w p] (walk bricks pos target walked)]
                (if found (long w) (recur p unseen w))))))))

(defn nearest
  "The straight distance to the nearest brick view short of: what no search can beat."
  [bricks [x z]]
  (long (max 0
             (- (reduce min
                        (map (fn [[cx cz]] (math/hypot (- (* 16 cx) x) (- (* 16 cz) z))) bricks))
                (* 16 view)))))

(defn -main
  "set-dir: an IDEAL line a world: the spiral, the zone search, the straight line."
  [dir]
  (let [files (sort-by #(Long/parseLong (re-find #"\d+" (.getName ^java.io.File %)))
                       (filter #(re-find #"\d+\.edn\.gz$" (.getName ^java.io.File %))
                               (.listFiles (io/file dir))))
        raw (capture/read-world (sr/nether-file (str (first files))))
        decoded (update-vals (:nether/columns raw) chunk/decode)
        bricks (into #{} (map (fn [[x _ z]] [(chunk-of x) (chunk-of z)])) (cn/bricks decoded))]
    (doseq [f files]
      (let [w (capture/read-world (str f))
            [lx lz ly] (:capture/landing w)
            {[ax _ az] :at} (sim/arrival decoded [lx ly lz])
            start [(double ax) (double az)]]
        (println "IDEAL"
                 (.getName ^java.io.File f)
                 [(long ax) (long az)]
                 "spiral" (spiral bricks start 8000)
                 "zones" (greedy bricks start 8000)
                 "line" (nearest bricks start))))
    (shutdown-agents)))
