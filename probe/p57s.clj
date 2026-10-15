(require '[clojurecraft.capture :as capture]
         '[clojurecraft.simrace :as simrace]
         '[clojurecraft.wood :as wood]
         '[clojurecraft.scoop :as scoop]
         '[clojurecraft.intent :as intent]
         '[clojurecraft.terrain :as terrain]
         '[clojurecraft.memory :as memory]
         '[clojurecraft.blocks :as blocks]
         '[clojurecraft.physics :as physics]
         '[clojurecraft.game :as game]
         '[clojurecraft.path :as path]
         '[clojure.string :as str]
         '[clojure.math :as math])
(defn out [& xs] (println (str/join " " xs)))
(doseq [p *command-line-args*]
  (let [snap (capture/read-world p)
        w0 (simrace/prepared (capture/read-world (:snapshot/file snap)))
        [w _] (simrace/moment snap w0)
        [px _ pz] (:player/pos w)
        [fx fy fz :as feet] (intent/feet w)
        ey (physics/y-of (game/eye w))]
    (out "=="
         p
         "feet"
         feet
         "go-at"
         (:plan/go-at w)
         "waiting"
         (:plan/waiting w)
         (:plan/waiting-since w)
         "now"
         (:time/now w))
    (out " logs" (count (memory/positions-now w blocks/log-states))
         "under-sky?" (terrain/under-sky? w feet))
    (out " near-walk" (wood/near-walk w)
         "climb-out" (wood/climb-out w)
         "hunt" (wood/hunt w)
         "next" (wood/gather-next w))
    (doseq [[dx dz] scoop/bearings]
      (let [x (long (math/floor (+ px (* dx scoop/hunt-distance))))
            z (long (math/floor (+ pz (* dz scoop/hunt-distance))))
            g (scoop/ground w [x z] ey)]
        (out "  bearing" [x z]
             "loaded?" (terrain/chunk-loaded? w [x 0 z])
             "ground" g
             "sky?" (when g (terrain/under-sky? w g))
             "routable?"
             (when g
               (intent/routable? w {:intent/kind :walk :intent/target g :intent/for :log-hunt})))))
    (doseq [y (range (+ fy 3) (- fy 3) -1)]
      (out "y" y)
      (doseq [z (range (- fz 6) (+ fz 7))]
        (out (str/join (for [x (range (- fx 6) (+ fx 7))]
                         (if (= [x y z] feet)
                           "@"
                           (case (path/classify w [x y z])
                             :solid "#"
                             :water "W"
                             :clear "."
                             :unknown "?"
                             :lava "L"
                             "~")))))))))
(shutdown-agents)
