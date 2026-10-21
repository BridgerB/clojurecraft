(require '[clojurecraft.stone-test :as st]
         '[clojurecraft.blocks :as blocks]
         '[clojurecraft.world :as world]
         '[clojurecraft.intent :as intent]
         '[clojurecraft.path :as path]
         '[clojurecraft.stone :as stone])

(let [dirt (blocks/default-state :dirt)
      ground
      (into {} (for [x (range 16) z (range 16) y (range (if (< x 10) 50 60) 64)] [[x y z] dirt]))
      cave (into {} (for [x (range 11 15) z (range 3 7) y (range 55 58)] [[x y z] blocks/air]))
      stairs (into {} (for [i (range 7) dy (range 3)] [[(- 8 i) (+ (- 64 i) dy) 5] blocks/air]))
      w (assoc (st/standing 64 :wooden_pickaxe)
               :world/chunks
               {[0 0] (world/column (merge ground cave stairs))})
      row (st/row :mine-stone)]
  (doseq [i [0 3 5 6]]
    (let [wi (assoc w :player/pos [(+ 0.5 (- 8 i)) (double (- 64 i)) 5.5])
          feet (intent/feet wi)
          exposed (take 3 (#'stone/exposed-of-states wi (#'stone/states-of-row row)))
          surfaces (keep #(stone/surface-over wi %) exposed)]
      (println "STEP" i "feet" feet "exposed" (vec exposed) "surfaces" (vec surfaces))
      (doseq [s surfaces]
        (let [walk {:intent/kind :walk :intent/target s :intent/for :over-stone}
              r (path/plan wi (path/feet-cell (:player/pos wi)) (intent/goal-of walk) {})]
          (println "  to"
                   s
                   (:path/status r)
                   (count (:path/waypoints r))
                   (intent/routable? wi walk))))
      (println "  over-stone" (stone/over-stone wi row)))))
(shutdown-agents)
