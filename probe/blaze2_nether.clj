(ns probe.blaze2-nether
  (:require [clojure.math]
            [clojurecraft.blocks :as blocks]
            [clojurecraft.capture :as capture]
            [clojurecraft.chunk :as chunk]))

(defn dist
  [[x y z] [a b c]]
  (clojure.math/sqrt (+ (* (- x a) (- x a)) (* (- y b) (- y b)) (* (- z c) (- z c)))))

(defn check
  [set at]
  (let [n (capture/read-world (str "cache/sim-nether/" set ".edn.gz"))
        cols (into {} (map (fn [[k b]] [k (chunk/decode b)])) (:nether/columns n))
        sp (blocks/states-where #(= :spawner (nth % 0)))
        br (blocks/states-where #(= :nether_bricks (nth % 0)))
        spawners (chunk/find-blocks cols sp)
        bricks (chunk/find-blocks cols br)
        xs (map ffirst (keys cols))]
    (println "SET"
             set
             "at"
             at
             "columns"
             (count cols)
             "chunk x range"
             (apply min (map first (keys cols)))
             (apply max (map first (keys cols)))
             "z range"
             (apply min (map second (keys cols)))
             (apply max (map second (keys cols))))
    (println " spawners total" (count spawners)
             "within 64 of at:"
             (pr-str (sort-by #(dist % at)
                              (filter #(< (dist % at) 64) (map #(subvec (vec %) 0 3) spawners)))))
    (println " nearest 5 spawners"
             (pr-str (take 5 (sort-by #(dist % at) (map #(subvec (vec %) 0 3) spawners)))))
    (println " nether_bricks within 32 of at:" (count (filter #(< (dist % at) 32) bricks))
             "total" (count bricks))))

(check "R2" [585 55 585])
(check "R4" [410 52 203])
