(require '[clojurecraft.capture :as capture]
         '[clojurecraft.simrace :as simrace]
         '[clojurecraft.sim :as sim]
         '[clojurecraft.plan :as plan]
         '[clojurecraft.intent :as intent]
         '[clojurecraft.swim :as swim]
         '[clojurecraft.terrain :as terrain]
         '[clojurecraft.blocks :as blocks]
         '[clojure.string :as str])
(def seen (atom #{}))
(defn colname
  [world pos]
  (let [id (terrain/block-at world pos)]
    (cond (nil? id) "?"
          (blocks/air? id) "."
          (blocks/fluid id) "~"
          (blocks/solid? id) "#"
          :else "o")))
(defn dump
  [world label]
  (let [[x y z :as feet] (intent/feet world)
        surface (swim/surface-y world :water feet)]
    (println label
             (:time/now world)
             feet
             "surface" surface
             "in-water?" (:player/in-water? world)
             "eye-in-water?" (:player/eye-in-water? world)
             "under-sky?" (terrain/under-sky? world feet)
             "banks" (swim/banks world feet surface #{}))
    (doseq [[dx dz] [[0 0] [1 0] [-1 0] [0 1] [0 -1]]]
      (println "   col"
               [dx dz]
               (str/join (for [dy (range -2 14)] (colname world [(+ x dx) (+ y dy) (+ z dz)])))))))
(defn step*
  [world event]
  (let [w (simrace/step world event)
        i (:plan/intent w)
        k [(:intent/kind i) (:intent/id i)]]
    (when (and (= :swim-out (:intent/kind i)) (not (contains? @seen k)) (< (count @seen) 4))
      (swap! seen conj k)
      (dump w (str "swim-out#" (:intent/id i) " for " (:intent/for i) " to-y " (:intent/to-y i))))
    w))
(doseq [p *command-line-args*]
  (reset! seen #{})
  (let [snap (capture/read-world p)
        w (simrace/world-for! snap)
        m (simrace/moment-of snap w)
        until (:snapshot/until snap)
        stop? #(or (plan/done? %) (plan/failed? %))]
    (println "==" p)
    (dump (:world m) "start")
    (sim/play-from step* m stop? (simrace/window-of snap) (simrace/go-of w until) nil)))
(shutdown-agents)
