(ns clojurecraft.pocket-trace
  "Probe: play a snapshot on, printing the body every k ticks while the intent (or its child) is of a watched kind."
  (:require [clojurecraft.capture :as capture]
            [clojurecraft.sim :as sim]
            [clojurecraft.simrace :as sr]))

(defn -main
  [path ms kinds k maxlines]
  (let [snap (capture/read-world path)
        w (sr/world-for! snap)
        m (sr/moment-of snap w)
        watch (set (map keyword (clojure.string/split kinds #",")))
        k (Long/parseLong k)
        maxlines (Long/parseLong maxlines)
        n (volatile! 0)
        lines (volatile! 0)
        fmt (fn [v] (mapv #(when (number? %) (/ (long (* 1000.0 %)) 1000.0)) v))
        stop? (fn [world]
                (doseq [e (:bot/effects world)
                        :when (= :log (:effect/kind e))]
                  (println "LOG" (:time/now world) (:effect/message e)))
                (let [i (:plan/intent world)
                      c (:intent/child i)]
                  (when (or (watch (:intent/kind i)) (watch (:intent/kind c)))
                    (vswap! n inc)
                    (when (and (zero? (mod @n k)) (< @lines maxlines))
                      (vswap! lines inc)
                      (println "T"
                               (:time/now world)
                               (:intent/kind i)
                               (:intent/kind c)
                               (fmt (:player/pos world))
                               (fmt (:player/vel world))
                               (pr-str (:player/controls world))
                               "hit" (:player/horizontal-collision? world)
                               "w" (:player/in-water? world)
                               "l" (:player/in-lava? world)
                               "bank" (:intent/bank i)
                               "tgt" (:intent/target i)
                               "for" (:intent/for i)))))
                false)
        r
        (sim/play-from sr/step m stop? (Long/parseLong ms) (sr/go-of w (:snapshot/until snap)) nil)]
    (println "END" (pr-str (dissoc (:plan/intent (:world r)) :intent/child)))
    (shutdown-agents)))
