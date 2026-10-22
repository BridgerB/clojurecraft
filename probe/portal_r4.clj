(require '[clojurecraft.simrace :as sr] '[clojurecraft.capture :as capture]
         '[clojurecraft.sim :as sim] '[clojurecraft.combat :as combat]
         '[clojurecraft.entity :as entity] '[clojurecraft.plan :as plan]
         '[clojurecraft.physics :as physics])
(let [path "test/issues/178/b10221230-R4-14-1.edn.gz"
      snap (capture/read-world path)
      w (sr/world-for! snap)
      m (sr/moment-of snap w)
      n (volatile! 0)
      stop? (fn [world]
              (let [k (vswap! n inc)]
                (when (<= k 400)
                  (println "TICK" k (get-in world [:plan/intent :intent/kind])
                           (mapv #(Math/round (double %)) (:player/pos world))
                           "hostiles"
                           (pr-str (for [[eid e] (entity/hostiles world)]
                                     [eid (entity/kind e)
                                      (mapv #(/ (Math/round (* 10 (double %))) 10.0) (:entity/pos e))
                                      :hit (combat/hittable? world e)
                                      :seen (combat/seen? world e)]))
                           "threats" (keys (combat/threats world))))
                (> k 400)))]
  (sim/play-from sr/step m stop? 30000 (sr/go-of w (:snapshot/until snap)) nil)
  (println "DONE"))
