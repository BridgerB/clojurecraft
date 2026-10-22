(ns probe.portal3-trace
  "Probe: the intents of a judged snapshot, one line each (id change), with the planner's facts."
  (:require [clojurecraft.capture :as capture]
            [clojurecraft.intent :as intent]
            [clojurecraft.main :as main]
            [clojurecraft.plan :as plan]
            [clojurecraft.sim :as sim]
            [clojurecraft.simrace :as sr]))

(defn trace!
  [path]
  (let [snap (capture/read-world path)
        w (sr/world-for! snap)
        {:keys [world] :as m} (sr/moment-of snap w)
        stage (sr/stage-of snap)
        seen (atom nil)
        n (atom 0)
        stop? (fn [wd]
                (let [i (:plan/intent wd)
                      id (:intent/id i)]
                  (when (and id (not= id @seen) (< @n 60))
                    (reset! seen id)
                    (swap! n inc)
                    (println "TRACE"
                             path
                             (:time/now wd)
                             (pr-str (select-keys i
                                                  [:intent/id :intent/kind :intent/for
                                                   :intent/target :intent/reason :intent/dir
                                                   :intent/to-y]))
                             "feet" (pr-str (intent/feet wd))
                             "go-at" (pr-str (:plan/go-at wd))
                             "unwalk" (:plan/unwalkable wd)
                             "bl" (count (:plan/blacklist wd))
                             "water?" (:player/in-water? wd)))
                  (or (plan/done? wd) (plan/failed? wd) (contains? (:plan/reached wd) stage))))]
    (sim/play-from sr/step m stop? (sr/window-of snap) (sr/go-of w (:snapshot/until snap)) nil)
    (println "END" path)))

(doseq [p (clojure.string/split (System/getenv "SNAPS") #",")] (trace! p))
