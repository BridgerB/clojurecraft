(require '[clojurecraft.fixture :as fx]
         '[clojurecraft.game :as game]
         '[clojurecraft.gym :as gym]
         '[clojurecraft.inventory :as inventory]
         '[clojurecraft.memory :as memory]
         '[clojurecraft.plan :as plan]
         '[clojurecraft.sim :as sim]
         '[clojurecraft.wood :as wood]
         '[clojurecraft.world :as world])

(def blocks
  (merge (into {} (for [x (range 2 5) z (range 2 5) y (range 64 66)] [[x y z] 1]))
         (into {} (for [y (range 66 69)] [[3 y 3] 137]))
         {[3 69 3] 252}))

(let [[w s] (sim/run (game/compose game/step plan/step)
                     (game/init fx/opts)
                     (sim/init {:column (world/column-bytes blocks) :spawn [0.5 64.0 0.5]})
                     #(or (plan/done? %) (plan/failed? %))
                     (:gym/timeout-ms (gym/gym "wood"))
                     {:event/kind :go :go/goals [:wood]})]
  (println (pr-str (select-keys w
                                [:plan/status :plan/reason :plan/waiting :plan/intent :plan/last
                                 :player/pos :player/inventory])))
  (println (pr-str (memory/intentions w)))
  (println (pr-str (:sim/violations s)))
  (println (pr-str {:logs (inventory/logs-held w) :done (plan/done? w)})))
(shutdown-agents)
