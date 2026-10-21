(ns probe.portalitems
  "Trace what a build did over a snapshot's window: each change of the intent's op and child, and
   each log line the bot said."
  (:require [clojurecraft.blocks :as blocks]
            [clojurecraft.capture :as capture]
            [clojurecraft.intent :as intent]
            [clojurecraft.plan :as plan]
            [clojurecraft.sim :as sim]
            [clojurecraft.simrace :as sr]
            [clojurecraft.terrain :as terrain]))

(defn brief
  [world]
  (let [i (:plan/intent world)
        c (:intent/child i)]
    [(:intent/kind i) (:intent/stage i)
     (some-> i
             :intent/op
             (select-keys [:build/op :build/step :build/item :build/cell]))
     (some-> c
             (select-keys [:intent/kind :intent/item :intent/stage :intent/status :intent/reason
                           :intent/target :intent/for])) (intent/feet world)]))

(defn -main
  [& paths]
  (doseq [path paths]
    (println "=== TRACE" path)
    (let [snap (capture/read-world path)
          w (sr/world-for! snap)
          {:keys [world] :as m} (sr/moment-of snap w)
          last* (volatile! nil)
          t0 (:time/now world)
          stop?
          (fn [wd]
            (doseq [e (:bot/effects wd)
                    :when (= :log (:effect/kind e))]
              (println "SAY" (- (:time/now wd) t0) (:effect/message e)))
            (let [b (brief wd)
                  c (:intent/child (:plan/intent wd))]
              (when (and c (#{:target-gone :needs-tool} (:intent/reason c)) (:intent/target c))
                (let [[x y z] (:intent/target c)]
                  (println "GONE"
                           (:intent/target c)
                           :lava-base
                           blocks/lava-base
                           (into {}
                                 (for [dy [-1 0 1 2]
                                       dx [-1 0 1]
                                       dz [-1 0 1]]
                                   [[dx dy dz]
                                    (some-> (terrain/block-at wd [(+ x dx) (+ y dy) (+ z dz)])
                                            blocks/name-of)])))))
              (when (not= b @last*)
                (vreset! last* b)
                (println "AT" (- (:time/now wd) t0) (pr-str b))))
            (or (plan/done? wd) (plan/failed? wd)))]
      (sim/play-from sr/step m stop? (sr/window-of snap) (sr/go-of w (:snapshot/until snap)) nil)))
  (shutdown-agents))
