;; Clj_r51's real snapshot at its moment, main's code: the running heading :tunnel-to, and for each
;; move the stepper would try from the feet, what refuses it; the terrain east and up; then the same
;; at the start of the next leg. clojure -M:dev probe/r51moves.clj snap.edn.gz
(require '[clojurecraft.capture :as capture] '[clojurecraft.simrace :as simrace] '[clojurecraft.sim :as sim]
         '[clojurecraft.intent :as intent] '[clojurecraft.fortress :as fortress] '[clojurecraft.path :as path]
         '[clojurecraft.blocks :as blocks] '[clojurecraft.terrain :as terrain] '[clojurecraft.physics :as physics]
         '[clojure.string :as str])
(defn out [& xs] (println (str/join " " xs)) (flush))
(defn ask [label f] (try (out label (pr-str (f))) (catch Throwable e (out label "THREW" (ex-message e)))))
(defn nm [world c] (try (blocks/name-of (terrain/block-at world c)) (catch Throwable _ "?")))
(defn moves-report [world]
  (let [i (:plan/intent world) feet (intent/feet world) target (:intent/target i)
        trail (fortress/trail-of world target)
        seen (-> (:intent/tunnel-visited i #{}) (into (:trail/cells trail)) (into (:trail/dead trail)))]
    (out "FEET" (pr-str feet) "TARGET" (pr-str target) "kind" (:intent/kind i) "for" (:intent/for i)
         "climb" (:intent/climb i) "seen" (count seen))
    (doseq [m (fortress/moves feet target (or (:intent/climb i) fortress/climb-past))]
      (let [to (fortress/plus feet m)]
        (out " move" (pr-str m) "to" (pr-str to)
             "opens" (pr-str (mapv #(nm world %) (fortress/opens feet m)))
             "floor" (nm world (fortress/plus to [0 -1 0]))
             "safe?" (try (fortress/safe? world feet m) (catch Throwable e (ex-message e)))
             "but-lava?" (try (fortress/safe-but-lava? world feet m) (catch Throwable e (ex-message e)))
             "lava-beside" (try (pr-str (vec (fortress/lava-beside world feet m))) (catch Throwable e (ex-message e)))
             "sealed?" (try (fortress/sealed? world feet m) (catch Throwable e (ex-message e)))
             "seen?" (contains? seen to)
             "closer?" (< (physics/distance to target) (physics/distance feet target)))))
    (ask " next-move" #(fortress/next-move world feet target (or (:intent/climb i) fortress/climb-past) false seen
                                            (boolean (:intent/bridge? i)) (boolean (:intent/no-down? i))))
    (let [[fx fy fz] feet]
      (doseq [y (range (+ fy 3) (- fy 3) -1)]
        (out "  y" y "(x" (- fx 4) "to" (+ fx 12) ", z" (- fz 4) "to" (+ fz 4) ")")
        (doseq [z (range (- fz 4) (+ fz 5))]
          (out "  " (str/join (for [x (range (- fx 4) (+ fx 13))]
                                (if (= [x y z] feet) "@"
                                    (case (path/classify world [x y z])
                                      :solid "#" :water "W" :clear "." :unknown "?" :lava "L" "~"))))))))))
(let [snap (capture/read-world (first *command-line-args*))
      [world0 sim0] (simrace/moment snap (simrace/world-for! snap))]
  (out "== at the moment")
  (moves-report world0)
  ;; step until a new :tunnel-to target starts, then report again (the east leg)
  (loop [world world0 sim sim0 k 0 t0 (:intent/target (:plan/intent world0))]
    (when (< k 800)
      (let [{world :world sim :sim} (sim/play-on simrace/step world sim (constantly false) 250 (:time/now world) [] nil)
            i (:plan/intent world)]
        (if (and (= :tunnel-to (:intent/kind i)) (not= t0 (:intent/target i)))
          (do (out "== next leg at" (:time/now world)) (moves-report world))
          (recur world sim (inc k) t0))))))
(shutdown-agents)
