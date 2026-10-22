(ns probe.blaze3-trace
  (:require [clojure.math :as math]
            [clojurecraft.capture :as capture]
            [clojurecraft.entity :as entity]
            [clojurecraft.sim :as sim]
            [clojurecraft.simrace :as sr]))

(defn line
  [world]
  (let [es (for [[_ e] (:world/entities world)
                 :when (#{:blaze :item} (entity/kind e))]
             [(entity/kind e) (mapv #(math/round (double %)) (:entity/pos e))])]
    (println "T"
             (:time/now world)
             (mapv #(/ (math/round (* 10 (double %))) 10.0) (:player/pos world))
             (:player/on-ground? world)
             (:intent/kind (:plan/intent world))
             (:player/health world)
             (pr-str es))))

(let [path "test/issues/502/R4-31-1.edn.gz"
      snap (capture/read-world path)
      w (sr/world-for! snap)
      m (sr/moment-of snap w)
      n (volatile! 0)
      stop? (fn [world]
              (when (zero? (mod (vswap! n inc) 2)) (line world))
              (or (> @n 3000) (> (:time/now world) 3104000)))]
  (sim/play-from sr/step m stop? (sr/window-of snap w) (sr/go-of w nil) nil)
  (println "done"))
