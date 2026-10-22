(require '[clojurecraft.capture :as capture]
         '[clojurecraft.simrace :as sr]
         '[clojurecraft.sim :as sim]
         '[clojurecraft.plan :as plan]
         '[clojurecraft.intent :as intent]
         '[clojurecraft.stone :as stone]
         '[clojurecraft.stairs :as stairs]
         '[clojurecraft.recipe :as recipe]
         '[clojure.string :as str])

(defn out [& xs] (println (str/join " " xs)) (flush))

(doseq [p *command-line-args*
        n [1 2 3]]
  (let [snap (capture/read-world p)
        w (sr/world-for! snap)
        m (sr/moment-of snap w)
        stop? #(or (plan/failed? %) (>= (:plan/attempts % 0) n))
        {:keys [world]} (sim/play-from sr/step m stop? 120000 nil nil)
        feet (intent/feet world)]
    (out "==" p
         "at failure" n
         "feet" feet
         "attempts" (:plan/attempts world)
         "held" (pr-str (recipe/counts (:player/inventory world))))
    (out "boxed-at" (pr-str (:plan/boxed-at world))
         "route" (count (:plan/boxed-route world))
         "last"
         (pr-str (select-keys (:plan/last world)
                              [:intent/kind :intent/for :intent/stage :intent/dir :intent/to-y])))
    (out "adjoins" (stone/adjoins-boxed? world feet)
         "filler-dig" (pr-str (stone/filler-dig world))
         "stairs" (pr-str (stone/stairs world {}))
         "tunnel" (pr-str (stone/tunnel-heading world))
         "deeper" (pr-str (stone/deeper world))
         "walk-home" (pr-str (stone/walk-home world))
         "dead-end" (pr-str (stone/dead-end-retreat world))
         "cross-home" (pr-str (stone/cross-home world))
         "climb" (pr-str (stone/climb world)))))
