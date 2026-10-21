(require '[clojurecraft.capture :as capture]
         '[clojurecraft.simrace :as simrace]
         '[clojurecraft.sim :as sim]
         '[clojurecraft.plan :as plan]
         '[clojurecraft.stairs :as stairs]
         '[clojurecraft.terrain :as terrain]
         '[clojurecraft.blocks :as blocks])

(def st (atom 0))

(doseq [p *command-line-args*]
  (let [snap (capture/read-world p)
        w0 (or (simrace/own-world snap)
               (simrace/prepared (capture/read-world (:snapshot/file snap))))
        m (simrace/moment-of snap w0)
        observe (fn [world]
                  (when (zero? @st)
                    (reset! st 1)
                    (doseq [x (range 2000 2075 2)]
                      (let [col (for [y (range 319 40 -1)
                                      :let [id (terrain/block-at world [x y 2120])]
                                      :when (and id (not (blocks/air? id)))]
                                  [y (blocks/name-of id)])]
                        (println x
                                 "dry-top?" (stairs/dry-top? world [x 2120])
                                 "first" (pr-str (take 2 col))))))
                  true)]
    (println "==" p)
    (sim/play-from simrace/step m observe 1000 (simrace/go-of w0 (:snapshot/until snap)) nil)))
(shutdown-agents)
