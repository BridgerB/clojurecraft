(require '[clojurecraft.capture :as capture]
         '[clojurecraft.simrace :as simrace]
         '[clojurecraft.stairs :as stairs]
         '[clojurecraft.intent :as intent])

(doseq [p *command-line-args*]
  (let [snap (capture/read-world p)
        w (simrace/world-for! snap)
        world0 (:world (simrace/moment-of snap w))
        feet (intent/feet world0)
        saved (:plan/intent world0)
        trials
        (cond-> []
          (#{:stairs-up :stairs-down :tunnel} (:intent/kind saved)) (conj ["saved" saved])
          :always
          (conj
           ["up" {:intent/kind :stairs-up :intent/to-y (+ (second feet) 6) :intent/status :active}]
           ["down"
            {:intent/kind :stairs-down :intent/to-y (- (second feet) 6) :intent/status :active}]
           ["up-surface"
            {:intent/kind :stairs-up
             :intent/to-y (+ (second feet) 6)
             :intent/for :surface
             :intent/status :active}]))]
    (println "PLANPROBE" p "feet" feet)
    (doseq [[label i0] trials]
      (println " trial"
               label
               (pr-str (select-keys i0
                                    [:intent/kind :intent/for :intent/to-y :intent/stage :intent/dir
                                     :intent/rise])))
      (try (loop [world (assoc world0 :plan/intent i0)
                  tick 0
                  last-sig nil]
             (let [i (:plan/intent world)
                   sig [(:intent/stage i) (:intent/dir i) (:intent/turns i) (:intent/status i)
                        (:intent/reason i) (:intent/lay i) (:intent/cells i) (:intent/level-cells i)
                        (some-> (:intent/child i)
                                :intent/kind) (intent/feet world)]]
               (when (and (not= sig last-sig) (< tick 40)) (println "  t" tick (pr-str sig)))
               (cond (>= tick 40)
                     (println "  ... 40 ticks, still"
                              (pr-str [(:intent/stage i) (:intent/status i)]))
                     (not= :active (:intent/status i :active))
                     (println "  end" (pr-str [(:intent/status i) (:intent/reason i)]))
                     :else (recur (intent/run world i nil) (inc tick) sig))))
           (catch Exception e (println "  ERR" (.getMessage e)))))))
(println "DONE")
