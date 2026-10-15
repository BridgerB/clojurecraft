(require '[clojurecraft.capture :as capture]
         '[clojurecraft.simrace :as simrace]
         '[clojurecraft.place :as place]
         '[clojurecraft.intent :as intent]
         '[clojurecraft.terrain :as terrain]
         '[clojurecraft.blocks :as blocks]
         '[clojurecraft.path :as path]
         '[clojure.string :as str])
(defn out [& xs] (println (str/join " " xs)))
(defn nm
  [w c]
  (some-> (terrain/block-at w c)
          blocks/name-of
          name))
(doseq [p *command-line-args*]
  (let [snap (capture/read-world p)
        w0 (simrace/prepared (capture/read-world (:snapshot/file snap)))
        [w _] (simrace/moment snap w0)
        [fx fy fz :as feet] (intent/feet w)]
    (out "==" p "feet" feet "in-water?" (:player/in-water? w) "held-slot" (:player/held-slot w))
    (out " spot" (place/spot w false #{})
         "passage-spot" (place/spot w true #{})
         "niche" (place/niche w))
    (doseq [dy [0 1]
            [dx dz] place/sides]
      (let [c [(+ fx dx) (+ fy dy) (+ fz dz)]
            up (update c 1 inc)
            dn (update c 1 dec)]
        (out "  cand"
             c
             (nm w c)
             "solid"
             (place/solid-at? w c)
             "under"
             (nm w dn)
             (place/solid-at? w dn)
             "over"
             (nm w up)
             (place/solid-at? w up)
             "falls-over" (blocks/falls? (terrain/block-at w up))
             "exposes-fluid" (intent/exposes-fluid? w c)
             "droppable"
             (when (terrain/block-at w c) (place/droppable? w (terrain/block-at w c))))))
    (doseq [y (range (+ fy 2) (- fy 2) -1)]
      (out " y" y)
      (doseq [z (range (- fz 4) (+ fz 5))]
        (out (str/join (for [x (range (- fx 4) (+ fx 5))]
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
