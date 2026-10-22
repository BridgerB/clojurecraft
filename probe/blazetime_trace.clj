(ns probe.blazetime-trace
  "blazetime: a snapshot's whole run again, from the landing, with a line for each blaze-leg
   intent (what it did, the health, the food, the edibles held) and a status every ten minutes."
  (:require [clojure.math :as math]
            [clojure.string]
            [clojurecraft.blaze :as blaze]
            [clojurecraft.capture :as capture]
            [clojurecraft.dimension :as dimension]
            [clojurecraft.game :as game]
            [clojurecraft.hunt :as hunt]
            [clojurecraft.plan :as plan]
            [clojurecraft.sim :as sim]
            [clojurecraft.simrace :as sr]))

(defn r1 [v] (mapv #(/ (math/round (* 10 (double %))) 10.0) v))

(def kinds #{:rest :eat :fight :camp :back-off :collect-drops :hunt :tunnel-to})

(defn play
  [path stop-rest-ms]
  (let [snap (capture/read-world path)
        w (sr/world-for! snap)
        seed (:snapshot/seed snap)
        until (:snapshot/until snap)
        last-id (atom nil)
        last-status (atom 0)
        last-dim (atom nil)
        rest-since (atom nil)
        t0 0
        line (fn [tag world]
               (let [i (:plan/intent world)
                     sp (when (:player/pos world) (blaze/nearest-spawner world))]
                 (println tag
                          (long (/ (- (:time/now world) t0) 1000))
                          "s"
                          (some-> (dimension/of world)
                                  (name))
                          (:intent/kind i)
                          (:intent/for i)
                          (some-> i
                                  :intent/target
                                  r1)
                          "hp"
                          (some-> (:player/health world)
                                  (* 10)
                                  math/round
                                  (/ 10.0))
                          "food" (:player/food world)
                          "edible" (hunt/edible-held world)
                          "rods" (blaze/rods world)
                          "blazes" (when (:player/pos world) (count (blaze/blazes world)))
                          "watch" (when (:player/pos world) (count (blaze/watchers world)))
                          "sp" sp
                          "pos"
                          (some-> (:player/pos world)
                                  r1))))
        stop? (fn [world]
                (let [i (:plan/intent world)
                      id (:intent/id i)
                      now (:time/now world)
                      dim (dimension/of world)]
                  (when (not= dim @last-dim) (reset! last-dim dim) (line "DIM" world))
                  (when (and (not= id @last-id) (contains? kinds (:intent/kind i)))
                    (line "I" world))
                  (reset! last-id id)
                  (when (> (- now @last-status) 600000) (reset! last-status now) (line "S" world))
                  (if (= :rest (:intent/kind i))
                    (do (when-not @rest-since (reset! rest-since now))
                        (and (> (- now @rest-since) stop-rest-ms) (do (line "STOP" world) true)))
                    (do (reset! rest-since nil) (or (plan/done? world) (plan/failed? world))))))]
    (println "==" path "seed" seed "until" until)
    (let [{:keys [world]} (sim/run+ sr/step
                                    (game/init {:host "sim" :port 0 :name "Clj_sim"})
                                    (sim/init (cond-> {:columns (:capture/columns w)
                                                       :decoded (:capture/decoded w)
                                                       :spawn (sr/spawn-of w)
                                                       :seed seed
                                                       :tick-seed seed
                                                       :difficulty sr/difficulty
                                                       :day-time (sr/day-time-of seed)}
                                                (:capture/nether w)
                                                (assoc :nether (:capture/nether w))))
                                    stop?
                                    (* 330 60000)
                                    (sr/go-of w until))]
      (line "END" world))))

(doseq [p (clojure.string/split (or (System/getenv "SNAPS") "") #",")
        :when (seq p)]
  (try (play p 1800000)
       (catch Exception e
         (println "ERR" p (str e) (pr-str (take 8 (map str (.getStackTrace e))))))))
(println "done")
(shutdown-agents)
