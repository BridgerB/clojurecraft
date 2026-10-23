(ns probe.blazetime-long
  "blazetime: a snapshot played on for an hour: health curve, deaths, what the bot does."
  (:require [clojure.math :as math]
            [clojure.string]
            [clojurecraft.blaze :as blaze]
            [clojurecraft.capture :as capture]
            [clojurecraft.dimension :as dimension]
            [clojurecraft.sim :as sim]
            [clojurecraft.simrace :as sr]))

(defn r1 [v] (mapv #(/ (math/round (* 10 (double %))) 10.0) v))

(defn play
  [path minutes]
  (let [snap (capture/read-world path)
        w (sr/world-for! snap)
        m (sr/moment-of snap w)
        t0 (:time/now (:world m))
        last-print (atom 0)
        last-hp (atom 20.0)
        last-dim (atom nil)
        line (fn [tag world]
               (let [i (:plan/intent world)]
                 (println tag
                          (long (/ (- (:time/now world) t0) 1000))
                          "s"
                          (some-> (dimension/of world)
                                  name)
                          (:intent/kind i)
                          (:intent/for i)
                          "hp"
                          (some-> (:player/health world)
                                  (* 10)
                                  math/round
                                  (/ 10.0))
                          "food" (:player/food world)
                          "blazes" (when (:player/pos world) (count (blaze/blazes world)))
                          "rods" (blaze/rods world)
                          "pos"
                          (some-> (:player/pos world)
                                  r1))))
        stop? (fn [world]
                (let [now (:time/now world)
                      hp (:player/health world 20.0)
                      dim (dimension/of world)]
                  (when (not= dim @last-dim) (reset! last-dim dim) (line "DIM" world))
                  (when (<= hp 0.5) (line "DEAD?" world))
                  (when (>= (- @last-hp hp) 3.0) (line "HIT" world))
                  (reset! last-hp hp)
                  (when (> (- now @last-print) 120000) (reset! last-print now) (line "S" world))
                  false))]
    (println "==" path)
    (let [{:keys [world sim]}
          (sim/play-from sr/step m stop? (* minutes 60000) (sr/go-of w nil) nil)]
      (line "END" world)
      (println "respawns" (:sim/respawns sim 0)))))

(doseq [p (clojure.string/split (or (System/getenv "SNAPS") "") #",")
        :when (seq p)]
  (try (play p 60) (catch Exception e (println "ERR" p (str e)))))
(println "done")
(shutdown-agents)
