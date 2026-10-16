(require '[clojurecraft.capture :as capture]
         '[clojurecraft.simrace :as simrace]
         '[clojurecraft.sim :as sim]
         '[clojurecraft.plan :as plan]
         '[clojurecraft.intent :as intent]
         '[clojurecraft.recipe :as recipe]
         '[clojurecraft.stairs :as stairs]
         '[clojurecraft.terrain :as terrain]
         '[clojure.string :as str])
(defn out [& xs] (println (str/join " " xs)))
(defn brief
  [i]
  (select-keys i
               [:intent/kind :intent/for :intent/goal :intent/target :intent/to-y :intent/to-height?
                :intent/dir :intent/stage]))
(doseq [p *command-line-args*]
  (let [snap (capture/read-world p)
        [world sim] (simrace/moment snap
                                    (simrace/prepared (capture/read-world (:snapshot/file snap))))]
    (out "=="
         p
         (pr-str (:snapshot/signature snap))
         "feet" (intent/feet world)
         "go-at" (:plan/go-at world)
         "boxed" (count (:plan/boxed-at world))
         "surfaced" (pr-str (:plan/surfaced world)))
    (out " held" (pr-str (recipe/counts (:player/inventory world))))
    (loop [world world
           sim sim
           k 0
           prev nil]
      (when (and (< k 1300) (not (plan/failed? world)))
        (let [{world :world sim :sim}
              (sim/play-on simrace/step world sim (constantly false) 250 (:time/now world) [] nil)
              i (:plan/intent world)
              c (:intent/child i)
              cur [(brief i) (select-keys c [:intent/kind :intent/target :intent/reason])]]
          (when (not= (first cur) (first prev))
            (out "  "
                 (:time/now world)
                 (intent/feet world)
                 (pr-str (first cur))
                 "out?" (stairs/out? world (intent/feet world))
                 "sky?" (terrain/under-sky? world (intent/feet world))
                 "boxed" (count (:plan/boxed-at world))))
          (recur world sim (inc k) cur))))))
(shutdown-agents)
