(ns probe.return-trace
  "Probe: replay a snapshot's run from its landing and print, after the first death, the site on
   record and every intent change with its whole map, and a position every 30 s."
  (:require [clojurecraft.capture :as capture]
            [clojurecraft.memory :as memory]
            [clojurecraft.simrace :as simrace]))

(def real-step simrace/step)

(defn line
  [world]
  [(:time/now world) (:player/dimension world)
   (some->> (:player/pos world)
            (mapv long)) (:player/health world)
   (into {} (remove (comp #{:intent/id} key)) (:plan/intent world))])

(defn tracer
  []
  (let [last-t (atom 0)
        last-kind (atom nil)
        deaths (atom 0)]
    (fn [world event]
      (let [w (real-step world event)
            d (:stats/deaths w 0)
            t (:time/now w 0)
            intent (:plan/intent w)
            key* [(:intent/kind intent) (:intent/target intent) (:intent/to-y intent)]]
        (when (> d @deaths)
          (reset! deaths d)
          (println "DEATH" d
                   "at" t
                   "site" (pr-str (memory/site w))
                   "given-up" (pr-str (memory/given-up-sites w))))
        (when (and (pos? @deaths) (not= key* @last-kind)) (println "KIND" (pr-str (line w))))
        (when (and (pos? @deaths) (>= (- t @last-t) 30000))
          (reset! last-t t)
          (println "POS" (pr-str (line w))))
        (reset! last-kind key*)
        w))))

(defn -main
  [& paths]
  (doseq [p paths]
    (println "TRACE" p)
    (let [snap (capture/read-world p)
          w (simrace/world-for! snap)]
      (with-redefs [simrace/step (tracer)]
        (let [r (simrace/replay snap w (* 60000 simrace/default-max-minutes))]
          (println "REPLAY" (pr-str (select-keys r [:run/signature :run/ms :run/respawns]))))))
    (flush))
  (shutdown-agents))
