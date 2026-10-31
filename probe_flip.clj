(require '[clojurecraft.record :as record]
         '[clojurecraft.main :as main]
         '[clojurecraft.game :as game]
         '[clojurecraft.pearls :as pearls]
         '[clojurecraft.intent :as intent]
         '[clojurecraft.plan :as plan]
         '[clojurecraft.entity :as entity])
(let [[path from len] *command-line-args*
      from (parse-long from)
      last-now (record/reduce-entries
                (fn [acc e] (if (and (= :tick (:event/kind e)) (:event/now e)) (:event/now e) acc))
                0
                path)
      lo (- last-now (* 1000 from))
      hi (+ lo (* 1000 (parse-long (or len "3"))))
      prev (volatile! nil)]
  (record/reduce-entries
   (fn [w e]
     (if-not (:event/kind e)
       w
       (let [w (assoc (main/step w e) :bot/effects [])
             now (:time/now w)
             cur (select-keys (:plan/intent w)
                              [:intent/kind :intent/target :intent/for :intent/hold? :intent/yield?
                               :intent/goal])]
         (when (and now (<= lo now hi) (= :tick (:event/kind e)) (not= cur @prev))
           (vreset! prev cur)
           (println
            "T" (- now last-now)
            "feet" (intent/feet w)
            "cur" (pr-str cur)
            "shelter" (pearls/shelter w)
            "pearls/shelter" (:pearls/shelter w)
            "mouth" (:pearls/mouth w)
            "menace"
            (pr-str (some-> (pearls/menace w)
                            second
                            :entity/pos))
            "hand"
            (pr-str (some-> (pearls/at-hand w)
                            ((fn [[i e]] [i (mapv long (:entity/pos e)) (entity/creepy? e)]))))
            "threat"
            (pr-str (some-> (pearls/threat w)
                            first))
            "angry-near"
            (pr-str (some-> (pearls/angry-near w)
                            first))
            "swarmed" (pearls/swarmed? w)
            "hp" (:player/health w)
            "wandering" (pearls/wandering? w)
            "avoid" (pr-str (plan/avoiding? w (intent/feet w)))
            "HUNT"
            (pr-str (try (select-keys (pearls/hunt w) [:intent/kind :intent/target :intent/for])
                         (catch Exception t (.getMessage t))))))
         w)))
   (game/init {:host "replay" :port 0 :name "Clj_replay"})
   path)
  (shutdown-agents))
