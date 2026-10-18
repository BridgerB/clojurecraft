(require '[clojurecraft.capture :as capture]
         '[clojurecraft.simrace :as simrace]
         '[clojurecraft.sim :as sim]
         '[clojurecraft.fortress :as fortress]
         '[clojurecraft.intent :as intent]
         '[clojurecraft.path :as path]
         '[clojurecraft.blocks :as blocks]
         '[clojurecraft.terrain :as terrain]
         '[clojure.string :as str])
(defn out [& xs] (println (str/join " " xs)))
(doseq [p *command-line-args*]
  (let [snap (capture/read-world p)
        w0 (simrace/world-for! snap)
        [world sim] (simrace/moment snap w0)
        feet (intent/feet world)
        i (:plan/intent world)
        nm #(some-> (terrain/block-at world %)
                    blocks/name-of)]
    (out "==" p "now" (:time/now world) "feet" feet)
    (out "SAFE" (fortress/safe? world (:intent/from i) (:intent/move i))
         "standable-to" (path/standable? world (fortress/plus (:intent/from i) (:intent/move i))))
    (doseq [dy [-1 0 1 2]
            dx [-1 0 1]]
      (let [c (fortress/plus feet [dx dy 0])] (out "CELL" [dx dy 0] c (nm c))))
    (loop [world world
           sim sim
           k 0]
      (when (< k 40)
        (let [{world :world sim :sim}
              (sim/play-on simrace/step world sim (constantly false) 250 (:time/now world) [] nil)
              i (:plan/intent world)]
          (out (:time/now world)
               "pos" (pr-str (:player/pos world))
               "vel" (pr-str (:player/velocity world))
               "ground" (:player/on-ground? world)
               "ctl" (pr-str (:player/controls world))
               "move" (pr-str (:intent/move i))
               "op" (pr-str (:intent/op i))
               "child" (pr-str (dissoc (:intent/child i) :intent/waypoints))
               "wps" (count (:intent/waypoints (:intent/child i))))
          (recur world sim (inc k)))))))
(shutdown-agents)
