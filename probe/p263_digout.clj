;; Issue #263, round 8: what dig-out (feat/263-warped8) answers in batch pearls-warped8-a's walled-in
;; runs (plan run 39731473452: run 2 at y 45 in its own diggings, run 3 on a ledge over a pit).
;; Folds the bot at the branch's code over a recording to the given second, then asks dig-out from
;; there and, standing at the end of the walk it gives, again, with the first cut's solid cells for
;; each heading.
;;
;;   clojure -M p263_digout.clj <run.edn.zst> <seconds>
(require '[clojurecraft.main :as main]
         '[clojurecraft.game :as game]
         '[clojurecraft.record :as record]
         '[clojurecraft.pearls :as pearls]
         '[clojurecraft.physics :as physics]
         '[clojurecraft.intent :as intent]
         '[clojurecraft.stairs :as stairs]
         '[clojure.string :as str])

(defn out [& xs] (println (str/join " " xs)))

(def path-arg (first *command-line-args*))
(def stop-ms (* 1000 (parse-long (second *command-line-args*))))

(defn ask
  [w label]
  (let [feet (intent/feet w)
        lw (assoc w :plan/last {:intent/kind :lookout :intent/status :done})]
    (out "==" label "feet" (pr-str feet) "digs-left" (pearls/digs-left w))
    (out " dig-out" (pr-str (pearls/dig-out w)))
    (out " hunt after a lookout" (pr-str (pearls/hunt lw)))
    (doseq [kind [:stairs-up :stairs-down :tunnel]
            h stairs/directions]
      (let [cells ((pearls/first-cut kind) feet h)]
        (out " "
             kind
             (pr-str h)
             "solid"
             (count (filter #(pearls/solid-at? w %) cells))
             (pr-str cells))))))

(def world0 (game/init {:host "probe" :port 0 :name "C1"}))

(def final
  (record/reduce-entries (fn [{:keys [world t0 done?] :as acc} e]
                           (cond (or done? (not (:event/kind e))) acc
                                 :else
                                 (let [w (assoc (main/step world e) :bot/effects [])
                                       now (:event/now e)
                                       t0 (or t0 now)
                                       off (when (and now t0) (- now t0))]
                                   {:world w :t0 t0 :done? (and off (>= off stop-ms))})))
                         {:world world0}
                         path-arg))

(let [w (:world final)
      d (pearls/dig-out w)]
  (ask w "at the stall")
  (when (= :walk (:intent/kind d))
    (ask (assoc w :player/pos (pearls/feet-at (:intent/target d))) "at the walk's end")))
(out "done")
(shutdown-agents)
