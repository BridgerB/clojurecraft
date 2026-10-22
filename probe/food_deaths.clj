;; Where the first death of a whole run to the Nether happens: the bot's world on the tick its health
;; reached 0, and the story before it. Usage: clojure -M probe/food_deaths.clj <set> <world> <seed>
(require '[clojure.java.io :as io]
         '[clojurecraft.capture :as capture]
         '[clojurecraft.dimension :as dimension]
         '[clojurecraft.entity :as entity]
         '[clojurecraft.game :as game]
         '[clojurecraft.intent :as intent]
         '[clojurecraft.memory :as memory]
         '[clojurecraft.plan :as plan]
         '[clojurecraft.recipe :as recipe]
         '[clojurecraft.sim :as sim]
         '[clojurecraft.simrace :as simrace])

(defn brief
  [i]
  (select-keys i
               [:intent/kind :intent/for :intent/goal :intent/target :intent/status
                :intent/reason]))

(let [[set-name n seed] *command-line-args*
      seed (Long/parseLong seed)
      f (io/file (str "cache/sim-worlds/" set-name "/" n ".edn.gz"))
      nether-path (simrace/nether-file f)
      nether (when (.exists (io/file nether-path)) (simrace/read-nether nether-path))
      w (cond-> (simrace/prepared (capture/read-world f))
          nether (simrace/with-nether nether nether-path))
      {:keys [world sim]} (sim/run+ simrace/step
                                    (game/init {:host "sim" :port 0 :name "Clj_sim"})
                                    (sim/init (cond-> {:columns (:capture/columns w)
                                                       :decoded (:capture/decoded w)
                                                       :spawn (simrace/spawn-of w)
                                                       :seed seed
                                                       :tick-seed seed
                                                       :difficulty simrace/difficulty
                                                       :day-time (simrace/day-time-of seed)}
                                                (:capture/nether w)
                                                (assoc :nether (:capture/nether w))))
                                    #(or (plan/done? %)
                                         (plan/failed? %)
                                         (some-> (:player/health %)
                                                 (<= 0)))
                                    (* 330 60000)
                                    (simrace/go-of w "nether"))]
  (println "DEATH" set-name n seed "at" (:time/now world) "ms, day-time" (:sim/day-time sim))
  (println " dim" (dimension/of world)
           "feet" (intent/feet world)
           "health" (:player/health world)
           "food" (:player/food world))
  (println " held" (pr-str (recipe/counts (:player/inventory world))))
  (println " intent" (pr-str (brief (:plan/intent world)))
           "last" (pr-str (brief (:plan/last world))))
  (println " forage" (pr-str (:hunt/forage world)))
  (println " hostiles" (pr-str (entity/healths world (:player/pos world))))
  (println " story (last 30):")
  (doseq [i (take-last 30 (memory/intentions world))]
    (println "  "
             (pr-str (select-keys i
                                  [:intention/at :intention/kind :intention/event :intention/for
                                   :intention/reason :intention/target])))))
(shutdown-agents)
