(require '[clojure.math :as math]
         '[clojurecraft.capture :as capture]
         '[clojurecraft.simrace :as simrace]
         '[clojurecraft.sim :as sim]
         '[clojurecraft.plan :as plan]
         '[clojurecraft.intent :as intent]
         '[clojure.string :as str])

(defn brief
  [i]
  (select-keys i
               [:intent/id :intent/kind :intent/for :intent/stage :intent/target :intent/to-y
                :intent/status :intent/reason :intent/dir :intent/turns :intent/level-steps]))

(doseq [p *command-line-args*]
  (try (let [snap (capture/read-world p)
             w0 (or (simrace/own-world snap)
                    (simrace/prepared (capture/read-world (:snapshot/file snap))))
             m (simrace/moment-of snap w0)
             n (atom 0)
             prev (atom nil)
             observe (fn [world]
                       (let [i (:plan/intent world)
                             cur [(:intent/id i) (:intent/kind i) (:intent/stage i)
                                  (:plan/waiting world)]]
                         (when (and (not= cur @prev) (< @n 300))
                           (swap! n inc)
                           (println (:time/now world)
                                    (mapv #(math/floor (double %)) (:player/pos world))
                                    (pr-str (brief i))
                                    "boxed" (count (:plan/boxed-at world))
                                    "wait" (pr-str (:plan/waiting world)))
                           (reset! prev cur))
                         (or (plan/failed? world) (plan/done? world))))]
         (println "==" p (pr-str (:snapshot/signature snap)))
         (sim/play-from simrace/step
                        m
                        observe
                        (simrace/window-of snap)
                        (simrace/go-of w0 (:snapshot/until snap))
                        nil))
       (catch Exception e (println "==" p "THREW" (.getName (class e)) (ex-message e)))))
(shutdown-agents)
