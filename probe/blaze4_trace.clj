(ns probe.blaze4-trace
  (:require [clojure.math :as math]
            [clojurecraft.capture :as capture]
            [clojurecraft.fortress :as fortress]
            [clojurecraft.intent :as intent]
            [clojurecraft.sim :as sim]
            [clojurecraft.simrace :as sr]
            [clojurecraft.terrain :as terrain]))

(defn r2 [v] (mapv #(/ (math/round (* 100 (double %))) 100.0) v))

(defn line
  [world]
  (println "T"
           (:time/now world)
           (r2 (:player/pos world))
           (:player/on-ground? world)
           (:player/health world)
           (pr-str (select-keys (:plan/intent world)
                                [:intent/kind :intent/target :intent/move :intent/from :intent/taken
                                 :intent/retreats :intent/airborne :intent/stage]))))

(defn eval-line
  [world]
  (let [i (:plan/intent world)
        feet (intent/feet world)
        target (:intent/target i)]
    (when (and (= :tunnel-to (:intent/kind i)) target)
      (println "E"
               (:time/now world)
               feet
               "nextmove"
               (fortress/next-move world feet target 3 false (:intent/tunnel-visited i #{}))
               "visited" (count (:intent/tunnel-visited i #{}))
               "perched" (fortress/perched? world feet)
               "recentre" (fortress/recentre world feet))
      (doseq [m (take 8 (fortress/moves feet target 3))]
        (println "  M" m
                 "safe" (fortress/safe? world feet m)
                 "lava-beside" (count (fortress/lava-beside world feet m))
                 "sbl" (fortress/safe-but-lava? world feet m)
                 "opens" (mapv #(terrain/block-at world %) (fortress/opens feet m))
                 "floor" (terrain/block-at world (mapv + feet m [0 -1 0])))))))

(defn run
  [path t0 t1 e0]
  (let [snap (capture/read-world path)
        w (sr/world-for! snap)
        m (sr/moment-of snap w)
        stop? (fn [world]
                (let [t (:time/now world)]
                  (when (and (<= t0 t t1) (zero? (mod t 500))) (line world))
                  (when (and (<= e0 t t1) (zero? (mod t 250))) (eval-line world))
                  (> t t1)))]
    (println "== " path)
    (sim/play-from sr/step m stop? (sr/window-of snap) (sr/go-of w nil) nil)))

(run "test/issues/502/b10221245-R3-5-2.edn.gz" 13110000 13248000 13236000)
(run "test/issues/502/b10221245-R4-11-1.edn.gz" 15510000 15668000 15655000)
(println "done")
