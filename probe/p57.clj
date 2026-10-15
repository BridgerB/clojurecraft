(require '[clojurecraft.record :as record]
         '[clojurecraft.main :as main]
         '[clojurecraft.game :as game]
         '[clojurecraft.wood :as wood]
         '[clojurecraft.scoop :as scoop]
         '[clojurecraft.intent :as intent]
         '[clojurecraft.terrain :as terrain]
         '[clojurecraft.memory :as memory]
         '[clojurecraft.blocks :as blocks]
         '[clojurecraft.physics :as physics]
         '[clojure.java.io :as io]
         '[clojure.math :as math])
(.mkdirs (io/file "out"))
(defn out
  [& xs]
  (let [s (clojure.string/join " " xs)]
    (println s)
    (spit "out/probe.log" (str s "\n") :append true)))
(doseq [p *command-line-args*]
  (let [world0 (game/init {:host "replay" :port 0 :name "Clj_replay"})
        w (record/replay main/step world0 p)
        [px _ pz] (:player/pos w)
        ey (physics/y-of (game/eye w))]
    (out "==" p
         "status" (:plan/status w)
         "reason" (:plan/reason w)
         "feet" (intent/feet w)
         "go-at" (:plan/go-at w)
         "chunks" (count (:world/chunks w)))
    (out " logs remembered" (count (memory/positions-now w blocks/log-states)))
    (out " near-walk" (wood/near-walk w) "climb-out" (wood/climb-out w) "hunt" (wood/hunt w))
    (out " hunt-targets" (vec (scoop/hunt-targets w)))
    (doseq [[dx dz] scoop/bearings]
      (let [x (long (math/floor (+ px (* dx scoop/hunt-distance))))
            z (long (math/floor (+ pz (* dz scoop/hunt-distance))))
            g (scoop/ground w [x z] ey)]
        (out "  bearing" [x z]
             "loaded?" (terrain/chunk-loaded? w [x 0 z])
             "ground" g
             "under-sky?" (when g (terrain/under-sky? w g))
             "routable?"
             (when g
               (intent/routable? w
                                 {:intent/kind :walk :intent/target g :intent/for :log-hunt})))))))
(shutdown-agents)
