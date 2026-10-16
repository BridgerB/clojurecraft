(require '[clojurecraft.capture :as capture]
         '[clojurecraft.simrace :as simrace]
         '[clojurecraft.memory :as memory]
         '[clojurecraft.recipe :as recipe]
         '[clojure.string :as str])
(defn out [& xs] (println (str/join " " xs)))
(let [[file seed] *command-line-args*
      r (simrace/run (simrace/prepared (capture/read-world file))
                     (parse-long seed)
                     "nether"
                     (* 90 60 1000))
      w (:run/end r)
      story (memory/intentions w)]
  (out "==" file seed (pr-str (:run/signature r)) "ms" (:run/ms r) "pos" (pr-str (:player/pos w)))
  (out " held" (pr-str (recipe/counts (:player/inventory w))))
  (out " reached-after" (pr-str (:plan/reached-after w)))
  (out " intent" (pr-str (dissoc (:plan/intent w) :intent/waypoints :intent/visited)))
  (out " kinds started per 5 min:")
  (doseq [[k xs] (sort-by key
                          (group-by #(quot (:intention/at %) 300000)
                                    (filter #(= :started (:intention/event %)) story)))]
    (out "  " (* 5 k) "min" (pr-str (frequencies (map :intention/kind xs)))))
  (out " failures:"
       (pr-str (frequencies (keep #(when (= :failed (:intention/event %))
                                     [(:intention/kind %) (:intention/reason %)])
                                  story))))
  (out " last 60:")
  (doseq [x (take-last 60 story)] (out "  " (pr-str (dissoc x :intention/id)))))
(shutdown-agents)
