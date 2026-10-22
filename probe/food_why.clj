;; Why the search does or does not start: from the first tick provisioning? holds, every change of
;; what foraging? asks. Usage: clojure -M probe/food_why.clj <set> <world> <seed>
(require '[clojure.java.io :as io]
         '[clojurecraft.capture :as capture]
         '[clojurecraft.entity :as entity]
         '[clojurecraft.game :as game]
         '[clojurecraft.hunt :as hunt]
         '[clojurecraft.intent :as intent]
         '[clojurecraft.plan :as plan]
         '[clojurecraft.sim :as sim]
         '[clojurecraft.simrace :as simrace]
         '[clojurecraft.terrain :as terrain])

(defn facts
  [world]
  {:foraging (hunt/foraging? world)
   :sky (terrain/under-sky? world (intent/feet world))
   :hostile (some? (entity/nearest (entity/hostiles world) (:player/pos world) hunt/forage-wary))
   :health (>= (:player/health world 20.0) hunt/forage-health)
   :intent (:intent/kind (:plan/intent world))
   :for (:intent/for (:plan/intent world))
   :goal (:intent/goal (:plan/intent world))
   :attempts (:plan/attempts world)
   :unwalkable (:plan/unwalkable world)
   :game (some? (hunt/game world))
   :retry (:forage/retry-at (:hunt/forage world))
   :leg (:forage/leg (:hunt/forage world))
   :edible (hunt/edible-held world)})

(let [[set-name n seed] *command-line-args*
      seed (Long/parseLong seed)
      f (io/file (str "cache/sim-worlds/" set-name "/" n ".edn.gz"))
      nether-path (simrace/nether-file f)
      nether (when (.exists (io/file nether-path)) (simrace/read-nether nether-path))
      w (cond-> (simrace/prepared (capture/read-world f))
          nether (simrace/with-nether nether nether-path))
      prev (atom nil)
      lines (atom 0)
      stop? (fn [world]
              (when (and (hunt/provisioning? world) (< @lines 150))
                (let [now (facts world)]
                  (when (not= now @prev)
                    (swap! lines inc)
                    (println "WHY" (:time/now world) (intent/feet world) (pr-str now))
                    (reset! prev now))))
              (or (plan/done? world) (plan/failed? world)))
      {:keys [world]} (sim/run+ simrace/step
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
           (:hunt/forage world)))
(shutdown-agents)
