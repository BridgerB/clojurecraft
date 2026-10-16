(require '[clojure.string :as str]
         '[clojurecraft.blocks :as blocks]
         '[clojurecraft.game :as game]
         '[clojurecraft.main :as main]
         '[clojurecraft.path :as path]
         '[clojurecraft.record :as record]
         '[clojurecraft.terrain :as terrain])

(defn out [& xs] (println (str/join " " xs)))

(doseq [p *command-line-args*]
  (try (out "==" p)
       (let [ws (reductions (fn [w e] (assoc (main/step w e) :bot/effects []))
                            (game/init {:host "replay" :port 0 :name "Clj_replay"})
                            (record/events p))
             tunnel? #(= :tunnel-to (:intent/kind (:plan/intent %)))
             w (reduce (fn [l w] (if (tunnel? w) w l)) nil ws)
             {:intent/keys [from move]} (:plan/intent w)]
         (out "pos" (pr-str (:player/pos w))
              "from" (pr-str from)
              "move" (pr-str move)
              "on-ground" (:player/on-ground? w)
              "collision" (:player/horizontal-collision? w)
              "controls" (pr-str (:player/controls w)))
         (doseq [dx [-1 0 1]
                 dz [-2 -1 0 1]
                 dy [-1 0 1 2]]
           (let [c (mapv + from [dx dy dz])]
             (out " "
                  (pr-str c)
                  (blocks/name-of (terrain/block-at w c))
                  (terrain/block-at w c)
                  (path/classify w c)))))
       (catch Exception e (out "THREW" (ex-message e)))))
(shutdown-agents)
