(require 'clojure.math
         '[clojurecraft.simrace :as sr]
         '[clojurecraft.capture :as capture]
         '[clojurecraft.terrain :as terrain]
         '[clojurecraft.blocks :as blocks]
         '[clojurecraft.entity :as entity])
(doseq [path *command-line-args*]
  (let [snap (capture/read-world path)
        w (sr/world-for! snap)
        {:keys [world]} (sr/moment-of snap w)
        [fx fy fz] (mapv #(long (clojure.math/floor (double %))) (:player/pos world))]
    (println "LOOK" path
             "pos" (:player/pos world)
             "health" (:player/health world)
             "ents"
             (vec (for [[id e] (:world/entities world)] [id (entity/kind e) (:entity/pos e)])))
    (println "LOOK column"
             (vec (for [dy (range -12 6)]
                    [(+ fy dy) (blocks/name-of (terrain/block-at world [fx (+ fy dy) fz]))])))
    (println "LOOK chunks-in-snap" (count (:snapshot/chunks snap))
             "world blocks overlay" (count (:world/blocks world)))))
