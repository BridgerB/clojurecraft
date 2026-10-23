(require '[clojurecraft.capture :as capture]
         '[clojurecraft.simrace :as simrace]
         '[clojurecraft.sim :as sim]
         '[clojurecraft.fortress :as fortress]
         '[clojurecraft.stairs :as stairs]
         '[clojurecraft.build :as build]
         '[clojurecraft.terrain :as terrain]
         '[clojurecraft.intent :as intent])

(let [path "test/issues/537/b10232214-R1-20-1.edn.gz"
      snap (capture/read-world path)
      w (simrace/world-for! snap)
      m (simrace/moment-of snap w)
      world (:world m)
      feet (intent/feet world)
      i (:plan/intent world)]
  (println "FEET" feet "POS" (:player/pos world))
  (println "INTENT" (pr-str (dissoc i :intent/child)))
  (println "CHILD" (pr-str (:intent/child i)))
  (println "OP" (pr-str (:intent/op i)))
  (println "FILLER" (stairs/filler world 1))
  (println "SAFE up-north" (fortress/safe? world feet [0 1 -1]))
  (println "NEXT-OP"
           (pr-str (build/next-op world
                                  (into (fortress/seal-ops world feet [0 1 -1])
                                        (fortress/step-plan feet [0 1 -1])))))
  (println "FOUNDATION floor" (pr-str (build/foundation world [469 43 347])))
  (doseq [c [[469 43 347] [469 42 347] [469 44 347] [469 45 348] [469 45 347] [469 43 348]]]
    (println "BLOCK" c (terrain/block-at world c)))
  (let [t0 (:t m)
        last-print (atom t0)
        brief (fn [x]
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
                                "controls"
                                (:player/controls world)
                                (pr-str (brief (:plan/intent world)))))
                     false)
                   900000
                   (simrace/go-of w (:snapshot/until snap))
                   nil)
    (println "DONE")))
