(ns probe.fortress3-trace
  "Issue #284 probe: the tail of a snapshot's judge, one line a change of feet, ground, step, op or child."
  (:require [clojurecraft.capture :as capture]
            [clojurecraft.intent :as intent]
            [clojurecraft.path :as path]
            [clojurecraft.plan :as plan]
            [clojurecraft.sim :as sim]
            [clojurecraft.simrace :as sr]))

(declare dump)

(defn brief
  [world]
  (let [i (:plan/intent world)
        c (:intent/child i)]
    [(:time/now world) (:player/pos world) (:player/on-ground? world)
     (select-keys i
                  [:intent/id :intent/kind :intent/status :intent/reason :intent/move :intent/from
                   :intent/taken :intent/retreats]) (:intent/op i)
     (when c (select-keys c [:intent/kind :intent/target :intent/status :intent/reason]))]))

(defn -main
  [& [path]]
  (let [snap (capture/read-world path)
        w (sr/world-for! snap)
        {:keys [world] :as m} (sr/moment-of snap w)
        stage (sr/stage-of snap)
        trace (volatile! [])
        last-key (volatile! nil)
        stop? (fn [wd]
                (let [b (brief wd)
                      k (rest b)]
                  (when (not= k @last-key) (vswap! trace conj b) (vreset! last-key k)))
                (or (plan/done? wd) (plan/failed? wd) (contains? (:plan/reached wd) stage)))
        r
        (sim/play-from sr/step m stop? (sr/window-of snap) (sr/go-of w (:snapshot/until snap)) nil)]
    (println "TRACE start" (pr-str (brief world)))
    (doseq [b (take-last 6 @trace)] (println "TRACE" (pr-str b)))
    (println "TRACE end" (pr-str (brief (:world r))))
    (dump (:world r) [402 36 309])
    (shutdown-agents)))

(defn dump
  "The routes round the final feet: classification of the cells, start-cell, and the plan to the stand."
  [world goal]
  (let [at (path/cells world)
        feet (path/feet-cell (:player/pos world))]
    (println "DUMP feet" feet "start" (path/start-cell world feet) "goal" goal)
    (doseq [y [37 36 35 34]
            z [308 309 310]]
      (println "DUMP y" y "z" z (mapv #(at [% y z]) (range 399 405))))
    (println "DUMP standable goal" (path/standable? world goal)
             "lava-near" (path/lava-near? world goal))
    (println "DUMP plan"
             (pr-str (dissoc (path/plan world feet {:goal/kind :cell :goal/pos goal}) :path/cost)))
    (println "DUMP goal-of" (pr-str (intent/goal-of {:intent/kind :walk :intent/target goal})))
    (println "DUMP plan2"
             (pr-str
              (path/plan world feet (intent/goal-of {:intent/kind :walk :intent/target goal}))))))
