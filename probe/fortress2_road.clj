(ns clojurecraft.fortress2-road
  (:require [clojure.string :as str]
            [clojurecraft.capture :as capture]
            [clojurecraft.dimension :as dimension]
            [clojurecraft.game :as game]
            [clojurecraft.intent :as intent]
            [clojurecraft.make :as make]
            [clojurecraft.sim :as sim]
            [clojurecraft.simrace :as simrace]))

(defn total
  "The count of held items whose name ends in suffix."
  [c suffix]
  (reduce + (for [[k n] c :when (str/ends-with? (name k) suffix)] n)))

(defn sample
  "One ROAD line for world: the goal, the intent, the feet, the dimension and what is held."
  [s world]
  (try (let [c (make/counts world)
             i (:plan/intent world)]
         (println "ROAD"
                  s
                  (:plan/goal world)
                  (:intent/kind i)
                  (:intent/for i)
                  "feet"
                  (intent/feet world)
                  (dimension/of world)
                  "sticks" (get c :stick 0)
                  "planks" (total c "_planks")
                  "logs" (total c "_log")
                  "tables" (get c :crafting_table 0)
                  "picks" (total c "_pickaxe")
                  "cobble" (get c :cobblestone 0)
                  "op" (select-keys (:intent/op i) [:build/step :build/op :build/cell :build/item])
                  "child"
                  (select-keys (:intent/child i)
                               [:intent/kind :intent/target :intent/status :intent/reason
                                :intent/for :intent/tool])))
       (catch Exception e (println "ROADERR" s (ex-message e)))))

(defn -main
  "Plays world file path with seed from its landing for minutes, sampling every simulated minute."
  [path seed minutes]
  (let [w (simrace/prepared (capture/read-world path))
        n (simrace/nether-file path)
        w
        (if (.exists (java.io.File. ^String n)) (simrace/with-nether w (simrace/read-nether n) n) w)
        seed (Long/parseLong seed)
        last-s (volatile! -1)
        stop? (fn [world]
                (let [s (quot (or (:time/now world) 0) 60000)]
                  (when (> s @last-s) (vreset! last-s s) (sample s world))
                  false))
        r (sim/run+ simrace/step
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
                    (simrace/go-of w "fortress"))]
    (println "ROADEND"
             (some-> r
                     :crashed
                     ex-message)
             (some-> r
                     :world
                     :time/now))
    nil))
