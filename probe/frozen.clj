(require '[clojurecraft.capture :as capture]
         '[clojurecraft.simrace :as simrace]
         '[clojurecraft.sim :as sim]
         '[clojurecraft.intent :as intent])

(let [path "test/issues/537/b10232214-R1-20-1.edn.gz"
      snap (capture/read-world path)
      w (simrace/world-for! snap)
      m (simrace/moment-of snap w)
      t0 (:t m)
      last-print (atom t0)
      brief (fn brief [x]
              (when x
                {:kind (:intent/kind x)
                 :stage (:intent/stage x)
                 :status (:intent/status x)
                 :reason (:intent/reason x)
                 :target (:intent/target x)
                 :cell (:intent/cell x)
                 :taken (:intent/taken x)
                 :move (:intent/move x)
                 :op (:build/op (:intent/op x))
                 :opstep (:build/step (:intent/op x))
                 :child (brief (:intent/child x))}))]
  (sim/play-from simrace/step
                 m
                 (fn [world]
                   (when (>= (- (:time/now world) @last-print) 30000)
                     (reset! last-print (:time/now world))
                     (println "T"
                              (- (:time/now world) t0)
                              "pos"
                              (:player/pos world)
                              (pr-str (brief (:plan/intent world)))))
                   false)
                 900000
                 (simrace/go-of w (:snapshot/until snap))
                 nil)
  (println "DONE"))
