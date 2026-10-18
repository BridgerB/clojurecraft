(require '[clojurecraft.capture :as capture]
         '[clojurecraft.simrace :as simrace]
         '[clojurecraft.blocks :as blocks]
         '[clojurecraft.terrain :as terrain]
         '[clojurecraft.memory :as memory]
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
                   :let [d (physics/distance eye (physics/centre pos))
                         h (physics/horizontal-distance eye pos)]
                   :when (= :ice (nm world [x (inc y) z]))]
               {:pos pos :d (long d) :h (long h) :sky? (terrain/under-sky? world pos)})]
    (out "==" p "iced pools" (count rows))
    (doseq [r (take 15 (sort-by :d rows))] (out "  " (pr-str r)))
    (let [pools-xz (for [[x y z :as pos] pools
                         :when (and (= :water (nm world pos)) (terrain/under-sky? world pos))]
                     {:pos pos
                      :h (long (physics/horizontal-distance eye pos))
                      :above (nm world [x (inc y) z])})]
      (out "  sky pools" (count pools-xz))
      (doseq [r (take 8 (sort-by :h pools-xz))] (out "   sky" (pr-str r))))))
(shutdown-agents)
