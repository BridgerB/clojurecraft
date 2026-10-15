(require '[clojurecraft.capture :as capture]
         '[clojurecraft.simrace :as simrace]
         '[clojurecraft.sim :as sim]
         '[clojurecraft.plan :as plan]
         '[clojurecraft.intent :as intent]
         '[clojurecraft.recipe :as recipe]
         '[clojurecraft.path :as path]
         '[clojurecraft.build :as build]
         '[clojure.string :as str])
(defn out [& xs] (println (str/join " " xs)))
(defn brief
  [i]
  (select-keys i
               [:intent/kind :intent/for :intent/goal :intent/stage :intent/target :intent/build
                :intent/origin :intent/facing :intent/status :intent/reason :intent/op]))
(doseq [p *command-line-args*]
  (let [snap (capture/read-world p)
        w0 (simrace/prepared (capture/read-world (:snapshot/file snap)))
        [world sim] (simrace/moment snap w0)]
    (out "=="
         p
         (:snapshot/signature snap)
         "now" (:time/now world)
         "feet" (intent/feet world)
         "go-at" (:plan/go-at world))
    (out " held" (recipe/counts (:player/inventory world)))
    (out " attempts"
         (:plan/attempts world)
         "waiting"
         (:plan/waiting world)
         (:plan/waiting-since world)
         "boxed-at"
         (count (:plan/boxed-at world #{})))
    (out " intent" (brief (:plan/intent world)))
    (out " child" (brief (:intent/child (:plan/intent world))))
    (out " last" (brief (:plan/last world)))
    (let [op (:intent/op (:plan/intent world))
          pos (:build/pos op)
          [fx fy fz] (intent/feet world)]
      (when pos
        (out " op pos" pos
             "reachable-stand" (build/reachable-stand world pos pos)
             "room-stand" (build/room-stand world (:plan/intent world) pos))
        (doseq [y (range (+ fy 3) (- fy 2) -1)]
          (out " y" y)
          (doseq [z (range (- fz 7) (+ fz 8))]
            (out (str/join (for [x (range (- fx 7) (+ fx 8))]
                             (cond (= [x y z] [fx fy fz]) "@"
                                   (= [x y z] pos) "X"
                                   :else
                                   (case (path/classify world [x y z])
                                     :solid "#"
                                     :water "W"
                                     :clear "."
                                     :unknown "?"
                                     :lava "L"
                                     "~")))))))))
    (loop [world world
           sim sim
           k 0
           prev nil]
      (if (and (< k 240) (not (plan/failed? world)) (not (plan/done? world)))
        (let [{world :world sim :sim}
              (sim/play-on simrace/step world sim (constantly false) 250 (:time/now world) [] nil)
              i (:plan/intent world)
              cur [(:intent/kind i) (:intent/for i) (:intent/stage i)
                   (:intent/kind (:intent/child i)) (:intent/reason (:intent/child i))
                   (:plan/waiting world)]]
          (when (not= cur prev)
            (out "  "
                 (:time/now world)
                 (intent/feet world)
                 (pr-str cur)
                 "attempts"
                 (:plan/attempts world)))
          (recur world sim (inc k) cur))
        (out "  end"
             (:plan/status world)
             (:plan/reason world)
             (pr-str (brief (:plan/last world))))))))
(shutdown-agents)
