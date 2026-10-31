(require '[clojurecraft.record :as record]
         '[clojurecraft.main :as main]
         '[clojurecraft.game :as game]
         '[clojurecraft.pearls :as pearls]
         '[clojurecraft.intent :as intent]
         '[clojurecraft.plan :as plan])
(let [[path from] *command-line-args*
      from (parse-long from)
      last-now (record/reduce-entries
                (fn [acc e] (if (and (= :tick (:event/kind e)) (:event/now e)) (:event/now e) acc))
                0
                path)
      lo (- last-now (* 1000 from))
      hi (+ lo 3000)
      prev (volatile! nil)]
  (record/reduce-entries
   (fn [w e]
     (if-not (:event/kind e)
       w
       (let [w (assoc (main/step w e) :bot/effects [])
             now (:time/now w)
             cur (select-keys (:plan/intent w) [:intent/kind :intent/target :intent/for])]
         (when (and now (<= lo now hi) (= :tick (:event/kind e)) (not= cur @prev))
           (vreset! prev cur)
           (println "T" (- now last-now)
                    "feet" (intent/feet w)
                    "cur" (pr-str cur)
                    "shelter" (pearls/shelter w)
                    "pearls/shelter" (:pearls/shelter w)
                    "mouth" (:pearls/mouth w)
                    "menace"
                    (pr-str (some-> (pearls/menace w)
                                    second
                                    :entity/pos))
                    "avoid" (pr-str (plan/avoiding? w (intent/feet w)))
                    "HUNT"
                    (pr-str (try (select-keys (pearls/hunt w)
                                              [:intent/kind :intent/target :intent/for])
                                 (catch Exception t (.getMessage t))))))
         w)))
   (game/init {:host "replay" :port 0 :name "Clj_replay"})
   path)
  (shutdown-agents))
