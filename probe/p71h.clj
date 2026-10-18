(require '[clojurecraft.capture :as capture]
         '[clojurecraft.simrace :as simrace]
         '[clojurecraft.sim :as sim]
         '[clojurecraft.plan :as plan]
         '[clojurecraft.intent :as intent]
         '[clojure.string :as str])
(def cost (atom {}))
(defn step*
  [world event]
  (let [t0 (System/nanoTime)
        w (simrace/step world event)
        ms (/ (- (System/nanoTime) t0) 1e6)
        i (:plan/intent w)
        k [(:intent/kind i) (:intent/stage i) (:intent/for i) (:event/kind event)]]
    (swap! cost update k (fn [[n t]] [(inc (or n 0)) (+ (or t 0) ms)]))
    w))
(doseq [p *command-line-args*]
  (reset! cost {})
  (let [snap (capture/read-world p)
        w (simrace/world-for! snap)
        m (simrace/moment-of snap w)
        until (:snapshot/until snap)
        stop? #(or (plan/done? %) (plan/failed? %))
        t0 (System/currentTimeMillis)
        r (sim/play-from step* m stop? (simrace/window-of snap) (simrace/go-of w until) nil)]
    (println "==" p "wall" (- (System/currentTimeMillis) t0))
    (doseq [[k [n t]] (take 12 (sort-by (comp - second val) @cost))]
      (println "  " k "calls" n "ms" (long t) "per" (long (/ t (max n 1)))))))
(shutdown-agents)
