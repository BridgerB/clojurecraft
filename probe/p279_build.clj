;; Issue #279, real-2-s2-Clj_r18: which :build fails :rejected, and its failing child.
;; Plays the snapshot as simrace/judged does and prints every intention fact since the moment.
;;   clojure -M probe/p279_build.clj <snapshot.edn.gz>
(require '[clojurecraft.capture :as capture]
         '[clojurecraft.simrace :as simrace]
         '[clojurecraft.sim :as sim]
         '[clojurecraft.memory :as memory]
         '[clojurecraft.plan :as plan])

(let [snap (capture/read-world (first *command-line-args*))
      w (simrace/own-world snap)
      {:keys [world] :as m} (simrace/moment-of snap w)
      stage (simrace/stage-of snap)
      stop? #(or (plan/done? %) (plan/failed? %) (contains? (:plan/reached %) stage))
      {world' :world} (sim/play-from simrace/step
                                     m
                                     stop?
                                     (simrace/window-of snap)
                                     (simrace/go-of w (:snapshot/until snap))
                                     nil)
      from (:time/now world)]
  (println "feet" (:player/pos world')
           "failed?" (plan/failed? world')
           "reason" (:plan/reason world'))
  (doseq [f (memory/intentions world')
          :when (>= (:intention/at f 0) from)]
    (println "INTENTION" (pr-str f)))
  (println "PLAN"
           (pr-str (select-keys world'
                                [:plan/status :plan/reason :plan/intent :plan/last
                                 :plan/blacklist]))))
