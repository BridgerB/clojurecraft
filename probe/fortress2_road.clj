(ns clojurecraft.fortress2-road
  (:require [clojure.string :as str]
            [clojurecraft.build :as build]
            [clojurecraft.capture :as capture]
            [clojurecraft.dimension :as dimension]
            [clojurecraft.game :as game]
            [clojurecraft.intent :as intent]
            [clojurecraft.make :as make]
            [clojurecraft.plan :as plan]
            [clojurecraft.sim :as sim]
            [clojurecraft.simrace :as simrace]))

(defn -main
  "Plays world file path with seed from its landing for minutes, printing every simulated minute:
   the goal, the feet, the dimension, the sticks, planks, logs, tables and pickaxes held, and
   whether the trees are near."
  [path seed minutes]
  (let [w (simrace/prepared (capture/read-world path))
        w (if-let [n (simrace/nether-file path)]
            (if (.exists (java.io.File. ^String n))
              (simrace/with-nether w (simrace/read-nether n) n)
              w)
            w)
        seed (Long/parseLong seed)
        last-s (volatile! -1)
        stop? (fn [world]
                (let [s (quot (or (:time/now world) 0) 60000)]
                  (when (> s @last-s)
                    (vreset! last-s s)
                    (let [c (make/counts world)
                          i (:plan/intent world)]
                      (println
                       "ROAD"
                       s
                       (:plan/goal world)
                       (:intent/kind i)
                       (:intent/for i)
                       "feet"
                       (intent/feet world)
                       (dimension/of world)
                       "sticks" (get c :stick 0)
                       "planks"
                       (reduce + (for [[k n] c :when (str/ends-with? (name k) "_planks")] n))
                       "logs" (reduce + (for [[k n] c :when (str/ends-with? (name k) "_log")] n))
                       "tables" (get c :crafting_table 0)
                       "picks"
                       (reduce + (for [[k n] c :when (str/ends-with? (name k) "_pickaxe")] n))
                       "cobble" (get c :cobblestone 0))))
                  false))]
    (sim/run simrace/step
             (game/init {:host "sim" :port 0 :name "Clj_sim"})
             (sim/init (cond-> {:columns (:capture/columns w)
                                :decoded (:capture/decoded w)
                                :spawn (simrace/spawn-of w)
                                :seed seed
                                :tick-seed seed
                                :difficulty simrace/difficulty
                                :day-time (simrace/day-time-of seed)}
                         (:capture/nether w) (assoc :nether (:capture/nether w))))
             stop?
             (* (Long/parseLong minutes) 60000)
             (simrace/go-of w "fortress"))
    nil))
