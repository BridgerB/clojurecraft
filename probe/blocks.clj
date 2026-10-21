(require '[clojurecraft.capture :as capture]
         '[clojurecraft.simrace :as simrace]
         '[clojurecraft.sim :as sim]
         '[clojurecraft.plan :as plan]
         '[clojurecraft.terrain :as terrain]
         '[clojurecraft.blocks :as blocks])

(def st (atom 0))

(doseq [p *command-line-args*]
  (let [snap (capture/read-world p)
        w0 (or (simrace/own-world snap)
               (simrace/prepared (capture/read-world (:snapshot/file snap))))
        m (simrace/moment-of snap w0)
        observe (fn [world]
                  (when (and (>= (:time/now world) 2080000) (zero? @st))
                    (reset! st 1)
                    (doseq [y (range 64 59 -1)
                            z [2119 2120 2121]]
                      (println "y"
                               y
                               "z"
                               z
                               (pr-str (for [x (range 2042 2051)]
                                         (some-> (terrain/block-at world [x y z])
                                                 blocks/name-of)))))
                    (println "pos" (:player/pos world) "vel" (:player/vel world)))
                  (or (plan/failed? world) (plan/done? world) (>= (:time/now world) 2082000)))]
    (println "==" p)
    (sim/play-from simrace/step m observe 200000 (simrace/go-of w0 (:snapshot/until snap)) nil)))
(shutdown-agents)
