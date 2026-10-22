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
         "wet-feet" (stone/wet-feet? world))
    (out "retreat" (pr-str (stone/retreat world)))
    (out "flow-exit" (pr-str (stone/flow-exit world)))
    (out "walk-home" (pr-str (stone/walk-home world)))
    (out "climb" (pr-str (stone/climb world)))
    (cells world feet 6)))
