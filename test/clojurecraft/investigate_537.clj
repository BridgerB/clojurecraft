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
            [clojurecraft.memory :as memory]
            [clojurecraft.physics :as physics]
            [clojurecraft.plan :as plan]
            [clojurecraft.sim :as sim]
            [clojurecraft.simrace :as simrace]
            [datascript.core :as d]))

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
                      (reduce min (map #(nth % 2) bricks)) (reduce max #(nth % 2) bricks)])}))

(defn fortress-search-brick
  [world]
  (let [bl (:plan/blacklist world #{})
        feet (intent/feet world)
        trail (or (:trail/cells (:fortress/trail world)) [])
        arrived-targets (set (d/q '[:find [?t ...] :where [?e :intention/kind ?k]
                                    [(contains? #{:tunnel-to :walk} ?k)] [?e :intention/event :done]
                                    [?e :intention/target ?t]]
                                  (:world/facts world)))
        visited-pts (into (conj arrived-targets feet) trail)
        unvisited? (fn [b]
                     (not-any? (fn [vp]
                                 (<= (physics/distance (physics/centre vp) (physics/centre b)) 6.0))
                               visited-pts))
        candidates (->> (fortress/bricks world)
                        (remove bl)
                        (filter unvisited?))]
    (memory/nearest-of (physics/centre feet) fortress/sight-radius candidates)))

(defn custom-search
  [world]
  (if-let [b (fortress-search-brick world)]
    {:intent/kind :tunnel-to
     :intent/target b
     :intent/steps fortress/steps-per-tunnel
     :intent/bridge? true}
    (when-let [s (fortress/exploring world)]
      {:intent/kind :tunnel-to
       :intent/target (fortress/leg-target s)
       :intent/steps fortress/steps-per-tunnel
       :intent/search s})))

(defn run-and-log!
  [world-file seed until max-ms custom?]
  (println "\n========================================================")
  (println "INVESTIGATING:" world-file "seed:" seed "custom?:" custom?)
  (println "========================================================")
  (let [npath (simrace/nether-file world-file)
        w-nether (simrace/prepared (capture/read-world npath))
        nether (:capture/nether w-nether)
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
         (let [world (if (and custom?
                              (= :active (:plan/status world))
                              (= :tick (:event/kind event))
                              (= :blaze-rods (:goal/id (plan/choose world plan/goals)))
                              (nil? (:plan/intent world))
                              (nil? (blaze/nearest-spawner world))
                              (nil? (first (blaze/at-hand-blaze world)))
                              (nil? (first (blaze/quarry world)))
                              (nil? (second (blaze/rod-lying world)))
                              (not (and (blaze/hurt? world) (blaze/healing world)))
                              (not (and (blaze/wary? world) (blaze/warily world)))
                              (not (and (blaze/outnumbered? world) (blaze/hide world))))
                       (if-let [si (custom-search world)]
                         (assoc world
                                :plan/intent
                                (assoc si
                                       :intent/status :active
                                       :intent/id (inc (:plan/intents world 0))
                                       :intent/goal :blaze-rods))
                         world)
                       world)
               w' (simrace/step world event)
               t (:time/now w' 0)
               dim (dimension/of w')
               pos (:player/pos w')
               feet (when pos (intent/feet w'))
               goal (plan/choose w' plan/goals)
               goal-id (:goal/id goal)
               current-intent (:plan/intent w')]
           ;; Packet logging during blaze-rods
           (when (and (= :packet (:event/kind event)) (= goal-id :blaze-rods))
             (let [p (:event/packet event)]
               (case (:packet/name p)
                 :set-health
                 (println (format "[SET-HEALTH] t=%d hp=%.1f food=%d sat=%.1f"
                                  t
                                  (double (:health p))
                                  (long (or (:food p) -1))
                                  (double (or (:saturation p) 0.0))))
                 :damage-event
                 (println (format "[DAMAGE-EVENT] t=%d eid=%s type=%s cause=%s"
                                  t
                                  (:entity-id p)
                                  (:source-type p)
                                  (:source-cause p)))
                 nil)))
           ;; Nether entry
           (when (and (= dim dimension/nether) (not @entered-nether?))
             (reset! entered-nether? true)
             (println (format "[NETHER-ENTRY] t=%d pos=%s" t (pr-str pos))))
           ;; Fortress reached
           (when (and (contains? (:plan/reached w') :fortress) (not @reached-fortress?))
             (reset! reached-fortress? true)
             (let [brs (fortress/bricks w')
                   arrived (filter #(fortress/arrived? w' %) brs)
                   sp (blaze/nearest-spawner w')
                   xs (map first brs)
                   ys (map second brs)
                   zs (map #(nth % 2) brs)]
               (println
                (format
                 "[FORTRESS-REACHED] t=%d pos=%s feet=%s arrived-bricks=%s all-bricks-count=%d nearest-spawner-known=%s"
                 t
                 (pr-str pos)
                 (pr-str feet)
                 (pr-str arrived)
                 (count brs)
                 (pr-str sp)))
               (println (format "[BRICK-BOUNDS-AT-REACH] X:[%d %d] Y:[%d %d] Z:[%d %d]"
                                (reduce min xs)
                                (reduce max xs)
                                (reduce min ys)
                                (reduce max ys)
                                (reduce min zs)
                                (reduce max zs)))))
           ;; Blaze-rods activity
           (when (= goal-id :blaze-rods)
             (when (or (> (- t @last-log) 15000) (not= (:intent/id current-intent) @last-intent-id))
               (reset! last-log t)
               (reset! last-intent-id (:intent/id current-intent))
               (let [sp (blaze/nearest-spawner w')
                     bls (blaze/blazes w')
                     wats (blaze/watchers w')]
                 (println
                  (format
                   "[BLAZE-RODS] t=%d pos=%s hp=%.1f food=%d intent=%s target=%s spawner=%s bls=%d wats=%d rods=%d hurt?=%s wary?=%s heals?=%s"
                   t
                   (pr-str pos)
                   (double (:player/health w' 20.0))
                   (long (:player/food w' 20))
                   (:intent/kind current-intent)
                   (pr-str (:intent/target current-intent))
                   (pr-str sp)
                   (count bls)
                   (count wats)
                   (blaze/rods w')
                   (blaze/hurt? w')
                   (blaze/wary? w')
                   (blaze/heals? w'))))))
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
      (println "[RUN-FINISHED] ms:" (:run/ms res)
               "reason:" (:reason res)
               "pos:" (:pos res)
               "rods:" (blaze/rods (:run/end res))))))

(defn -main
  [& _args]
  (let [cases [["cache/sim-worlds/R1/1.edn.gz" 1 false (* 60000 65)]
               ["cache/sim-worlds/R1/17.edn.gz" 1 false (* 60000 80)]]]
    (doseq [[world seed custom? ms] cases]
      (when (.exists (io/file (str world)))
        (run-and-log! (io/file (str world)) seed "blaze-rods" ms custom?))))
  (shutdown-agents))
