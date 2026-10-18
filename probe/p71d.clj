(require '[clojurecraft.capture :as capture]
         '[clojurecraft.simrace :as simrace]
         '[clojurecraft.sim :as sim]
         '[clojurecraft.plan :as plan]
         '[clojurecraft.intent :as intent]
         '[clojure.string :as str])
(def logs (atom []))
(defn step*
  [world event]
  (let [w (simrace/step world event)]
    (doseq [e (:bot/effects w)
            :when (= :log (:effect/kind e))]
      (swap! logs conj [(:time/now w) (intent/feet w) (:effect/message e)]))
    w))
(doseq [p *command-line-args*]
  (reset! logs [])
  (let [snap (capture/read-world p)
        w (simrace/world-for! snap)
        m (simrace/moment-of snap w)
        until (:snapshot/until snap)
        stop? #(or (plan/done? %) (plan/failed? %))
        r (sim/play-from step* m stop? (simrace/window-of snap) (simrace/go-of w until) nil)]
    (println "==" p (:time/now (:world m)))
    (doseq [[t f msg] @logs] (println t f (str/replace (str msg) #"\s+" " ")))
    (println "  end" (:plan/status (:world r)) (:plan/reason (:world r)))))
(shutdown-agents)
