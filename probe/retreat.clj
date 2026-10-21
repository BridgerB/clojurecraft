(require '[clojure.math :as math]
         '[clojurecraft.capture :as capture]
         '[clojurecraft.simrace :as simrace]
         '[clojurecraft.sim :as sim]
         '[clojurecraft.plan :as plan]
         '[clojurecraft.intent :as intent]
         '[clojurecraft.path :as path]
         '[clojurecraft.stairs :as stairs]
         '[clojurecraft.stone :as stone]
         '[clojurecraft.terrain :as terrain]
         '[clojurecraft.blocks :as blocks])

(defn dirs-report
  [world feet]
  (for [d stairs/directions
        :let [cells (stairs/level-cells feet d)]]
    {:dir d
     :cells (mapv (fn [c] [c
                           (some-> (terrain/block-at world c)
                                   blocks/name-of) (path/classify world c)])
                  cells)
     :level-safe (stairs/level-safe? world cells)
     :clear-way (stairs/clear-way? world cells)
     :level-clear (stairs/level-clear? world cells)}))

(doseq [p *command-line-args*]
  (let [snap (capture/read-world p)
        w0 (or (simrace/own-world snap)
               (simrace/prepared (capture/read-world (:snapshot/file snap))))
        m (simrace/moment-of snap w0)
        n (atom 0)
        observe
        (fn [world]
          (let [i (:plan/intent world)]
            (when (and (= :walk (:intent/kind i))
                       (= :retreat (:intent/for i))
                       (< @n 2)
                       (not= (:intent/id i) (:last-id @(def st (atom {})))))
              (swap! st assoc :last-id (:intent/id i))
              (swap! n inc)
              (let [feet (intent/feet world)]
                (println "RETREAT at" (:time/now world) "feet" feet "target" (:intent/target i))
                (println " tunnel-heading" (stone/tunnel-heading world))
                (println " deeper" (stone/deeper world))
                (println " boxed-at" (:plan/boxed-at world)
                         "route" (count (:plan/boxed-route world))
                         "unreach" (:plan/retreat-unreachable world))
                (doseq [r (dirs-report world feet)] (println "  " (pr-str r)))))
            (or (plan/failed? world) (plan/done? world) (>= @n 2))))]
    (println "==" p)
    (sim/play-from simrace/step
                   m
                   observe
                   (simrace/window-of snap)
                   (simrace/go-of w0 (:snapshot/until snap))
                   nil)))
(shutdown-agents)
