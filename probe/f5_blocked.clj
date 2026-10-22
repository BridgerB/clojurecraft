(ns probe.f5-blocked
  "Probe (issue #286, fortress5): a blocked snapshot played to its failure, then every move
   fortress/moves offers from the feet judged: the cells it opens and the floor by name, safe?,
   the lava beside, safe-but-lava?."
  (:require [clojurecraft.blocks :as blocks]
            [clojurecraft.capture :as capture]
            [clojurecraft.fortress :as fortress]
            [clojurecraft.intent :as intent]
            [clojurecraft.plan :as plan]
            [clojurecraft.sim :as sim]
            [clojurecraft.simrace :as simrace]
            [clojurecraft.stairs :as stairs]
            [clojurecraft.terrain :as terrain]))

(defn run
  "Play path's snapshot until its plan fails, then print one MOVE line per move tried."
  [path target]
  (let [snap (capture/read-world path)
        w (simrace/world-for! snap)
        m (simrace/moment-of snap w)
        r (sim/play-from simrace/step
                         m
                         #(or (plan/done? %) (plan/failed? %))
                         (simrace/window-of snap)
                         (simrace/go-of w (:snapshot/until snap))
                         nil)
        world (:world r)
        feet (intent/feet world)
        name-at #(some-> (terrain/block-at world %)
                         blocks/name-of)]
    (println "FEET"
             path
             feet
             "target" target
             "status" (:plan/status world)
             "on-ground" (:player/on-ground? world))
    (doseq [mv (fortress/moves feet target)]
      (let [cells (fortress/opens feet mv)
            floor (fortress/plus (fortress/plus feet mv) [0 -1 0])]
        (println "MOVE"
                 path
                 (pr-str {:move mv
                          :cells (mapv name-at cells)
                          :floor (name-at floor)
                          :safe? (fortress/safe? world feet mv)
                          :lava-beside (count (fortress/lava-beside world feet mv))
                          :safe-but-lava? (fortress/safe-but-lava? world feet mv)
                          :over-fluid? (stairs/over-fluid? world floor)
                          :sealed? (fortress/sealed? world feet mv)}))))
    (println "NEXT"
             path
             (pr-str (fortress/next-move world feet target fortress/climb-past false #{})))
    (println "BELOW"
             path
             (pr-str (for [dy (range 1 40)]
                       [(- (second feet) dy) (name-at (update feet 1 - dy))])))))

(defn -main
  "Each path and its target."
  [& args]
  (doseq [[p t] (partition 2 args)] (run p (read-string t)))
  (shutdown-agents))
