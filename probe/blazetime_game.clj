(ns probe.blazetime-game
  "blazetime: how much game each captured world shows its bot in the first minutes."
  (:require [clojure.string]
            [clojurecraft.capture :as capture]
            [clojurecraft.entity :as entity]
            [clojurecraft.game :as game]
            [clojurecraft.hunt :as hunt]
            [clojurecraft.physics :as physics]
            [clojurecraft.sim :as sim]
            [clojurecraft.simrace :as sr]))

(defn within
  [world r]
  (let [p (:player/pos world)]
    (count (for [[_ e] (:world/entities world)
                 :when (and (hunt/game-kinds (entity/kind e))
                            (<= (physics/distance p (:entity/pos e)) r))]
             e))))

(defn play
  [f at-ms]
  (let [w (sr/prepared (capture/read-world f))
        {:keys [world]} (sim/run+ sr/step
                                  (game/init {:host "sim" :port 0 :name "Clj_sim"})
                                  (sim/init {:columns (:capture/columns w)
                                             :decoded (:capture/decoded w)
                                             :spawn (sr/spawn-of w)
                                             :seed 1
                                             :tick-seed 1
                                             :difficulty sr/difficulty
                                             :day-time (sr/day-time-of 1)})
                                  #(> (:time/now %) at-ms)
                                  (+ at-ms 60000)
                                  (sr/go-of w "nether"))]
    (println (str f)
             "t" (:time/now world)
             "r24" (within world 24)
             "r48" (within world 48)
             "r96" (within world 96)
             "game" (some? (hunt/game world)))))

(doseq [set-dir (clojure.string/split (or (System/getenv "SETS") "") #",")
        :when (seq set-dir)
        f (sr/world-files set-dir)]
  (try (play f 120000) (catch Exception e (println "ERR" (str f) (str e)))))
(println "done")
(shutdown-agents)
