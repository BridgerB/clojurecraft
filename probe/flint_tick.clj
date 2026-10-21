(require '[clojure.string :as str]
         '[clojurecraft.blocks :as blocks]
         '[clojurecraft.capture :as capture]
         '[clojurecraft.intent :as intent]
         '[clojurecraft.plan :as plan]
         '[clojurecraft.sim :as sim]
         '[clojurecraft.simrace :as simrace]
         '[clojurecraft.terrain :as terrain])

(defn out [& xs] (println (str/join " " xs)))

(def since (parse-long (first *command-line-args*)))

(doseq [p (rest *command-line-args*)]
  (try (out "==" p)
       (let [snap (capture/read-world p)
             w (simrace/world-for! snap)
             m (simrace/moment-of snap w)
             step (fn [wd e]
                    (let [wd' (simrace/step wd e)
                          t (:time/now wd')]
                      (when (and (>= t since) (< t (+ since 1200)))
                        (let [[x y z] (intent/feet wd')
                              nm #(blocks/type-of (terrain/block-at wd' %))]
                          (out "TICK" t
                               "pos" (pr-str (:player/pos wd'))
                               "ground" (:player/on-ground? wd')
                               "water" (:player/in-water? wd')
                               "kind" (:intent/kind (:plan/intent wd'))
                               "below" (nm [x (dec y) z])
                               "below2" (nm [x (- y 2) z])
                               "below3" (nm [x (- y 3) z]))))
                      wd'))
             stop? #(or (plan/failed? %) (plan/done? %))]
         (sim/play-from step
                        m
                        stop?
                        (simrace/window-of snap)
                        (simrace/go-of w (:snapshot/until snap))
                        nil))
       (catch Exception e (out "THREW" (ex-message e)))))

(shutdown-agents)
