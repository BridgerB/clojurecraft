(require '[clojurecraft.capture :as capture]
         '[clojurecraft.simrace :as simrace]
         '[clojurecraft.sim :as sim]
         '[clojurecraft.plan :as plan]
         '[clojurecraft.intent :as intent]
         '[clojurecraft.entity :as entity]
         '[clojurecraft.combat :as combat]
         '[clojurecraft.physics :as physics]
         '[clojure.string :as str])
(defn out [& xs] (println (str/join " " xs)))
(defn brief
  [i]
  (select-keys i
               [:intent/kind :intent/for :intent/goal :intent/stage :intent/target :intent/status
                :intent/reason :intent/level-steps :intent/to-y :intent/rise]))
(defn near-hostiles
  [world]
  (let [pos (:player/pos world)]
    (->> (entity/hostiles world)
         (map (fn [[eid e]] [eid (name (entity/kind e))
                             (long (physics/distance pos (:entity/pos e)))
                             (if (combat/hittable? world e) :seen :hid)]))
         (sort-by #(nth % 2))
         (take 4)
         vec)))
(doseq [p *command-line-args*]
  (let [snap (capture/read-world p)
        w0 (simrace/world-for! snap)
        [world sim] (simrace/moment snap w0)]
    (out "==" p (:snapshot/signature snap) "now" (:time/now world))
    (loop [world world
           sim sim
           k 0
           prev nil]
      (if (and (< k 90) (not (plan/failed? world)) (not (plan/done? world)))
        (let [{world :world sim :sim}
              (sim/play-on simrace/step world sim (constantly false) 10000 (:time/now world) [] nil)
              i (:plan/intent world)
              cur [(:intent/kind i) (:intent/stage i) (:intent/kind (:intent/child i))]]
          (when (or (not= cur prev) (zero? (mod k 6)))
            (out (:time/now world)
                 "feet"
                 (intent/feet world)
                 "hp"
                 (:player/health world)
                 "food"
                 (:player/food world)
                 (pr-str (brief i))
                 "child" (pr-str (brief (:intent/child i)))
                 "backed" (pr-str (:plan/backed-off world))
                 "boxed" (count (:plan/boxed-at world #{}))
                 "mobs" (pr-str (near-hostiles world))))
          (recur world sim (inc k) cur))
        (out "end" (:plan/status world) (:plan/reason world))))))
(shutdown-agents)
