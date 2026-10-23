;; Why the planner's helpers answer as they do where a snapshot's window ends: the spot a redig
;; wants (each candidate's conjuncts) and the way to a log (routes, out?, pit-rim).
;; Usage: clojure -M probe/overworld_why.clj <snapshot.edn.gz>[,...]
(require '[clojure.math :as math]
         '[clojure.string :as str]
         '[clojurecraft.blocks :as blocks]
         '[clojurecraft.capture :as capture]
         '[clojurecraft.game :as game]
         '[clojurecraft.intent :as intent]
         '[clojurecraft.path :as path]
         '[clojurecraft.physics :as physics]
         '[clojurecraft.place :as place]
         '[clojurecraft.plan :as plan]
         '[clojurecraft.sim :as sim]
         '[clojurecraft.simrace :as simrace]
         '[clojurecraft.stairs :as stairs]
         '[clojurecraft.terrain :as terrain]
         '[clojurecraft.wood :as wood])

(defn why-spot
  [world]
  (let [[px py pz] (:player/pos world)
        [fx fy fz] (map #(long (math/floor %)) [px py pz])
        eye (game/eye world)]
    (doseq [[dx dy dz] (filter (fn [[dx _ dz]] (= 1 (+ (abs dx) (abs dz)))) place/around)
            :let [target [(+ fx dx) (+ fy dy) (+ fz dz)]
                  support [(+ fx dx) (+ fy dy -1) (+ fz dz)]
                  t (terrain/block-at world target)
                  s (terrain/block-at world support)]]
      (println "SPOT"
               (pr-str
                {:target target
                 :t (some-> t
                            blocks/name-of)
                 :s (some-> s
                            blocks/name-of)
                 :replaceable (boolean (some-> t
                                               blocks/replaceable?))
                 :confirmed (terrain/confirmed? world target)
                 :solid-support (boolean (some-> s
                                                 blocks/solid?))
                 :passage (place/passage? world target)
                 :inside (place/inside-player? world target)
                 :drop-beside (place/drop-beside? world target)
                 :standable (path/standable? world target)
                 :exposes (intent/exposes-fluid? world target)
                 :reach (<= (physics/distance eye (physics/centre target)) place/place-reach)})))
    (println "SPOT-RESULT" (pr-str (place/spot world true)))))

(defn why-wood
  [world]
  (let [feet (intent/feet world)]
    (println "WOOD"
             (pr-str {:feet feet
                      :out (stairs/out? world feet)
                      :under-sky (terrain/under-sky? world feet)
                      :pit-rim (stairs/pit-rim world feet)
                      :opening (select-keys (intent/opening world) [:intent/kind :intent/target])
                      :near-walk (some-> (wood/near-walk world)
                                         (select-keys [:intent/kind :intent/target]))}))
    (doseq [bottom (wood/far-trunks world)
            :let [i (wood/log-walk world bottom)
                  goal (intent/goal-of i)
                  r (path/plan world feet goal {})]]
      (println "TRUNK"
               (pr-str {:bottom bottom
                        :walk (select-keys i [:intent/target :intent/pickup?])
                        :routable (intent/routable? world i)
                        :path (:path/status r)})))))

(doseq [path (str/split (first *command-line-args*) #",")]
  (println "SNAP" path)
  (let [snap (capture/read-world path)
        w (simrace/world-for! snap)
        m (simrace/moment-of snap w)
        r (sim/play-from simrace/step
                         m
                         #(or (plan/done? %) (plan/failed? %))
                         (simrace/window-of snap)
                         (simrace/go-of w (:snapshot/until snap))
                         nil)
        world (:world r)]
    (println "END" (pr-str {:status (:plan/status world) :reason (:plan/reason world)}))
    (when (:player/pos world)
      (try (why-spot world) (catch Exception e (println "SPOT threw" (ex-message e))))
      (try (why-wood world) (catch Exception e (println "WOOD threw" (ex-message e)))))
    (flush)))
(shutdown-agents)
