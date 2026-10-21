(ns clojurecraft.pocket-trace
  "Probe: play a snapshot on, printing the body every k ticks while the intent (or its child) is of a watched kind."
  (:require [clojurecraft.blocks :as blocks]
            [clojurecraft.capture :as capture]
            [clojurecraft.sim :as sim]
            [clojurecraft.simrace :as sr]
            [clojurecraft.terrain :as terrain]))

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
                    (when (= 1 @n)
                      (let [[px py pz] (:player/pos world)
                            fx (long (clojure.math/floor px))
                            fy (long (clojure.math/floor py))
                            fz (long (clojure.math/floor pz))]
                        (println "CELLS feet"
                                 [fx fy fz]
                                 "columns y-1..y+5 (S solid, wN water level N, l lava, . air)")
                        (doseq [dz [-1 0 1]
                                dx [-1 0 1]]
                          (println "COL dx"
                                   dx
                                   "dz"
                                   dz
                                   (clojure.string/join (for [y (range (dec fy) (+ fy 6))]
                                                          (let [p [(+ fx dx) y (+ fz dz)]
                                                                fl (terrain/fluid-at world p)
                                                                id (terrain/block-at world p)]
                                                            (cond (nil? id) \?
                                                                  (= :water (:fluid/kind fl))
                                                                  (str "w" (:fluid/level fl))
                                                                  (= :lava (:fluid/kind fl)) \l
                                                                  (blocks/collision-top id) \S
                                                                  :else \.))))))))
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
