(require '[clojurecraft.capture :as capture]
         '[clojurecraft.simrace :as simrace]
         '[clojurecraft.sim :as sim]
         '[clojurecraft.plan :as plan])

(doseq [p *command-line-args*]
  (let [snap (capture/read-world p)
        w0 (or (simrace/own-world snap)
               (simrace/prepared (capture/read-world (:snapshot/file snap))))
        m (simrace/moment-of snap w0)
        observe (fn [world]
                  (let [now (:time/now world)]
                    (when (and (>= now 2072100) (< now 2072700))
                      (let [[x y z] (:player/pos world)
                            [vx vy vz] (:player/vel world)]
                        (println now
                                 (format "x %.3f y %.3f vx %.4f vy %.4f" x y vx vy)
                                 "yaw" (:control/yaw (:player/controls world))
                                 "ts" (:time/tick world)
                                 "i"
                                 (select-keys (:plan/intent world)
                                              [:intent/shore :intent/bank :intent/next-search
                                               :intent/kind])
                                 "w" (:player/in-water? world)
                                 "fwd" (:control/forward? (:player/controls world))
                                 "jmp" (:control/jump? (:player/controls world)))))
                    (or (plan/failed? world) (plan/done? world) (>= now 2072700))))]
    (println "==" p)
    (sim/play-from simrace/step m observe 200000 (simrace/go-of w0 (:snapshot/until snap)) nil)))
(shutdown-agents)
