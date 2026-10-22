(ns probe.blaze3-trace
  (:require [clojure.math :as math]
            [clojurecraft.capture :as capture]
            [clojurecraft.sim :as sim]
            [clojurecraft.simrace :as sr]
            [clojurecraft.terrain :as terrain]))

(defn line
  [world]
  (let [[x y z] (:player/pos world)
        cell [(long (math/floor x)) (long (math/floor y)) (long (math/floor z))]
        under (update cell 1 dec)]
    (println "T"
             (:time/now world)
             (mapv #(/ (math/round (* 100 (double %))) 100.0) (:player/pos world))
             (:player/on-ground? world)
             (pr-str (select-keys (:plan/intent world)
                                  [:intent/kind :intent/target :intent/move :intent/from
                                   :intent/stage :intent/ops :intent/airborne]))
             "under" (terrain/block-at world under)
             "feet" (terrain/block-at world cell))))

(let [path "test/issues/502/R4-31-1.edn.gz"
      snap (capture/read-world path)
      w (sr/world-for! snap)
      m (sr/moment-of snap w)
      stop? (fn [world]
              (when (<= 3100800 (:time/now world) 3102000) (line world))
              (> (:time/now world) 3102000))]
  (sim/play-from sr/step m stop? (sr/window-of snap) (sr/go-of w nil) nil)
  (println "done"))
