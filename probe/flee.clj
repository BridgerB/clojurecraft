(require '[clojurecraft.capture :as capture]
         '[clojurecraft.simrace :as simrace]
         '[clojurecraft.sim :as sim]
         '[clojurecraft.plan :as plan]
         '[clojurecraft.intent :as intent]
         '[clojurecraft.path :as path]
         '[clojurecraft.swim :as swim]
         '[clojurecraft.terrain :as terrain]
         '[clojure.math :as math]
         '[clojure.string :as str])

(defn out [& xs] (println (str/join " " xs)) (flush))

(defn cells
  [world [fx fy fz] r]
  (doseq [y (range (+ fy 2) (- fy 2) -1)]
    (out " y" y)
    (doseq [z (range (- fz r) (inc (+ fz r)))]
      (out (str/join (for [x (range (- fx r) (inc (+ fx r)))]
                       (if (= [x y z] [fx fy fz])
                         "@"
                         (case (path/classify world [x y z])
                           :solid "#"
                           :water "W"
                           :clear "."
                           :unknown "?"
                           :lava "L"
                           "~"))))))))

(doseq [p *command-line-args*]
  (try
    (let [snap (capture/read-world p)
          w0 (simrace/prepared (capture/read-world (:snapshot/file snap)))
          [world sim] (simrace/moment snap w0)]
      (out "==" p)
      (loop [world world
             sim sim
             k 0
             shown 0]
        (when (and (< k 9000) (not (plan/failed? world)) (< shown 400))
          (let [{world' :world sim :sim}
                (sim/play-on simrace/step world sim (constantly false) 50 (:time/now world) [] nil)
                i (:plan/intent world')
                flee? (= :flee (:intent/kind i))]
            (when (and flee? (zero? shown))
              (out " flee starts" (:time/now world') "feet" (intent/feet world'))
              (cells world' (intent/feet world') 4))
            (when (> (:plan/attempts world' 0) (:plan/attempts world 0))
              (out " attempt" (:plan/attempts world')
                   "at" (:time/now world')
                   "feet" (intent/feet world')
                   "lava" (:player/in-lava? world')
                   "footing" (pr-str (swim/footing world' (intent/feet world')))
                   "last" (pr-str (select-keys (:plan/last world') [:intent/kind :intent/reason])))
              (cells world' (intent/feet world') 6))
            (when flee?
              (out " " (:time/now world')
                   "pos" (mapv #(/ (math/round (* 100.0 %)) 100.0) (:player/pos world'))
                   "vel" (mapv #(/ (math/round (* 100.0 %)) 100.0) (:player/vel world'))
                   "lava" (:player/in-lava? world')
                   "ground" (:player/on-ground? world')
                   "hcol" (:player/horizontal-collision? world')
                   "target" (:intent/target i)
                   "controls" (pr-str (:player/controls world'))
                   "trap" (pr-str (:plan/trap world'))))
            (recur world' sim (inc k) (if flee? (inc shown) shown))))))
    (catch Exception e (out "==" p "THREW" (.getName (class e)) (ex-message e)))))
(shutdown-agents)
