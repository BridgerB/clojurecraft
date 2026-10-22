;; Issue #263, round 8: why the roam finds nothing for four hours (batch pearls-warped8-a, plan
;; run 39731473452, run 2: from the 21st minute it stood in lookouts at y 45 in the stairs and
;; tunnels it had cut, its pickaxe at 21 digs, every helper of the hunt nil, probe 39776762205).
;; Folds the whole bot over a recording to the given second and asks the roam, heading by heading
;; and reach by reach, what the route answers: its status, its end, whether that end is spent,
;; and the same route with ten times the expansions; then the route to the burrow's mouth.
;;
;;   clojure -M p263_roam.clj <run.edn.zst> <seconds>
(require '[clojurecraft.main :as main]
         '[clojurecraft.game :as game]
         '[clojurecraft.record :as record]
         '[clojurecraft.pearls :as pearls]
         '[clojurecraft.path :as path]
         '[clojurecraft.memory :as memory]
         '[clojurecraft.intent :as intent]
         '[clojure.string :as str])

(defn out [& xs] (println (str/join " " xs)))

(def path-arg (first *command-line-args*))
(def stop-ms (* 1000 (parse-long (second *command-line-args*))))

(defn ask-route
  [world goal opts]
  (let [r (path/plan world (path/feet-cell (:player/pos world)) goal opts)]
    [(:path/status r) (count (:path/waypoints r)) (last (:path/waypoints r))]))

(defn dump
  [world]
  (let [feet (intent/feet world)
        spent (memory/arrived world)
        start (mod (quot (:time/now world 0) pearls/roam-turn-ms) (count pearls/headings))]
    (out "== now" (:time/now world)
         "feet" (pr-str feet)
         "digs-left" (pearls/digs-left world)
         "spent" (count spent)
         "start" start
         "roam" (pr-str (pearls/roam world)))
    (out " spent ends" (pr-str (sort spent)))
    (doseq [reach pearls/roam-reaches
            heading pearls/headings]
      (let [goal (pearls/roam-goal feet heading reach)
            [s n to] (ask-route world goal {})
            [s2 n2 to2] (ask-route world goal {:path/max-nodes 60000})]
        (out " reach"
             reach
             "heading"
             (pr-str heading)
             "->"
             s
             n
             (pr-str to)
             "spent?"
             (contains? spent to)
             "| x10:"
             s2
             n2
             (pr-str to2)
             "spent?"
             (contains? spent to2))))
    (doseq [[label c] [["mouth" (:pearls/mouth world)] ["shelter" (:pearls/shelter world)]]]
      (when c
        (let [g (intent/goal-of {:intent/kind :walk :intent/target c})]
          (out " to"
               label
               (pr-str c)
               (pr-str (ask-route world g {}))
               "x10"
               (pr-str (ask-route world g {:path/max-nodes 60000}))))))))

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
(dump (:world final))
(out "done")
(shutdown-agents)
