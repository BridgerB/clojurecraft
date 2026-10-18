(require '[clojurecraft.capture :as capture]
         '[clojurecraft.simrace :as simrace]
         '[clojurecraft.sim :as sim]
         '[clojurecraft.plan :as plan]
         '[clojurecraft.intent :as intent]
         '[clojurecraft.path :as path]
         '[clojurecraft.terrain :as terrain]
         '[clojurecraft.blocks :as blocks]
         '[clojurecraft.stairs :as stairs]
         '[clojure.string :as str])
(defn out [& xs] (println (str/join " " xs)))
(defn kinds [world cells] (mapv #(path/classify world %) cells))
(defn why
  "Why a stair up (and a level step) is refused toward each heading from the feet."
  [world]
  (let [feet (intent/feet world)
        boxed (:plan/boxed-at world #{})]
    (out "  feet" feet
         "boxed-count" (count boxed)
         "held-pick"
         (boolean (some (fn [[_ {:keys [item]}]] (= :pickaxe (:kind (blocks/tool item))))
                        (:player/inventory world))))
    (doseq [d stairs/directions]
      (let [cells (stairs/up-cells feet d)
            [fx fy fz] (last cells)
            lc (stairs/level-cells feet d)]
        (out "  up" d
             "kinds" (kinds world cells)
             "floor" (path/classify world [fx (dec fy) fz])
             "boxed-last?" (contains? boxed (last cells))
             "clear-way?" (stairs/clear-way? world cells)
             "falls?" (stairs/falls-over? world cells)
             "fluid-exp?" (mapv #(boolean (intent/exposes-fluid? world %)) cells)
             "water-over?" (stairs/water-over? world cells)
             "up-dry?" (stairs/up-dry? world cells)
             "up-lay" (stairs/up-lay world feet cells))
        (out "     level" (kinds world lc)
             "boxed-last?" (contains? boxed (last lc))
             "level-safe?" (stairs/level-safe? world lc)
             "too-hard" (mapv #(stairs/too-hard? world %) lc))))
    (out "  column above feet"
         (mapv (fn [dy] (let [[x y z] feet] (path/classify world [x (+ y dy) z]))) (range 0 12)))))
(doseq [p *command-line-args*]
  (let [snap (capture/read-world p)
        w0 (simrace/world-for! snap)
        [world sim] (simrace/moment snap w0)]
    (out "==" p (:snapshot/signature snap) "now" (:time/now world))
    (why world)
    (loop [world world
           sim sim
           k 0]
      (when (and (< k 4) (not (plan/failed? world)) (not (plan/done? world)))
        (let [{world :world sim :sim} (sim/play-on simrace/step
                                                   world
                                                   sim
                                                   (constantly false)
                                                   60000
                                                   (:time/now world)
                                                   []
                                                   nil)]
          (out "-- t" (:time/now world)
               "intent"
               (pr-str (select-keys (:plan/intent world)
                                    [:intent/kind :intent/stage :intent/level-steps :intent/rise])))
          (why world)
          (recur world sim (inc k)))))))
(shutdown-agents)
