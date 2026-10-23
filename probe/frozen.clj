(require '[clojurecraft.capture :as capture]
         '[clojurecraft.simrace :as simrace]
         '[clojurecraft.sim :as sim]
         '[clojurecraft.fortress :as fortress]
         '[clojurecraft.terrain :as terrain]
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
                 :airborne (:intent/airborne x)
                 :child (brief (:intent/child x))}))
      r (sim/play-from simrace/step
                       m
                       (fn [world]
                         (when (>= (- (:time/now world) @last-print) 30000)
                           (reset! last-print (:time/now world))
                           (println "T"
                                    (- (:time/now world) t0)
                                    "pos"
                                    (:player/pos world)
                                    "ground"
                                    (:player/on-ground? world)
                                    (pr-str (brief (:plan/intent world)))))
                         false)
                       900000
                       (simrace/go-of w (:snapshot/until snap))
                       nil)
      world (:world r)
      feet (intent/feet world)
      i (:plan/intent world)]
  (println "FINAL feet" feet "on-ground" (:player/on-ground? world) "intent" (pr-str i))
  (println "BURIED" (pr-str (fortress/buried world feet)))
  (println "PERCHED" (fortress/perched? world feet)
           "RECENTRE" (pr-str (fortress/recentre world feet)))
  (println
   "NEXT-MOVE"
   (pr-str
    (fortress/next-move world feet (:intent/target i) 0 false (:intent/tunnel-visited i #{}))))
  (doseq [mv (fortress/moves feet (:intent/target i) 0)]
    (println "MOVE" mv
             "safe" (fortress/safe? world feet mv)
             "sealed" (fortress/sealed? world feet mv)))
  (doseq [c [[468 44 349] [468 45 349] [468 43 349] [469 44 349] [467 44 349] [468 44 348]
             [468 44 350]]]
    (println "BLOCK" c (terrain/block-at world c)))
  (println "DONE"))
