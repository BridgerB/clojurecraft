(ns probe.fortress3-trace
  "Issue #284 probe: the tail of a snapshot's judge, one line a change of feet, ground, step, op or child."
  (:require [clojurecraft.capture :as capture]
            [clojurecraft.intent :as intent]
            [clojurecraft.plan :as plan]
            [clojurecraft.sim :as sim]
            [clojurecraft.simrace :as sr]))

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
    (doseq [b (take-last 120 @trace)] (println "TRACE" (pr-str b)))
    (println "TRACE end" (pr-str (brief (:world r))))
    (shutdown-agents)))
