;; Issue #105: a snapshot played on from its moment, printing the scene (facts and slice) at the
;; start of every :stairs-down and :redig, to see where the bot stands when it is boxed.
;;
;;   clojure -M probe/boxed_scene.clj <snapshot.edn.gz>
(require '[clojurecraft.capture :as capture]
         '[clojurecraft.plan :as plan]
         '[clojurecraft.scene :as scene]
         '[clojurecraft.sim :as sim]
         '[clojurecraft.simrace :as simrace])

(def path (first *command-line-args*))
(def snap (capture/read-world path))
(def w (simrace/world-for! snap))
(def m (simrace/moment-of snap w))
(def seen (atom #{}))

(defn watch
  [world]
  (let [i (:plan/intent world)
        k [(:intent/kind i) (:intent/id i)]]
    (when (and i (#{:stairs-down :redig :swim-out :tunnel} (:intent/kind i)) (not (@seen k)))
      (swap! seen conj k)
      (println "AT"
               (:time/now world)
               (pr-str (select-keys i
                                    [:intent/kind :intent/to-y :intent/for :intent/floor-to
                                     :intent/item])))
      (println "FACTS" (pr-str (scene/facts world)))
      (doseq [l (scene/slice world)] (println "S" l)))
    (or (plan/done? world) (plan/failed? world))))

(let [{world' :world} (sim/play-from simrace/step
                                     m
                                     watch
                                     (simrace/window-of snap)
                                     (simrace/go-of w (:snapshot/until snap))
                                     nil)]
  (println "END" (:time/now world') (:plan/status world') (:plan/reason world')))
