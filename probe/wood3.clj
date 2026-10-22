(ns probe.wood3
  "Wood3 probe: the play of a snapshot with every intent change, the column under the feet, and
   the trunks the planner sees."
  (:require [clojure.math :as math]
            [clojurecraft.blocks :as blocks]
            [clojurecraft.capture :as capture]
            [clojurecraft.memory :as memory]
            [clojurecraft.sim :as sim]
            [clojurecraft.simrace :as simrace]
            [clojurecraft.terrain :as terrain]
            [clojurecraft.wood :as wood]))

(defn column
  [world [fx fy fz]]
  (let [x (long (math/floor fx))
        y (long (math/floor fy))
        z (long (math/floor fz))]
    (println "COLUMN"
             [x y z]
             (pr-str (vec (for [dy (range 3 -40 -1)]
                            [(+ y dy)
                             (blocks/name-of (terrain/block-at world [x (+ y dy) z]))]))))))

(defn brief
  [i]
  (when i
    (into (sorted-map)
          (filter (fn [[_ v]] (or (keyword? v) (number? v) (and (vector? v) (<= (count v) 4)))))
          i)))

(defn go
  [path]
  (let [snap (capture/read-world path)
        w (simrace/world-for! snap)
        {:keys [world] :as m} (simrace/moment-of snap w)
        seen (volatile! nil)
        t0 (:time/now world)
        stop? (fn [wd]
                (let [i (:plan/intent wd)
                      k [(:intent/id i) (:plan/wait wd) (:plan/status wd)]]
                  (when (not= k @seen)
                    (vreset! seen k)
                    (println "AT"
                             (- (:time/now wd) t0)
                             (some-> (:player/pos wd)
                                     (->> (mapv math/round)))
                             "wait"
                             (:plan/wait wd)
                             "status"
                             (:plan/status wd)
                             "boxed"
                             (:plan/boxed-at wd)
                             (pr-str (brief i))))
                  (or (= :failed (:plan/status wd)) false)))
        _ (println "PATH" path)
        _ (column world (:player/pos world))
        _ (println "GATHER-NEXT" (pr-str (brief (wood/gather-next world))))
        _ (println "BLACKLIST" (pr-str (:plan/blacklist world))
                   "unreached" (pr-str (:plan/unreached world))
                   "failures" (pr-str (:plan/trunk-failures world)))
        r (sim/play-from simrace/step
                         m
                         stop?
                         (simrace/window-of snap)
                         (simrace/go-of w (:snapshot/until snap))
                         nil)]
    (println "ENDED"
             (pr-str (some-> r
                             :world
                             :player/pos)))))

(defn -main [& paths] (doseq [p paths] (go p)) (shutdown-agents))
