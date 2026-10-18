(ns probe.castone-trace
  "A probe for the cast-one batch (issues #135 #130 #74 #45 #44 #41): each snapshot played on from
   its moment as the judge plays it, with a line whenever what the intent is doing changes (its
   kind, the build's op, its child and grandchild) or every every-ms, so the whole road to the
   failure reads as text: the first head-lines and the last tail-lines of it. Run on a runner
   (probe.yml), never the Mac:

     clojure -M -i probe/castone_trace.clj -e '(probe.castone-trace/-main \"test/issues/41/x.edn.gz\")'"
  (:require [clojurecraft.build :as build]
            [clojurecraft.capture :as capture]
            [clojurecraft.intent :as intent]
            [clojurecraft.memory :as memory]
            [clojurecraft.plan :as plan]
            [clojurecraft.scene :as scene]
            [clojurecraft.sim :as sim]
            [clojurecraft.simrace :as simrace]
            [clojurecraft.stone :as stone]))

(def every-ms 30000)
(def head-lines 100)
(def tail-lines 220)

(defn doing
  "What the running intent is doing, as small values: its kind, its op, its child and the child's."
  [i]
  (let [brief #(some-> %
                       scene/brief-map
                       (dissoc :intent/started :intent/best-tick :intent/planned-chunks))]
    {:i (brief (select-keys i
                            [:intent/kind :intent/for :intent/build :intent/target :intent/stage]))
     :op (some-> (:intent/op i)
                 brief)
     :c (brief (:intent/child i))
     :cc (brief (:intent/child (:intent/child i)))}))

(defn tracer
  "A bot reducer that is simrace/step and keeps a line (into lines) when what the intent is doing
   changes or every-ms has passed since the last, t0 the moment's time."
  [t0 lines]
  (let [seen (atom [nil -1])]
    (fn [world event]
      (let [w (simrace/step world event)]
        (when (= :tick (:event/kind event))
          (let [d (doing (:plan/intent w))
                t (:time/now w)
                [last-d last-t] @seen]
            (when (or (not= d last-d) (> (- t last-t) every-ms))
              (reset! seen [d t])
              (swap! lines conj
                (str "T+" (quot (- t t0) 1000)
                     " " (pr-str (select-keys (scene/facts w) [:pos :health :status]))
                     " " (pr-str d)
                     " wait " (pr-str (:plan/waiting w))
                     " reason " (pr-str (:plan/reason w)))))))
        w))))

(defn lava-line
  "What the lava hunt sees from the world: sources loaded, near, the hunt's target, the pockets."
  [w]
  (println "LAVA"
           (pr-str {:feet (intent/feet w)
                    :sources (count (build/lava-sources w))
                    :near (count (build/sources-near w))
                    :target (build/hunt-target w)
                    :site (:site/origin (memory/site w))
                    :given-up (memory/given-up-sites w)
                    :boxed-at (:plan/boxed-at w)
                    :feet-boxed? (contains? (:plan/boxed-at w #{}) (intent/feet w))
                    :stairs (stone/stairs w {:goal/depth build/lava-depth})})))

(defn trace!
  "Play the snapshot at path on from its moment with the tracer and print the road."
  [path]
  (let [snap (capture/read-world path)
        w (simrace/world-for! snap)
        {:keys [world] :as m} (simrace/moment-of snap w)
        stage (simrace/stage-of snap)
        t0 (:time/now world)
        lines (atom [])]
    (println "TRACE" path (pr-str (:snapshot/signature snap)) "window" (simrace/window-of snap))
    (lava-line world)
    (let [{world' :world crashed :crashed}
          (sim/play-from (tracer t0 lines)
                         m
                         #(or (plan/done? %) (plan/failed? %) (contains? (:plan/reached %) stage))
                         (simrace/window-of snap)
                         (simrace/go-of w (:snapshot/until snap))
                         nil)
          all @lines]
      (when crashed (println "CRASHED" crashed))
      (doseq [l (take head-lines all)] (println l))
      (when (> (count all) (+ head-lines tail-lines)) (println "... " (count all) "lines"))
      (doseq [l (take-last tail-lines (drop head-lines all))] (println l))
      (lava-line world')
      (doseq [line (scene/slice world')] (println "SLICE end" line))
      (doseq [line (take-last 40 (simrace/story world' t0))] (println "STORY" (pr-str line))))))

(defn -main "Trace each path given." [& paths] (doseq [p paths] (trace! p)) (shutdown-agents))
