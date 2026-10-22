(ns probe.portal3-trace
  "Probe: the intents of a judged snapshot, one line each (id change), with the planner's facts."
  (:require [clojurecraft.capture :as capture]
            [clojurecraft.intent :as intent]
            [clojurecraft.main :as main]
            [clojurecraft.path :as path]
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
        seen2 (atom nil)
        n2 (atom 0)
        stop? (fn [wd]
                (let [i (:plan/intent wd)
                      ch (:intent/child i)
                      id (when i [(:intent/id i) (:intent/id ch) (:intent/status ch)])]
                  (when (and id (not= id @seen) (< @n 400))
                    (reset! seen id)
                    (swap! n inc)
                    (println "TRACE"
                             path
                             (:time/now wd)
                             (pr-str (select-keys i
                                                  [:intent/id :intent/kind :intent/for
                                                   :intent/target :intent/reason :intent/dir
                                                   :intent/to-y]))
                             "op"
                             (pr-str (select-keys (:intent/op i)
                                                  [:build/op :build/step :build/pos :build/cell
                                                   :build/stand]))
                             "child"
                             (pr-str (select-keys ch
                                                  [:intent/kind :intent/target :intent/status
                                                   :intent/reason :intent/for :intent/stage]))
                             "feet" (pr-str (intent/feet wd))
                             "go-at" (pr-str (:plan/go-at wd))
                             "unwalk" (:plan/unwalkable wd)
                             "bl" (count (:plan/blacklist wd))
                             "water?" (:player/in-water? wd)))
                  (when (and id (= :walk (:intent/kind i)) (not= id @seen2) (< @n2 4))
                    (reset! seen2 id)
                    (swap! n2 inc)
                    (let [r (path/plan wd (path/feet-cell (:player/pos wd)) (intent/goal-of i))]
                      (println "ROUTE"
                               path
                               (:intent/target i)
                               (:path/status r)
                               (count (:path/waypoints r))
                               (pr-str (take 25 (:path/waypoints r)))
                               (pr-str (map #(path/classify wd %) (take 25 (:path/waypoints r)))))))
                  (or (plan/done? wd) (plan/failed? wd) (contains? (:plan/reached wd) stage))))]
    (sim/play-from sr/step m stop? (sr/window-of snap) (sr/go-of w (:snapshot/until snap)) nil)
    (println "END" path)))

(doseq [p (clojure.string/split (System/getenv "SNAPS") #",")] (trace! p))
