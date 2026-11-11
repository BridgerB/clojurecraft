;; Every :tunnel-to of Clj_r51 folded to the end: its target, why, how it ended and in how long,
;; and the feet when it ended, from the bot's own intention facts. clojure -M:dev probe-data/legs.clj rec.edn
(require '[clojurecraft.why :as why] '[clojurecraft.memory :as memory])
(let [w (why/at (first *command-line-args*) Long/MAX_VALUE)
      is (filter #(= :tunnel-to (:intention/kind %)) (memory/intentions w))
      by (group-by :intention/id is)]
  (println "tunnel-to intentions" (count by))
  (doseq [[id fs] (sort-by key by)
          :let [s (first (filter #(= :started (:intention/event %)) fs))
                e (first (remove #(= :started (:intention/event %)) fs))]]
    (println id (:intention/why s) (:intention/target s)
             (:intention/event e) (:intention/reason e)
             (when (and s e) (quot (- (:intention/at e) (:intention/at s)) 1000)) "s"))
  (println "ends" (frequencies (map (fn [[_ fs]] (some (fn [f] (when-not (= :started (:intention/event f)) [(:intention/why (first fs)) (:intention/event f) (:intention/reason f)])) fs)) by))))
(shutdown-agents)
