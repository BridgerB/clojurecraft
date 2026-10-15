<<<<<<< HEAD
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
    (out " under-sky? feet" (terrain/under-sky? w (intent/feet w))
         "over head"
         (mapv #(blocks/name-of (terrain/block-at w (update (intent/feet w) 1 + %))) (range 2 8)))
    (out " nearest logs"
         (take 5
               (memory/nearest-first (game/eye w)
                                     wood/far-radius
                                     (memory/positions-now w blocks/log-states))))
    (out
     " nearest standing trunk"
     (memory/nearest-log w (game/eye w) wood/far-radius (constantly false) wood/standing-trunk?))
    (out " first-walk" (scoop/first-walk w :log-hunt (scoop/hunt-targets w)))
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
||||||| parent of 8c1ff88 (probe: issue 57)
=======
;; probe: issue #57, wood :no-log at the landing with no intent ever started.
;; Fold each recording to its final world and ask the gather chain's parts what they see.
(require '[clojure.string :as str]
         '[clojurecraft.game :as game]
         '[clojurecraft.main :as main]
         '[clojurecraft.memory :as memory]
         '[clojurecraft.wood :as wood]
         '[clojurecraft.scoop :as scoop]
         '[clojurecraft.stairs :as stairs]
         '[clojurecraft.intent :as intent]
         '[clojurecraft.terrain :as terrain]
         '[clojurecraft.path :as path]
         '[clojurecraft.blocks :as blocks]
         '[clojurecraft.record :as record]
         '[clojurecraft.physics :as physics]
         '[clojurecraft.plan :as plan])

(.mkdirs (java.io.File. "out"))

(defn out
  [& xs]
  (let [s (str/join " " xs)]
    (println s)
    (spit "out/probe.log" (str s "\n") :append true)))

(doseq [p *command-line-args*]
  (let [w (record/replay main/step (game/init {:host "replay" :port 0 :name "Clj_replay"}) p)
        eye (game/eye w)
        feet (intent/feet w)
        logs (memory/positions-now w blocks/log-states)
        near (memory/nearest-first eye wood/search-radius logs)
        bottoms (filter #(memory/trunk-bottom? w %) near)
        standing (filter #(wood/standing-trunk? w %)
                         (memory/nearest-first eye wood/far-radius logs))]
    (out "==" p)
    (out " feet" feet
         "pos" (:player/pos w)
         "go-at" (:plan/go-at w)
         "status" (:plan/status w)
         "reason" (:plan/reason w)
         "waiting" (:plan/waiting w))
    (out " sightings" (count (memory/latest w))
         "log-positions" (count logs)
         "under-sky?" (terrain/under-sky? w feet)
         "out?" (stairs/out? w feet)
         "chunks" (count (:world/chunks w)))
    (out " bottoms-in-48" (count bottoms) "standing-in-256" (count standing))
    (doseq [b (take 5 standing)]
      (let [i (wood/log-walk w b)]
        (out "  trunk" b "target" (:intent/target i) "routable?" (intent/routable? w i))))
    (out " near-walk" (pr-str (wood/near-walk w)))
    (out " climb-out" (pr-str (wood/climb-out w)))
    (out " hunt" (pr-str (wood/hunt w)))
    (out " hunt-targets" (pr-str (mapv #(take 2 %) (scoop/hunt-targets w))))
    (out " gather-next" (pr-str (wood/gather-next w)))
    (doseq [t (take 3 (scoop/hunt-targets w))]
      (let [i {:intent/kind :walk :intent/target t :intent/for :log-hunt}
            r (path/plan w (path/feet-cell (:player/pos w)) (intent/goal-of i))]
        (out "  hunt-walk" t "route" (:path/status r) "waypoints" (count (:path/waypoints r)))))
    (let [[fx fy fz] feet
          r 8]
      (doseq [y (range (+ fy 3) (- fy 2) -1)]
        (out " y"
             y
             (str/join (for [x (range (- fx r) (inc (+ fx r)))
                             z (range (- fz r) (inc (+ fz r)))]
                         (if (= [x y z] [fx fy fz])
                           "@"
                           (case (path/classify w [x y z])
                             :solid "#"
                             :water "W"
                             :clear "."
                             :unknown "?"
                             :lava "L"
                             "~")))))))
    (out " mem-48-band"
         (pr-str (count (filter (fn [[_ y _]] (<= (abs (- y (physics/y-of eye))) 12)) near))))))

>>>>>>> 8c1ff88 (probe: issue 57)
(shutdown-agents)
