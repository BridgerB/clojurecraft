(ns clojurecraft.blocked-why
  "Why a :tunnel-to failed :blocked at a moment of a shipped recording: the recording folded to
   each at-ms (why/at), then the failed tunnel's feet, target, trail, every move toward the target
   with each test next-move asks, and the blocks round the feet. A probe script: runs on a runner.

     clojure -M:dev -m clojurecraft.blocked-why <recording> <at-ms> ..."
  (:require [clojure.string :as str]
            [clojurecraft.blocks :as blocks]
            [clojurecraft.fortress :as f]
            [clojurecraft.intent :as intent]
            [clojurecraft.terrain :as terrain]
            [clojurecraft.why :as why]))

(defn short-name
  "A block's name, shortened for a grid."
  [id]
  (cond (nil? id) "?"
        :else (let [n (name (blocks/name-of id))] (subs n 0 (min 9 (count n))))))

(defn -main
  "Print the tunnel's state at each at-ms."
  [path & ms]
  (doseq [m ms]
    (let [w (why/at path (parse-long m))
          feet (intent/feet w)
          i (or (:plan/intent w) (:plan/failed w))
          target (:intent/target i)
          climb (:intent/climb i f/climb-past)
          trail (f/trail-of w target)
          seen (-> (:intent/tunnel-visited i #{})
                   (into (:trail/cells trail))
                   (into (:trail/dead trail)))
          [fx fy fz] feet]
      (println "==== at" m
               "feet" feet
               "pos" (:player/pos w)
               "ground" (:player/on-ground? w)
               "hp" (:player/health w))
      (println "intent" (dissoc i :intent/tunnel-visited :intent/child))
      (println "tunnel-visited" (:intent/tunnel-visited i))
      (println "fortress/trail" (:fortress/trail w))
      (println "plan/failed reason" (:intent/reason (:plan/failed w))
               "plan/intent kind" (:intent/kind (:plan/intent w)))
      (when target
        (doseq [mv (f/moves feet target climb)]
          (println " move" mv
                   "to" (f/plus feet mv)
                   "safe" (f/safe? w feet mv)
                   "safe-but-lava" (f/safe-but-lava? w feet mv)
                   "lava" (vec (f/lava-beside w feet mv))
                   "seen" (contains? seen (f/plus feet mv))
                   "climb-out" (f/climb-out? w feet mv false)
                   "cells"
                   (mapv #(short-name (terrain/block-at w %))
                         (conj (f/opens feet mv) (f/plus (f/plus feet mv) [0 -1 0])))))
        (println "next-move"
                 (f/next-move w feet target climb false seen false (boolean (:intent/no-down? i)))
                 "fresh?"
                 (f/fresh-move?
                  feet
                  (f/next-move w feet target climb false seen false (boolean (:intent/no-down? i)))
                  target
                  seen
                  (:trail/dead trail))
                 "step-back" (f/step-back w feet trail false (boolean (:intent/no-down? i)))
                 "recentre" (f/recentre w feet)))
      (doseq [y (range (- fy 2) (+ fy 4))]
        (println "y" y "(rows z" (- fz 3) ".." (+ fz 3) ", cols x" (- fx 3) ".." (+ fx 3) ")")
        (doseq [z (range (- fz 3) (+ fz 4))]
          (println "  "
                   (str/join " "
                             (for [x (range (- fx 3) (+ fx 4))]
                               (format "%-9s"
                                       (if (= [x y z] feet)
                                         "@FEET"
                                         (short-name (terrain/block-at w [x y z])))))))))))
  (shutdown-agents))
