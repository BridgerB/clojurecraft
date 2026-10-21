(require '[clojure.math :as math]
         '[clojurecraft.capture :as capture]
         '[clojurecraft.simrace :as simrace]
         '[clojurecraft.sim :as sim]
         '[clojurecraft.plan :as plan]
         '[clojurecraft.intent :as intent]
         '[clojurecraft.swim :as swim]
         '[clojurecraft.terrain :as terrain]
         '[clojurecraft.blocks :as blocks])

(def st (atom {:n 0 :t 0}))

(doseq [p *command-line-args*]
  (let [snap (capture/read-world p)
        w0 (or (simrace/own-world snap)
               (simrace/prepared (capture/read-world (:snapshot/file snap))))
        m (simrace/moment-of snap w0)
        observe (fn [world]
                  (let [i (:plan/intent world)
                        now (:time/now world)]
                    (when (and (>= (- now (:t @st)) 2000) (< (:n @st) 80))
                      (swap! st (fn [s]
                                  (-> s
                                      (update :n inc)
                                      (assoc :t now))))
                      (let [feet (intent/feet world)
                            surface (swim/surface-y world :water feet)]
                        (println now
                                 (mapv #(/ (math/round (* 100 %)) 100.0) (:player/pos world))
                                 "vel" (mapv #(/ (math/round (* 100 %)) 100.0) (:player/vel world))
                                 "water?" (:player/in-water? world)
                                 "ground?" (:player/on-ground? world)
                                 "i"
                                 (select-keys i
                                              [:intent/kind :intent/bank :intent/shore
                                               :intent/next-search])
                                 "ctl" (:player/controls world)
                                 "surf" surface
                                 "bank16" (swim/banks world feet surface #{}))))
                    (or (plan/failed? world) (plan/done? world))))]
    (println "==" p)
    (sim/play-from simrace/step m observe 200000 (simrace/go-of w0 (:snapshot/until snap)) nil)))
(shutdown-agents)
