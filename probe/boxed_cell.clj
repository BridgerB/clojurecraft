(require '[clojure.string :as str]
         '[clojurecraft.capture :as capture]
         '[clojurecraft.simrace :as simrace]
         '[clojurecraft.stairs :as stairs]
         '[clojurecraft.intent :as intent]
         '[clojurecraft.terrain :as terrain]
         '[clojurecraft.blocks :as blocks]
         '[clojurecraft.path :as path])

(defn bname
  [world cell]
  (some-> (terrain/block-at world cell)
          blocks/name-of))

;; args: path then cells as x,y,z triples
(let [[p & cells] *command-line-args*
      snap (capture/read-world p)
      w (simrace/world-for! snap)
      world0 (:world (simrace/moment-of snap w))]
  (doseq [cs cells]
    (let [[x y z] (mapv #(Long/parseLong %) (str/split cs #","))
          world (assoc world0 :player/pos [(+ x 0.5) (double y) (+ z 0.5)] :player/on-ground? true)
          feet [x y z]]
      (println "CELL"
               p
               feet
               "filler" (stairs/filler world 1)
               "carried" (stairs/carried-filler world)
               "pit-rim" (try (stairs/pit-rim world feet) (catch Exception e :err))
               "out?" (try (stairs/out? world feet) (catch Exception e :err)))
      (doseq [d stairs/directions]
        (let [dc (stairs/stair-cells feet d)
              uc (stairs/up-cells feet d)
              lc (stairs/level-cells feet d)]
          (println
           " dir"
           d
           "down[cells"
           (mapv #(bname world %) dc)
           "floor"
           (bname world (update (last dc) 1 dec))
           "clear"
           (stairs/clear-way? world dc)
           "boxed-last"
           (contains? (:plan/boxed-at world #{}) (last dc))
           "down-safe"
           (try (stairs/down-safe? world dc) (catch Exception e :err))
           "down-lay"
           (try (stairs/down-lay world feet dc) (catch Exception e :err))
           "]"
           "up[cells"
           (mapv #(bname world %) uc)
           "step"
           (bname world (update (last uc) 1 dec))
           "clear"
           (stairs/clear-way? world uc)
           "boxed-last"
           (contains? (:plan/boxed-at world #{}) (last uc))
           "falls"
           (try (stairs/falls-over? world uc) (catch Exception e :err))
           "up-dry"
           (try (stairs/up-dry? world uc) (catch Exception e :err))
           "up-lay"
           (try (stairs/up-lay world feet uc) (catch Exception e :err))
           "]"
           "level[cells"
           (mapv #(bname world %) lc)
           "floor"
           (bname world (update (last lc) 1 dec))
           "clear"
           (stairs/clear-way? world lc)
           "boxed-last"
           (contains? (:plan/boxed-at world #{}) (last lc))
           "exposes"
           (mapv #(try (intent/exposes-fluid? world %) (catch Exception e :err)) lc)
           "level-safe"
           (try (stairs/level-safe? world lc) (catch Exception e :err))
           "level-lay"
           (try (stairs/level-lay world feet lc) (catch Exception e :err))
           "lay"
           (try (stairs/lay world feet lc) (catch Exception e :err))
           "]"))))))
(println "DONE")
