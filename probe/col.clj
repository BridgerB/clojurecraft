(require '[clojurecraft.capture :as capture]
         '[clojurecraft.simrace :as simrace]
         '[clojurecraft.intent :as intent]
         '[clojurecraft.path :as path]
         '[clojurecraft.stairs :as stairs]
         '[clojure.string :as str])
(defn runs
  [xs]
  (str/join " " (map (fn [g] (str (name (first g)) "x" (count g))) (partition-by identity xs))))
(doseq [p *command-line-args*]
  (let [snap (capture/read-world p)
        w0 (simrace/world-for! snap)
        [world _] (simrace/moment snap w0)
        [x y z] (intent/feet world)]
    (println "==" p "feet" [x y z] "go-at" (:plan/go-at world) "handed?" (stairs/handed? world))
    (println " bore-cells" (stairs/bore-cells world [x y z]))
    (println " column" (runs (map (fn [dy] (path/classify world [x (+ y dy) z])) (range 0 80))))
    (doseq [[dx dz] [[0 0] [1 0] [-1 0] [0 1] [0 -1] [3 0] [-3 0] [0 3] [0 -3] [6 0] [-6 0] [0 6]
                     [0 -6]]]
      (println " col"
               [dx dz]
               (runs (map (fn [dy] (path/classify world [(+ x dx) (+ y dy) (+ z dz)]))
                          (range 0 40)))))))
(shutdown-agents)
