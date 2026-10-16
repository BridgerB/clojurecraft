(require '[clojure.string :as str]
         '[clojurecraft.game :as game]
         '[clojurecraft.main :as main]
         '[clojurecraft.physics :as physics]
         '[clojurecraft.record :as record]
         '[clojurecraft.terrain :as terrain])

(defn out [& xs] (println (str/join " " xs)))

(doseq [p *command-line-args*]
  (try (out "==" p)
       (let [ws (reductions (fn [w e] (assoc (main/step w e) :bot/effects []))
                            (game/init {:host "replay" :port 0 :name "Clj_replay"})
                            (record/events p))
             walking? #(= :walk (:intent/kind (:intent/child (:plan/intent %))))
             picked (filter walking? ws)
             sample (take-nth 20 picked)]
         (doseq [w (take 40 sample)]
           (out "t" (:time/now w)
                "pos" (pr-str (mapv #(/ (clojure.math/round (* 1000.0 %)) 1000.0) (:player/pos w)))
                "vel" (pr-str (mapv #(/ (clojure.math/round (* 1000.0 %)) 1000.0) (:player/vel w)))
                "ready" (game/physics-ready? w)
                "loaded?" (:player/loaded? w)
                "chunk" (terrain/chunk-loaded? w (:player/pos w))
                "ground" (:player/on-ground? w)
                "coll" (:player/horizontal-collision? w)
                "ctl" (pr-str (:player/controls w))
                "phase" (:bot/phase w)
                "dead" (:player/dead? w)
                "solid-under" ((terrain/solid-fn w) 14966 109 13651))))
       (catch Exception e (out "THREW" (ex-message e)))))
(shutdown-agents)
