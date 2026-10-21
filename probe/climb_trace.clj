(ns clojurecraft.climb-trace
  "Probe: play a snapshot on, printing each change of the running intent's child and every logged line."
  (:require [clojurecraft.capture :as capture]
            [clojurecraft.sim :as sim]
            [clojurecraft.simrace :as sr]))

(defn -main
  [path ms]
  (let [snap (capture/read-world path)
        w (sr/world-for! snap)
        m (sr/moment-of snap w)
        seen (volatile! nil)
        stop? (fn [world]
                (doseq [e (:bot/effects world)
                        :when (= :log (:effect/kind e))]
                  (println "LOG" (:time/now world) (:effect/message e)))
                (let [i (:plan/intent world)
                      k [(:intent/kind i) (:intent/stage i) (:intent/kind (:intent/child i))
                         (:intent/reason (:intent/child i)) (:intent/op i)]]
                  (when (not= k @seen)
                    (vreset! seen k)
                    (println "INTENT"
                             (:time/now world)
                             (pr-str k)
                             (:player/pos world)
                             (:player/health world)
                             (:player/food world))))
                false)
        r
        (sim/play-from sr/step m stop? (Long/parseLong ms) (sr/go-of w (:snapshot/until snap)) nil)]
    (println "END" (pr-str (:plan/intent (:world r))))
    (shutdown-agents)))
