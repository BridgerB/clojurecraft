(ns kit.r4
  (:require [clojurecraft.blocks :as blocks]
            [clojurecraft.capture :as capture]
            [clojurecraft.memory :as memory]
            [clojurecraft.path :as path]
            [clojurecraft.simrace :as sr]
            [clojurecraft.terrain :as terrain]))

(defn -main
  "Prints the last intentions before the moment, a column map round the feet, and routes from the feet."
  [& [path]]
  (let [snap (capture/read-world path)
        mom (sr/moment-of snap (sr/world-for! snap))
        w (:world mom)
        [fx fy fz] (mapv long [2267 65 2329])]
    (doseq [f (take-last 25 (sort-by :intention/at (memory/intentions w)))]
      (println "HIST"
               (pr-str (select-keys f
                                    [:intention/at :intention/event :intention/kind
                                     :intention/target :intention/reason]))))
    (doseq [dy [1 0 -1 -2 -3]]
      (println "LAYER dy" dy)
      (doseq [dz (range -6 7)]
        (println "  "
                 (clojure.string/join (for [dx (range -8 7)]
                                        (let [b (terrain/block-at w
                                                                  [(+ fx dx) (+ fy dy) (+ fz dz)])]
                                          (cond (nil? b) \?
                                                (zero? b) \.
                                                :else (first (name (blocks/name-of b))))))))))
    (doseq [g [[2265 65 2333] [2262 65 2333] [2262 65 2330]]]
      (println "PLAN"
               g
               (pr-str (select-keys (path/plan w [fx fy fz] {:goal/kind :block :goal/pos g})
                                    [:path/status :path/cost]))))
    (shutdown-agents)))
