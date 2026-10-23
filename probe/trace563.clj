(println "TRACE-START")
(require '[clojurecraft.capture :as capture]
         '[clojurecraft.simrace :as sr]
         '[clojurecraft.sim :as sim]
         '[clojurecraft.plan :as plan])

(let [path "test/issues/563/R3-13-2.edn.gz"
      snap (capture/read-world path)
      w (sr/world-for! snap)
      m (sr/moment-of snap w)
      last-id (atom nil)
      last-h (atom 99.0)
      step (fn [ww e]
             (let [ww' (sr/step ww e)
                   i (:plan/intent ww')
                   h (:player/health ww' 20.0)]
               (when (and i (not= (:intent/id i) @last-id))
                 (reset! last-id (:intent/id i))
                 (println "INTENT"
                          (:time/now ww')
                          h
                          (:intent/kind i)
                          (:intent/target i)
                          (:intent/goal i)
                          "hold" (:intent/hold? i)
                          "end" (:intent/to-the-end? i)
                          "for" (:intent/for i)
                          "etype" (get-in ww' [:world/entities (:intent/target i) :entity/type])
                          "cornered" (:plan/cornered ww')))
               (when (< h (- @last-h 1.0))
                 (reset! last-h h)
                 (println "HEALTH"
                          (:time/now ww')
                          h
                          (:intent/kind i)
                          (:intent/target i)
                          (:intent/goal i)))
               ww'))]
  (sim/play-from step
                 m
                 (fn [ww] (or (plan/done? ww) (plan/failed? ww)))
                 (sr/window-of snap)
                 (sr/go-of w (:snapshot/until snap))
                 nil)
  (println "END"))
