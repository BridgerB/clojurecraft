;; What the bot does minute by minute in one world from empty hands: a sample every sample-ms of
;; sim time. Usage: clojure -M probe/food2_why.clj <set> <world> <seed> <sample-minutes>
(require '[clojure.java.io :as io]
         '[clojurecraft.capture :as capture]
         '[clojurecraft.game :as game]
         '[clojurecraft.hunt :as hunt]
         '[clojurecraft.intent :as intent]
         '[clojurecraft.plan :as plan]
         '[clojurecraft.sim :as sim]
         '[clojurecraft.simrace :as simrace]
         '[clojurecraft.terrain :as terrain])

(let [[set-name n seed every] *command-line-args*
      seed (Long/parseLong seed)
      every-ms (* 60000 (Long/parseLong every))
      f (io/file (str "cache/sim-worlds/" set-name "/" n ".edn.gz"))
      nether-path (simrace/nether-file f)
      nether (when (.exists (io/file nether-path)) (simrace/read-nether nether-path))
      w (cond-> (simrace/prepared (capture/read-world f))
          nether (simrace/with-nether nether nether-path))
      next-at (atom 0)
      stop? (fn [world]
              (when (and (:player/pos world) (>= (:time/now world 0) @next-at))
                (reset! next-at (+ (:time/now world 0) every-ms))
                (println "WHY"
                         (quot (long (:time/now world 0)) 60000)
                         (mapv #(long (clojure.math/floor %)) (:player/pos world))
                         (pr-str {:food (:player/food world)
                                  :hp (:player/health world)
                                  :phase (hunt/provision-phase world)
                                  :edible (hunt/edible-held world)
                                  :kind (:intent/kind (:plan/intent world))
                                  :for (:intent/for (:plan/intent world))
                                  :goal (:intent/goal (:plan/intent world))
                                  :attempts (:plan/attempts world)
                                  :sky (terrain/under-sky? world (intent/feet world))
                                  :game (some? (hunt/game world))
                                  :forage (:hunt/forage world)}))
                (flush))
              (or (plan/done? world) (plan/failed? world)))
      {:keys [world crashed]} (sim/run+ simrace/step
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
                                        stop?
                                        (* 330 60000)
                                        (simrace/go-of w "nether"))]
  (println "END"
           set-name
           n
           seed
           (:time/now world)
           (:plan/status world)
           (:plan/reason world)
           (some-> crashed
                   str))
  (flush))
(shutdown-agents)
