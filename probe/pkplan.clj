(require '[clojurecraft.plan-test :as pt]
         '[clojurecraft.fixtures :as fx]
         '[clojurecraft.memory :as memory]
         '[clojurecraft.plan :as plan])

(let [w (assoc (pt/world-state [0.5 64.0 0.5]) :world/facts (memory/empty-facts))
      seen (volatile! nil)
      step (fn [wd e]
             (let [wd' (pt/step wd e)
                   i (:plan/intent wd')]
               (when (and i (not= (:intent/id i) @seen))
                 (vreset! seen (:intent/id i))
                 (println "INTENT"
                          (:event/now e)
                          (select-keys i [:intent/kind :intent/for :intent/target])
                          (:player/pos wd')))
               wd'))
      [w2 _] (fx/fold step w (cons pt/go (fx/ticks 50 25000)))]
  (println "END"
           (select-keys w2
                        [:plan/status :plan/reason :plan/attempts :plan/waiting-ticks :plan/boxed-at
                         :plan/unwalkable])
           (:player/pos w2)
           (plan/failed? w2)))
(shutdown-agents)
