(require '[clojurecraft.capture :as capture]
         '[clojurecraft.simrace :as simrace]
         '[clojurecraft.build :as build]
         '[clojurecraft.intent :as intent]
         '[clojurecraft.path :as path]
         '[clojure.string :as str])
(defn out [& xs] (println (str/join " " xs)))
(doseq [p *command-line-args*]
  (let [snap (capture/read-world p)
        w0 (simrace/prepared (capture/read-world (:snapshot/file snap)))
        [world _] (simrace/moment snap w0)
        pos (:build/pos (:intent/op (:plan/intent world)))
        feet (intent/feet world)]
    (out "==" p
         "pos" pos
         "feet" feet
         "within-reach?" (build/within-reach? world pos)
         "feet reaches?" (build/reaches-from? world feet pos))
    (out " reachable-stand" (build/reachable-stand world pos pos))
    (doseq [dy [0 -1 1 -2]
            dx (range -4 5)
            dz (range -4 5)
            :let [c (mapv + pos [dx (inc dy) dz])]
            :when (path/standable? world c)]
      (out "  cand" c
           "reaches?" (build/reaches-from? world c pos)
           "routable?"
           (intent/routable? world {:intent/kind :walk :intent/target c :intent/for :build})))))
(shutdown-agents)
