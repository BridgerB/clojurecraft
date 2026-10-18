(ns probe.pkice
  "Issue #71, snapshot R2/35: what the planner's retreat sees from the cell the plan failed in."
  (:require [clojurecraft.intent :as intent]
            [clojurecraft.path :as path]
            [clojurecraft.stone :as stone]
            [probe.pklog :as pklog]))

(defn -main
  [& [p]]
  (let [w (pklog/play p)
        feet (intent/feet w)
        from (path/feet-cell (:player/pos w))
        at (path/cells w)
        r (path/plan w from {:goal/kind :away :goal/pos feet :goal/range stone/retreat-distance})]
    (println "FEET" feet from (:plan/boxed-at w))
    (println "START" (path/start-cell w from) "standable" (path/standable? w from))
    (println "CELLS"
             {:feet (at from)
              :head (at (update from 1 inc))
              :under (at (update from 1 dec))
              :under-id (clojurecraft.terrain/block-at w (update from 1 dec))
              :feet-id (clojurecraft.terrain/block-at w from)})
    (println "ROUTE" (select-keys r [:path/status :path/from]) (take 5 (:path/waypoints r)))
    (println "RETREAT" (stone/retreat w))
    (shutdown-agents)))
