;; The food a bot carries into the Nether: whole runs from empty hands to the Nether in the captured
;; worlds of one set, and the world each stops in. Usage: clojure -M probe/food_trace.clj <set> <count> [seed]
(require '[clojure.java.io :as io]
         '[clojurecraft.capture :as capture]
         '[clojurecraft.dimension :as dimension]
         '[clojurecraft.hunt :as hunt]
         '[clojurecraft.recipe :as recipe]
         '[clojurecraft.simrace :as simrace])

(def food-names
  #{:beef :porkchop :mutton :rabbit :bread :apple :carrot :potato :baked_potato :cooked_beef
    :cooked_porkchop :cooked_mutton :cooked_rabbit :melon_slice :sweet_berries :beetroot
    :rotten_flesh :chicken :cooked_chicken})

(defn line
  [set-name f seed r]
  (let [w (:run/end r)
        held (recipe/counts (:player/inventory w))]
    (println "FOOD"
             (pr-str {:world (str set-name "/" (.getName (io/file f)))
                      :seed seed
                      :ok (:ok r)
                      :reason (:reason r)
                      :min (quot (long (:run/ms r 0)) 60000)
                      :dim (dimension/of w)
                      :food (:player/food w)
                      :health (:player/health w)
                      :edible (hunt/edible-held w)
                      :foods (select-keys held food-names)
                      :respawns (:run/respawns r)
                      :eaten (:run/eaten r)
                      :forage (:hunt/forage w)}))
    (flush)))

(let [[set-name n seed] *command-line-args*
      seed (Long/parseLong (or seed "1"))
      dir (str "cache/sim-worlds/" set-name)
      files (take (Long/parseLong n) (simrace/world-files dir))
      nether-path (simrace/nether-file (first files))
      nether (when (.exists (io/file nether-path)) (simrace/read-nether nether-path))
      jobs (atom files)
      take! (fn [] (ffirst (swap-vals! jobs rest)))
      worker (fn []
               (doseq [f (take-while some? (repeatedly take!))]
                 (try (let [w (cond-> (simrace/prepared (capture/read-world f))
                                nether (simrace/with-nether nether nether-path))
                            r (simrace/run w seed "nether" (* 330 60000) nil)]
                        (line set-name f seed r))
                      (catch Exception e (println "FOOD" (str f) "THREW" (ex-message e)) (flush)))))
      workers (mapv (fn [_] (future (worker))) (range (.availableProcessors (Runtime/getRuntime))))]
  (run! deref workers)
  (shutdown-agents))
