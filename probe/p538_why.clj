;; Issue #538: why every move from the feet at the :blocked failure is refused.
;; Plays the snapshot as simrace/judged does to the plan's end, then for each move of
;; fortress/moves prints what fortress/safe? reads.
;;   clojure -M probe/p538_why.clj <snapshot.edn.gz> <tx> <ty> <tz>
(require '[clojurecraft.blocks :as blocks]
         '[clojurecraft.capture :as capture]
         '[clojurecraft.fortress :as fortress]
         '[clojurecraft.intent :as intent]
         '[clojurecraft.path :as path]
         '[clojurecraft.plan :as plan]
         '[clojurecraft.sim :as sim]
         '[clojurecraft.simrace :as simrace]
         '[clojurecraft.terrain :as terrain])

(defn why
  [world feet move]
  (let [cells (fortress/opens feet move)
        floor (fortress/plus (fortress/plus feet move) [0 -1 0])
        at #(terrain/block-at world %)
        nm #(some-> (at %)
                    blocks/name-of)]
    {:move move
     :safe? (fortress/safe? world feet move)
     :cells (mapv nm cells)
     :floor [(nm floor) (path/floor? world floor)]
     :lava-beside (vec (fortress/lava-beside world feet move))
     :below (let [[x y z] floor]
              (some (fn [d]
                      (let [n (nm [x (- y d) z])] (when (and n (not (#{:air :cave_air} n))) [d n])))
                    (range 1 60)))}))

(let [[file & t] *command-line-args*
      target (mapv parse-long t)
      snap (capture/read-world file)
      w (simrace/world-for! snap)
      {:keys [world] :as m} (simrace/moment-of snap w)
      stage (simrace/stage-of snap)
      stop? #(or (plan/done? %) (plan/failed? %) (contains? (:plan/reached %) stage))
      {world' :world} (sim/play-from simrace/step
                                     m
                                     stop?
                                     (simrace/window-of snap)
                                     (simrace/go-of w (:snapshot/until snap))
                                     nil)
      feet (intent/feet world')]
  (println "FILE"
           file
           "feet"
           feet
           "target"
           target
           "failed?"
           (plan/failed? world')
           (:plan/reason world'))
  (doseq [mv (fortress/moves feet target 3)] (println "WHY" (pr-str (why world' feet mv)))))
