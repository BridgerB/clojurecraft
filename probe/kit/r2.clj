(ns kit.r2
  (:require [clojurecraft.capture :as capture]
            [clojurecraft.entity :as entity]
            [clojurecraft.sim :as sim]
            [clojurecraft.simrace :as sr]))

(defn -main
  "Prints every 2 s from the moment: the bot's cell, its intent, the entities within 10 blocks."
  [& [path]]
  (let [snap (capture/read-world path)
        mom (sr/moment-of snap (sr/world-for! snap))]
    (println "MOMENT-T" (:t mom))
    (doseq [ms (range 0 120000 2000)]
      (let [wd (:world (sim/play-from sr/step mom (constantly false) ms nil nil))
            [x y z] (:player/pos wd)]
        (println "T"
                 ms
                 "pos"
                 [(long x) (long y) (long z)]
                 "hp"
                 (:player/health wd)
                 (pr-str (select-keys (:plan/intent wd)
                                      [:intent/kind :intent/target :intent/stage :intent/for
                                       :intent/id]))
                 (pr-str (select-keys (:intent/child (:plan/intent wd))
                                      [:intent/kind :intent/target :intent/status]))
                 (pr-str (for [[id e] (:world/entities wd)
                               :let [[ex ey ez] (:entity/pos e)]
                               :when (and ex (< (Math/abs (- ex x)) 10) (< (Math/abs (- ez z)) 10))]
                           [id (entity/kind e) [(long ex) (long ey) (long ez)]])))))
    (shutdown-agents)))
