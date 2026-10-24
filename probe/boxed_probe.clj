(require '[clojurecraft.capture :as capture]
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

(doseq [p *command-line-args*]
  (let [snap (capture/read-world p)
        w (simrace/world-for! snap)
        world (:world (simrace/moment-of snap w))
        i (:plan/intent world)
        feet (intent/feet world)]
    (println "PROBE" p)
    (println " intent"
             (pr-str (select-keys i
                                  [:intent/kind :intent/for :intent/to-y :intent/dir :intent/stage
                                   :intent/turns :intent/level-steps :intent/level-dir :intent/rise
                                   :intent/no-shaft :intent/to-height? :intent/visited
                                   :intent/floor-to :intent/target])))
    (println " feet" feet
             "pos" (:player/pos world)
             "on-ground" (:player/on-ground? world)
             "in-water" (:player/in-water? world)
             "boxed-at" (:plan/boxed-at world)
             "go-at" (:plan/go-at world))
    (println
     " filler1" (stairs/filler world 1)
     "carried" (stairs/carried-filler world)
     "shaft?" (try (stairs/shaft? world i feet) (catch Exception e (str "ERR " (.getMessage e))))
     "shaft-safe?"
     (try (stairs/shaft-safe? world feet) (catch Exception e (str "ERR " (.getMessage e))))
     "pillar-item" (try (stairs/pillar-item world) (catch Exception e (str "ERR " (.getMessage e))))
     "pillar-up?"
     (try (stairs/pillar-up? world i feet) (catch Exception e (str "ERR " (.getMessage e))))
     "out?" (try (stairs/out? world feet) (catch Exception e (str "ERR " (.getMessage e))))
     "pit-rim" (try (stairs/pit-rim world feet) (catch Exception e (str "ERR " (.getMessage e))))
     "centred?" (try (intent/centred? world feet) (catch Exception e (str "ERR " (.getMessage e)))))
    (doseq [d stairs/directions]
      (let [dc (stairs/stair-cells feet d)
            uc (stairs/up-cells feet d)
            lc (stairs/level-cells feet d)]
        (println
         " dir"
         d
         "down{:cells"
         (mapv #(bname world %) dc)
         ":floor"
         (bname world (update (last dc) 1 dec))
         ":clear"
         (stairs/clear-way? world dc)
         ":safe"
         (stairs/safe? world dc)
         ":down-safe"
         (try (stairs/down-safe? world dc) (catch Exception e (str "ERR" (.getMessage e))))
         ":down-lay"
         (try (stairs/down-lay world feet dc) (catch Exception e (str "ERR" (.getMessage e))))
         ":exposes"
         (mapv #(try (intent/exposes-fluid? world %) (catch Exception e :err)) dc)
         "}"
         "up{:cells"
         (mapv #(bname world %) uc)
         ":step"
         (bname world (update (last uc) 1 dec))
         ":up-safe"
         (try (stairs/up-safe? world uc) (catch Exception e (str "ERR" (.getMessage e))))
         ":up-dry"
         (try (stairs/up-dry? world uc) (catch Exception e (str "ERR" (.getMessage e))))
         ":up-lay"
         (try (stairs/up-lay world feet uc) (catch Exception e (str "ERR" (.getMessage e))))
         ":falls"
         (try (stairs/falls-over? world uc) (catch Exception e (str "ERR" (.getMessage e))))
         ":seal"
         (try (stairs/seal world feet uc) (catch Exception e (str "ERR" (.getMessage e))))
         ":step-lay"
         (try (stairs/step-lay world feet uc) (catch Exception e (str "ERR" (.getMessage e))))
         ":step-to-lay"
         (try (stairs/step-to-lay world uc) (catch Exception e (str "ERR" (.getMessage e))))
         "}"
         "level{:cells"
         (mapv #(bname world %) lc)
         ":floor"
         (bname world (update (last lc) 1 dec))
         ":level-safe"
         (try (stairs/level-safe? world lc) (catch Exception e (str "ERR" (.getMessage e))))
         ":level-lay"
         (try (stairs/level-lay world feet lc) (catch Exception e (str "ERR" (.getMessage e))))
         ":lay"
         (try (stairs/lay world feet lc) (catch Exception e (str "ERR" (.getMessage e))))
         ":reserve-lay"
         (try (stairs/reserve-level-lay world (:intent/kind i) lc feet)
              (catch Exception e (str "ERR" (.getMessage e))))
         "}")))
    (println " shaft-cells" (mapv #(bname world %) (stairs/shaft-cells feet))
             "under-feet" (bname world (update feet 1 dec))
             "over-head"
             (mapv #(bname world %)
                   [(update feet 1 + 2) (update feet 1 + 3) (update feet 1 + 4)]))))
(println "DONE")
