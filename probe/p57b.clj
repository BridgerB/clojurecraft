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

