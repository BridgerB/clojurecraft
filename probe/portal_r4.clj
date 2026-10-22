(require '[clojure.math :as math]
         '[clojurecraft.simrace :as sr]
         '[clojurecraft.capture :as capture]
         '[clojurecraft.sim :as sim]
         '[clojurecraft.combat :as combat]
         '[clojurecraft.entity :as entity]
         '[clojurecraft.physics :as physics])
(defn r1 [v] (mapv #(/ (math/round (* 10 (double %))) 10.0) v))
(let [path "test/issues/178/b10221230-R4-14-1.edn.gz"
      snap (capture/read-world path)
      w (sr/world-for! snap)
      m (sr/moment-of snap w)
      n (volatile! 0)
      first-fight (volatile! nil)
      stop? (fn [world]
              (let [k (vswap! n inc)
                    kind (get-in world [:plan/intent :intent/kind])]
                (when (and (nil? @first-fight) (> k 17500) (= kind :fight)) (vreset! first-fight k))
                (when (and @first-fight (< (- k @first-fight) 40))
                  (println
                   "T"
                   (- k @first-fight)
                   kind
                   (r1 (:player/pos world))
                   (get-in world [:plan/intent :intent/stage])
                   (pr-str (:player/controls world))
                   (pr-str (for [[eid e] (entity/hostiles world)
                                 :when (< (physics/distance (:player/pos world) (:entity/pos e)) 8)]
                             [eid (r1 (:entity/pos e)) (combat/hittable? world e)]))
                   "thr"
                   (keys (combat/threats world))))
                false))]
  (sim/play-from sr/step m stop? (sr/window-of snap) (sr/go-of w (:snapshot/until snap)) nil)
  (println "DONE" @n))
