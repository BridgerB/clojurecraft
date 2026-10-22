(require '[clojure.math :as math]
         '[clojurecraft.simrace :as sr]
         '[clojurecraft.capture :as capture]
         '[clojurecraft.sim :as sim]
         '[clojurecraft.combat :as combat]
         '[clojurecraft.entity :as entity]
         '[clojurecraft.physics :as physics])
(let [path "test/issues/178/b10221230-R4-14-1.edn.gz"
      snap (capture/read-world path)
      w (sr/world-for! snap)
      m (sr/moment-of snap w)
      n (volatile! 0)
      prev (volatile! nil)
      shown (volatile! 0)
      stop? (fn [world]
              (let [k (vswap! n inc)
                    kind (get-in world [:plan/intent :intent/kind])]
                (when (and (> k 17000) (not= kind @prev) (< @shown 24))
                  (vswap! shown inc)
                  (println "CHG"
                           k
                           kind
                           (mapv #(/ (math/round (* 10 (double %))) 10.0) (:player/pos world))
                           "near"
                           (pr-str (for [[eid e] (entity/hostiles world)
                                         :when (< (physics/distance (:player/pos world)
                                                                    (:entity/pos e))
                                                  8)]
                                     [eid (entity/kind e)
                                      (mapv #(/ (math/round (* 10 (double %))) 10.0)
                                            (:entity/pos e)) (combat/hittable? world e)]))
                           "threats" (keys (combat/threats world))))
                (vreset! prev kind)
                false))]
  (sim/play-from sr/step m stop? (sr/window-of snap) (sr/go-of w (:snapshot/until snap)) nil)
  (println "DONE" @n))
