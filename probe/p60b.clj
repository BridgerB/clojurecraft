(require '[clojurecraft.capture :as capture]
         '[clojurecraft.simrace :as simrace]
         '[clojurecraft.intent :as intent]
         '[clojurecraft.blocks :as blocks]
         '[clojurecraft.terrain :as terrain]
         '[clojurecraft.memory :as memory]
         '[clojurecraft.scoop :as scoop]
         '[clojurecraft.game :as game]
         '[clojurecraft.physics :as physics]
         '[clojure.string :as str])
(defn out [& xs] (println (str/join " " xs)))
(defn nm
  [world pos]
  (some-> (terrain/block-at world pos)
          blocks/name-of))
(doseq [p *command-line-args*]
  (let [snap (capture/read-world p)
        w0 (simrace/world-for! snap)
        [world _] (simrace/moment snap w0)
        eye (game/eye world)
        pools (vals (memory/pools world :water))
        rows (for [[x y z :as pos] pools
                   :let [d (physics/distance eye (physics/centre pos))]]
               {:pos pos
                :d (long d)
                :loaded? (terrain/chunk-loaded? world pos)
                :at (nm world pos)
                :above (nm world [x (inc y) z])
                :above2 (nm world [x (+ y 2) z])
                :sky? (terrain/under-sky? world pos)})]
    (out "==" p "eye" eye "pools" (count rows))
    (doseq [[k n] (sort-by (comp - val)
                           (frequencies (map (juxt :loaded? :at :above :above2 :sky?) rows)))]
      (out "  " n k))
    (doseq [r (take 12 (sort-by :d (filter :loaded? rows)))] (out "  near" (pr-str r)))))
(shutdown-agents)
