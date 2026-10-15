(require '[clojurecraft.capture :as capture] '[clojurecraft.simrace :as simrace] '[clojurecraft.sim :as sim]
         '[clojurecraft.build :as build] '[clojurecraft.plan :as plan] '[clojurecraft.intent :as intent]
         '[clojurecraft.terrain :as terrain] '[clojurecraft.blocks :as blocks] '[clojurecraft.recipe :as recipe]
         '[clojurecraft.place :as place] '[clojure.java.io :as io])
(.mkdirs (io/file "out"))
(defn out [& xs] (let [s (apply str (interpose " " xs))] (println s) (spit "out/probe.log" (str s "\n") :append true)))
(defn nm [world c] (blocks/name-of (terrain/block-at world c)))
(defn show [world sim]
  (let [i (:plan/intent world) c (:intent/child i)
        cell (or (:intent/cell c) (:intent/target c))]
    (out " now" (:time/now world) "feet" (intent/feet world) "pos" (mapv #(/ (Math/round (* 100.0 %)) 100.0) (:player/pos world))
         "op" (select-keys (:intent/op i) [:build/op :build/pos :build/stand :build/item :build/step])
         "child" (select-keys c [:intent/kind :intent/item :intent/cell :intent/target :intent/against :intent/click-face :intent/stage :intent/status :intent/reason]))
    (when cell
      (out "   cell" cell (nm world cell) "below" (nm world (update cell 1 dec)) "support" (place/support world cell)
           "sim-dest" (blocks/name-of (sim/block-at sim cell)) "overlap?" (sim/overlaps-player? sim cell) "sim-pos" (:sim/player-pos sim)))))
(doseq [p *command-line-args*]
  (let [snap (capture/read-world p)
        w (simrace/prepared (capture/read-world (:snapshot/file snap)))
        [world sim] (simrace/moment snap w)]
    (out "==" p (:snapshot/signature snap) "held" (recipe/counts (:player/inventory world)) "attempts" (:plan/attempts world))
    (out " intent" (select-keys (:plan/intent world) [:intent/kind :intent/build :intent/origin :intent/facing :intent/access]))
    (show world sim)
    (loop [world world sim sim k 0]
      (when (and (< k 40) (not (plan/failed? world)) (not (plan/done? world)))
        (let [{world :world sim :sim} (sim/play-on simrace/step world sim (constantly false) 250 (:time/now world) [] nil)]
          (show world sim)
          (recur world sim (inc k)))))))
(shutdown-agents)
