(ns probe.return-trace
  "Probe: replay a snapshot's run from its landing, print the lead-up to each death and every
   intent change and 10 s sample after it."
  (:require [clojurecraft.capture :as capture]
            [clojurecraft.simrace :as simrace]))

(def real-step simrace/step)

(defn line
  [world]
  (let [i (:plan/intent world)]
    [(:time/now world) (:player/dimension world)
     (some->> (:player/pos world)
              (mapv long)) (:player/health world) (:player/food world)
     (select-keys i [:intent/kind :intent/goal :intent/status :intent/target :intent/stage])]))

(defn tracer
  []
  (let [ring (atom [])
        last-t (atom 0)
        last-kind (atom nil)
        deaths (atom 0)]
    (fn [world event]
      (let [w (real-step world event)
            d (:stats/deaths w 0)
            t (:time/now w 0)
            kind (get-in w [:plan/intent :intent/kind])
            nether? (= "minecraft:the_nether" (:player/dimension w))
            sample? (>= (- t @last-t) (if (pos? @deaths) 10000 2000))]
        (when (and (not= kind @last-kind) (or nether? (pos? @deaths)))
          (swap! ring #(vec (take-last 60 (conj % (into [:kind] (line w)))))))
        (when (and sample? (or nether? (pos? @deaths)))
          (reset! last-t t)
          (swap! ring #(vec (take-last 60 (conj % (into [:s] (line w))))))
          (when (pos? @deaths) (println "AFTER" (pr-str (line w)))))
        (when (and (not= kind @last-kind) (pos? @deaths)) (println "AFTERKIND" (pr-str (line w))))
        (reset! last-kind kind)
        (when (> d @deaths)
          (println "DEATH" d "at" t)
          (doseq [l @ring] (println "LEAD" (pr-str l)))
          (reset! deaths d))
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
