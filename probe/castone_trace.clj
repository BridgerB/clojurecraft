(ns probe.castone-trace
  "A probe for the cast-one batch (issues #135 #130 #74 #45 #44 #41): each snapshot played on from
   its moment as the judge plays it, with a line whenever the intent changes or every every-ms,
   so the whole road to the failure reads as text. Run on a runner (probe.yml), never the Mac:

     clojure -M -i probe/castone_trace.clj -e '(probe.castone-trace/-main \"test/issues/41/x.edn.gz\")'"
  (:require [clojurecraft.build :as build]
            [clojurecraft.capture :as capture]
            [clojurecraft.intent :as intent]
            [clojurecraft.memory :as memory]
            [clojurecraft.plan :as plan]
            [clojurecraft.scene :as scene]
            [clojurecraft.sim :as sim]
            [clojurecraft.simrace :as simrace]))

(def every-ms 20000)

(defn tracer
  "A bot reducer that is simrace/step and prints a line when the intent's id changes or every-ms
   has passed since the last, t0 the moment's time."
  [t0]
  (let [seen (atom [nil -1])]
    (fn [world event]
      (let [w (simrace/step world event)]
        (when (= :tick (:event/kind event))
          (let [i (:plan/intent w)
                id (:intent/id i)
                t (:time/now w)
                [last-id last-t] @seen]
            (when (or (not= id last-id) (> (- t last-t) every-ms))
              (reset! seen [id t])
              (println "T+"
                       (quot (- t t0) 1000)
                       (pr-str (select-keys (scene/facts w) [:pos :health :food :intent :status]))
                       "wait" (pr-str (:plan/waiting w))
                       "reason" (pr-str (:plan/reason w))))))
        w))))

(defn lava-line
  "What the lava hunt sees from the world: sources loaded, near, the hunt's target."
  [w]
  (println "LAVA"
           (pr-str {:feet (intent/feet w)
                    :sources (count (build/lava-sources w))
                    :near (count (build/sources-near w))
                    :target (build/hunt-target w)
                    :site (:site/origin (memory/site w))})))

(defn trace!
  "Play the snapshot at path on from its moment with the tracer and print the road."
  [path]
  (let [snap (capture/read-world path)
        w (simrace/world-for! snap)
        {:keys [world] :as m} (simrace/moment-of snap w)
        stage (simrace/stage-of snap)
        t0 (:time/now world)]
    (println "TRACE" path (pr-str (:snapshot/signature snap)) "window" (simrace/window-of snap))
    (lava-line world)
    (let [{world' :world crashed :crashed}
          (sim/play-from (tracer t0)
                         m
                         #(or (plan/done? %) (plan/failed? %) (contains? (:plan/reached %) stage))
                         (simrace/window-of snap)
                         (simrace/go-of w (:snapshot/until snap))
                         nil)]
      (when crashed (println "CRASHED" crashed))
      (lava-line world')
      (doseq [line (scene/slice world')] (println "SLICE end" line))
      (doseq [line (simrace/story world' t0)] (println "STORY" (pr-str line))))))

(defn -main "Trace each path given." [& paths] (doseq [p paths] (trace! p)) (shutdown-agents))
