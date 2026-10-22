(require '[clojurecraft.capture :as capture]
         '[clojurecraft.simrace :as sr]
         '[clojurecraft.sim :as sim]
         '[clojurecraft.plan :as plan]
         '[clojurecraft.intent :as intent]
         '[clojurecraft.path :as path]
         '[clojurecraft.stone :as stone]
         '[clojurecraft.terrain :as terrain]
         '[clojure.string :as str])

(defn out [& xs] (println (str/join " " xs)) (flush))

(defn cells
  [world [fx fy fz] r]
  (doseq [y (range (+ fy 2) (- fy 3) -1)]
    (out " y" y)
    (doseq [z (range (- fz r) (inc (+ fz r)))]
      (out (str/join (for [x (range (- fx r) (inc (+ fx r)))]
                       (cond (= [x y z] [fx fy fz]) "@"
                             (= :water (terrain/fluid-kind world [x y z])) "W"
                             :else
                             (case (path/classify world [x y z])
                               :solid "#"
                               :water "w"
                               :clear "."
                               :unknown "?"
                               :lava "L"
                               "~"))))))))

(doseq [p *command-line-args*]
  (let [snap (capture/read-world p)
        w (sr/world-for! snap)
        m (sr/moment-of snap w)
        stop? #(or (plan/failed? %) (>= (:plan/attempts % 0) 2))
        {:keys [world]} (sim/play-from sr/step m stop? 120000 nil nil)
        feet (intent/feet world)]
    (out "==" p)
    (out "feet" feet
         "pos" (:player/pos world)
         "in-water" (:player/in-water? world)
         "attempts" (:plan/attempts world)
         "boxed-at" (:plan/boxed-at world)
         "boxed-route" (count (:plan/boxed-route world))
         "go-at" (:plan/go-at world))
    (out "fluid-feet" (terrain/fluid-kind world feet)
         "standable" (path/standable? world feet)
         "start-cell" (path/start-cell world feet)
         "x" 1)
    (out "retreat" (pr-str (stone/retreat world)))
    (out "flow-exit" (pr-str (stone/flow-exit world)))
    (out "walk-home" (pr-str (stone/walk-home world)))
    (out "climb" (pr-str (stone/climb world)))
    (cells world feet 6)))

(require '[clojurecraft.stairs :as stairs])
(doseq [p *command-line-args*
        :when (re-find #"r18" p)]
  (let [snap (capture/read-world p)
        w (sr/world-for! snap)
        m (sr/moment-of snap w)
        stop? #(or (plan/failed? %) (>= (:plan/attempts % 0) 2))
        {:keys [world]} (sim/play-from sr/step m stop? 120000 nil nil)
        feet (intent/feet world)
        i (:plan/intent world)]
    (out "== stairs-up probe" feet
         "intent" (pr-str (select-keys i [:intent/kind :intent/for :intent/dir :intent/stage])))
    (doseq [d stairs/directions
            :let [cells (stairs/up-cells feet d)]]
      (out "dir" d
           "cells" cells
           "kinds" (mapv #(path/classify world %) cells)
           "step"
           (path/classify world
                          [(+ (first feet) (first d)) (second feet) (+ (nth feet 2) (second d))]))
      (out "  safe?" (stairs/safe? world cells)
           "clear-way?" (stairs/clear-way? world cells)
           "up-safe?" (stairs/up-safe? world cells)
           "up-sealed?" (stairs/up-sealed? world cells)
           "up-dry?" (stairs/up-dry? world cells)
           "water-over?" (stairs/water-over? world cells))
      (out "  seal" (stairs/seal world feet cells)
           "dry-feet" (stairs/dry-feet world feet cells)
           "step-lay" (stairs/step-lay world feet cells)
           "wet-feet" (stairs/wet-feet world cells)
           "up-lay" (stairs/up-lay world feet cells)
           "filler" (stairs/filler world 1)))))
