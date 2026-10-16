(require '[clojurecraft.capture :as capture]
         '[clojurecraft.simrace :as simrace]
         '[clojurecraft.intent :as intent]
         '[clojurecraft.blocks :as blocks]
         '[clojurecraft.terrain :as terrain]
         '[clojure.string :as str])
(defn out [& xs] (println (str/join " " xs)))
(doseq [p *command-line-args*]
  (let [snap (capture/read-world p)
        [world _] (simrace/moment snap
                                  (simrace/prepared (capture/read-world (:snapshot/file snap))))
        [fx fy fz] (intent/feet world)]
    (out "==" p "feet" [fx fy fz])
    (doseq [dx [-2 0 2]
            dz [-2 0 2]]
      (out " column"
           [(+ fx dx) (+ fz dz)]
           (pr-str (for [y (range (inc fy) (+ fy 22))]
                     (some-> (terrain/block-at world [(+ fx dx) y (+ fz dz)])
                             blocks/name-of)))))))
(shutdown-agents)
