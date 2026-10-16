(require '[clojure.string :as str]
         '[clojurecraft.game :as game]
         '[clojurecraft.intent :as intent]
         '[clojurecraft.main :as main]
         '[clojurecraft.probe :as probe]
         '[clojurecraft.record :as record])

(defn out [& xs] (println (str/join " " xs)))

(defn show
  [label w]
  (out label "t" (:time/now w) "feet" (pr-str (intent/feet w)) "pos" (pr-str (:player/pos w)))
  (out "  intent" (pr-str (dissoc (:plan/intent w) :intent/child)))
  (out "  child" (pr-str (:intent/child (:plan/intent w))))
  (doseq [line (probe/around w 8)] (out "  " line)))

(doseq [p *command-line-args*]
  (try (out "==" p)
       (let [ws (reductions (fn [w e] (assoc (main/step w e) :bot/effects []))
                            (game/init {:host "replay" :port 0 :name "Clj_replay"})
                            (record/events p))
             tunnel? #(= :tunnel-to (:intent/kind (:plan/intent %)))
             [first-t last-t]
             (reduce (fn [[f l] w] (if (tunnel? w) [(or f w) w] [f l])) [nil nil] ws)]
         (when first-t (show "first tunnel tick" first-t))
         (when last-t (show "last tunnel tick" last-t))
         (doseq [r (take-last 30 (clojurecraft.memory/intentions (last ws)))]
           (out "  story" (pr-str r))))
       (catch Exception e (out "THREW" (ex-message e)))))
(shutdown-agents)
