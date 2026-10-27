(ns clojurecraft.investigate-537
  "Investigation script for #537 and #262: why bots reach fortress and search hundreds of blocks away."
  (:require [clojure.java.io :as io]
            [clojurecraft.blaze :as blaze]
            [clojurecraft.blocks :as blocks]
            [clojurecraft.capture :as capture]
            [clojurecraft.chunk :as chunk]
            [clojurecraft.dimension :as dimension]
            [clojurecraft.fortress :as fortress]
            [clojurecraft.game :as game]
            [clojurecraft.intent :as intent]
            [clojurecraft.plan :as plan]
            [clojurecraft.sim :as sim]
            [clojurecraft.simrace :as simrace]))

(set! *warn-on-reflection* true)

(defn analyze-nether
  [nether]
  (let [decoded (:decoded nether)
        spawners (chunk/find-blocks decoded (fn [id] (= (blocks/name-of id) :spawner)))
        bricks (chunk/find-blocks decoded fortress/brick?)]
    {:spawners (mapv (fn [[x y z]] [x y z]) spawners)
     :brick-count (count bricks)
     :brick-bounds (when (seq bricks)
                     [(reduce min (map first bricks)) (reduce max (map first bricks))
                      (reduce min (map second bricks)) (reduce max (map second bricks))
                      (reduce min (map #(nth % 2) bricks)) (reduce max (map #(nth % 2) bricks))])}))

(defn run-and-log!
  [world-file seed until max-ms]
  (println "\n========================================================")
  (println "INVESTIGATING:" world-file "seed:" seed "until:" until)
  (println "========================================================")
  (let [w (assoc (simrace/prepared (capture/read-world world-file)) :capture/file (str world-file))
        npath (simrace/nether-file world-file)
        nether (simrace/read-nether npath)
        w-nether (simrace/with-nether w nether npath)
        nether-info (analyze-nether nether)]
    (println "Nether file:" npath)
    (println "Nether spawners:" (:spawners nether-info))
    (println "Nether fortress brick count:" (:brick-count nether-info)
             "bounds:" (:brick-bounds nether-info))
    (let
      [last-log (atom 0)
       reached-fortress? (atom false)
       entered-nether? (atom false)
       last-intent-id (atom nil)
       instrumented-step
       (fn [world event]
         (let [w' (simrace/step world event)
               t (:time/now w' 0)
               dim (dimension/of w')
               pos (:player/pos w')
               feet (when pos (intent/feet w'))
               goal (plan/choose w' plan/goals)
               goal-id (:goal/id goal)
               current-intent (:plan/intent w')]
           ;; Nether entry
           (when (and (= dim dimension/nether) (not @entered-nether?))
             (reset! entered-nether? true)
             (println (format "[NETHER-ENTRY] t=%d pos=%s" t (pr-str pos))))
           ;; Fortress reached
           (when (and (contains? (:plan/reached w') :fortress) (not @reached-fortress?))
             (reset! reached-fortress? true)
             (let [brs (fortress/bricks w')
                   arrived (filter #(fortress/arrived? w' %) brs)
                   sp (blaze/nearest-spawner w')]
               (println
                (format
                 "[FORTRESS-REACHED] t=%d pos=%s feet=%s search=%s arrived-bricks=%s all-bricks-count=%d nearest-spawner-known=%s"
                 t
                 (pr-str pos)
                 (pr-str feet)
                 (pr-str (:fortress/search w'))
                 (pr-str arrived)
                 (count brs)
                 (pr-str sp)))))
           ;; Blaze-rods activity
           (when (= goal-id :blaze-rods)
             (when (or (> (- t @last-log) 30000) (not= (:intent/id current-intent) @last-intent-id))
               (reset! last-log t)
               (reset! last-intent-id (:intent/id current-intent))
               (let [sp (blaze/nearest-spawner w')
                     bls (blaze/blazes w')]
                 (println
                  (format
                   "[BLAZE-RODS] t=%d pos=%s intent=%s target=%s search=%s spawner=%s blazes=%d rods=%d"
                   t
                   (pr-str pos)
                   (:intent/kind current-intent)
                   (pr-str (:intent/target current-intent))
                   (pr-str (:intent/search current-intent))
                   (pr-str sp)
                   (count bls)
                   (blaze/rods w'))))))
           w'))
       opts {:host "sim" :port 0 :name "Clj_sim"}
       res (sim/run+ instrumented-step
                     (game/init opts)
                     (sim/init {:columns (:capture/columns w-nether)
                                :decoded (:capture/decoded w-nether)
                                :spawn (simrace/spawn-of w-nether)
                                :seed seed
                                :tick-seed seed
                                :difficulty simrace/difficulty
                                :day-time (simrace/day-time-of seed)
                                :nether (:capture/nether w-nether)})
                     #(or (plan/done? %) (plan/failed? %))
                     max-ms
                     (simrace/go-of w-nether until))]
      (println "[RUN-FINISHED] ms:" (:run/ms res) "reason:" (:reason res) "pos:" (:pos res)))))

(defn -main
  [& _args]
  (let [cases [["cache/sim-worlds/R2/12.edn.gz" 2] ["cache/sim-worlds/R3/4.edn.gz" 2]
               ["cache/sim-worlds/R1/1.edn.gz" 1] ["cache/sim-worlds/R1/17.edn.gz" 1]]]
    (doseq [[world seed] cases]
      (when (.exists (io/file (str world)))
        (run-and-log! (io/file (str world)) seed "blaze-rods" (* 60000 200)))))
  (shutdown-agents))
