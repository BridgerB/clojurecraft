(require '[clojurecraft.capture :as capture]
         '[clojurecraft.simrace :as simrace]
         '[clojurecraft.sim :as sim]
         '[clojurecraft.swim :as swim]
         '[clojurecraft.stairs :as stairs]
         '[clojurecraft.terrain :as terrain]
         '[clojurecraft.place :as place]
         '[clojurecraft.path :as path]
         '[clojurecraft.physics :as physics]
         '[clojurecraft.game :as game]
         '[clojurecraft.intent :as intent])

(doseq [p *command-line-args*]
  (try
    (let [snap (capture/read-world p)
          w0 (or (simrace/own-world snap)
                 (simrace/prepared (capture/read-world (:snapshot/file snap))))
          m (simrace/moment-of snap w0)
          r (sim/play-from simrace/step
                           m
                           (fn [w]
                             (let [i (:plan/intent w)]
                               (and (= :swim-out (:intent/kind i)) (:intent/child i))))
                           (simrace/window-of snap)
                           (simrace/go-of w0 (:snapshot/until snap))
                           nil)
          w (:world r)
          feet (intent/feet w)
          surface (swim/surface-y w :water feet)
          top (swim/float-top w feet surface)
          [fx _ fz] feet]
      (println "==" p "feet" feet "surface" surface "top" top "pos" (:player/pos w))
      (println "intent" (pr-str (:plan/intent w)))
      (println "filler" (stairs/filler w 1) "under-sky?" (terrain/under-sky? w feet))
      (println "banks" (swim/banks w feet surface #{}))
      (println "bank-block" (swim/bank-block w feet surface))
      (println "notch" (swim/notch w feet surface) "obstacle" (swim/obstacle w feet nil))
      (doseq [dz (range -2 3)
              dx (range -3 3)
              :let [cell [(+ fx dx) top (+ fz dz)]
                    bank [(+ fx dx) (inc top) (+ fz dz)]]
              :when (= :water (terrain/fluid-kind w cell))]
        (println "cand" cell
                 "clear1" (path/clear? w bank)
                 "clear2" (path/clear? w [(+ fx dx) (+ top 2) (+ fz dz)])
                 "open" (swim/in-the-open? w bank)
                 "inside" (place/inside-player? w cell)
                 "support" (place/support w cell)
                 "dist" (physics/distance (game/eye w) (physics/centre cell))
                 "blocks"
                 (mapv #(terrain/block-at w [(+ fx dx) % (+ fz dz)]) (range (dec top) (+ top 4))))))
    (catch Exception e (println "==" p "THREW" (ex-message e)))))
(shutdown-agents)
