(require '[clojurecraft.capture :as capture]
         '[clojurecraft.simrace :as simrace]
         '[clojurecraft.sim :as sim]
         '[clojurecraft.stairs :as stairs]
         '[clojurecraft.intent :as intent]
         '[clojurecraft.terrain :as terrain]
         '[clojurecraft.blocks :as blocks]
         '[clojurecraft.path :as path])

(defn bname
  [world cell]
  (some-> (terrain/block-at world cell)
          blocks/name-of))

(doseq [p *command-line-args*]
  (let [snap (capture/read-world p)
        w (simrace/world-for! snap)
        m (simrace/moment-of snap w)
        world0 (:world m)
        n0 (count (:plan/boxed-at world0 #{}))
        stop? #(> (count (:plan/boxed-at % #{})) n0)
        {world' :world} (sim/play-from simrace/step
                                       m
                                       stop?
                                       (simrace/window-of snap)
                                       (simrace/go-of w (:snapshot/until snap))
                                       nil)
        world world'
        feet (intent/feet world)]
    (println "FIRSTBOX" p
             "feet" feet
             "pos" (:player/pos world)
             "new-boxed" (remove (:plan/boxed-at world0 #{}) (:plan/boxed-at world #{}))
             "intent-now"
             (pr-str (select-keys (:plan/intent world)
                                  [:intent/kind :intent/for :intent/to-y :intent/stage :intent/dir
                                   :intent/status])))
    (println " filler" (stairs/filler world 1)
             "carried" (stairs/carried-filler world)
             "pit-rim" (try (stairs/pit-rim world feet) (catch Exception e :err))
             "out?" (try (stairs/out? world feet) (catch Exception e :err))
             "on-ground" (:player/on-ground? world)
             "in-water" (:player/in-water? world))
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
         "exposes"
         (mapv #(try (intent/exposes-fluid? world %) (catch Exception e :err)) dc)
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
         "exposes"
         (mapv #(try (intent/exposes-fluid? world %) (catch Exception e :err)) uc)
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
         "]")))))
(println "DONE")
