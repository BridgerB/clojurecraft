(require '[clojurecraft.capture :as capture]
         '[clojurecraft.simrace :as simrace]
         '[clojurecraft.build :as build]
         '[clojurecraft.intent :as intent]
         '[clojurecraft.path :as path]
         '[clojure.string :as str])
(defn out [& xs] (println (str/join " " xs)))
(doseq [p *command-line-args*]
  (let [snap (capture/read-world p)
        w0 (simrace/prepared (capture/read-world (:snapshot/file snap)))
        [world _] (simrace/moment snap w0)
        {:intent/keys [build origin facing access op]} (:plan/intent world)
        plan (build/plan-for build origin facing (or access 0))]
    (out "=="
         p
         "origin"
         origin
         facing
         "access"
         access
         "feet"
         (intent/feet world)
         "op"
         (:build/pos op)
         (:build/step op))
    (doseq [[k o] (map-indexed vector plan)
            :when (#{:room :landing :floor :hole :over} (:build/step o))]
      (out " "
           k
           (:build/op o)
           (:build/step o)
           (:build/pos o)
           "stand"
           (:build/stand o)
           (path/classify world (:build/pos o))
           (if (build/satisfied? world o) "done" "todo")))))
(shutdown-agents)
