(ns clojurecraft.fortress2-bricks
  (:require [clojure.java.io :as io]
            [clojure.math :as math]
            [clojure.string :as str]
            [clojurecraft.capture :as capture]
            [clojurecraft.chunk :as chunk]
            [clojurecraft.fortress :as fortress]))

(defn -main
  "Prints each set's fortress bricks and each world's distance to the nearest."
  [& _]
  (doseq [set ["R1" "R2" "R3" "R4"]
          :when (.exists (io/file (str "cache/sim-nether/" set ".edn.gz")))]
    (let [n (capture/read-world (str "cache/sim-nether/" set ".edn.gz"))
          cols (into {} (map (fn [[k b]] [k (chunk/decode b)])) (:nether/columns n))
          xs (map first (keys cols))
          zs (map second (keys cols))
          bricks (filter (fn [[_ y]] (>= y 32)) (chunk/find-blocks cols fortress/brick?))
          cells (frequencies (map (fn [[x _ z]] [(quot x 64) (quot z 64)]) bricks))]
      (println "SET" set
               "chunks" (count cols)
               "x" [(apply min xs) (apply max xs)]
               "z" [(apply min zs) (apply max zs)]
               "bricks" (count bricks)
               "cells64" (into (sorted-map) cells))
      (doseq [f (sort (map str (.listFiles (io/file (str "cache/sim-worlds/" set)))))
              :when (str/ends-with? f ".edn.gz")]
        (let [w (capture/read-world f)
              [lx lz] (:capture/landing w)
              nx (quot lx 8)
              nz (quot lz 8)
              d (when (seq bricks)
                  (apply min (map (fn [[x _ z]] (math/hypot (- x nx) (- z nz))) bricks)))]
          (println "WORLD" f
                   "nether" [nx nz]
                   "nearest-brick"
                   (some-> d
                           math/round)))))))
(-main)
