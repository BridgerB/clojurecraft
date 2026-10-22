;; Issue #263, sim-endermen: the pearl stage played in a captured Nether against the model's own
;; warped-forest spawns. For each set named, finds the warped forest of its captured Nether
;; (the chunk with the most open nylium), puts the bot there with the chain's kit at Normal and
;; plays :ender-pearls (twelve pearls) for up to max-min simulated minutes, logging every
;; enderman spawned, provoked and killed (with whether it dropped a pearl) and every change of
;; the pearls held, then a summary line per set.
;;
;;   clojure -M probe/sim_endermen_pearls.clj <max-min> <seed> R1 [R2 ...]
(require '[clojurecraft.blocks :as blocks]
         '[clojurecraft.dimension :as dimension]
         '[clojurecraft.game :as game]
         '[clojurecraft.plan :as plan]
         '[clojurecraft.recipe :as recipe]
         '[clojurecraft.sim :as sim]
         '[clojurecraft.simrace :as simrace]
         '[clojure.string :as str])

(defn out [& xs] (locking *out* (println (str/join " " xs)) (flush)))

(def max-min (parse-long (first *command-line-args*)))
(def seed (parse-long (second *command-line-args*)))
(def sets (drop 2 *command-line-args*))
(def nylium (blocks/states-of :warped_nylium))
(def wall-end (+ (System/currentTimeMillis) (* 40 60000))) ; stop every run by 40 wall minutes, inside the probe's hour
(def tags (atom {}))                   ; thread → set name, for the wrapped model functions

(defn tag [] (get @tags (.getId (Thread/currentThread)) "?"))
(defn secs [s] (quot (:sim/now s 0) 1000))

;; the model's functions wrapped to log, by their vars (no direct linking)
(let [add sim/add-mob]
  (alter-var-root #'sim/add-mob
                  (constantly (fn [s kind pos opts]
                                (let [s' (add s kind pos opts)]
                                  (when (= :enderman kind)
                                    (out (tag)
                                         "SPAWN t" (secs s)
                                         "enderman at" (pr-str (mapv long pos))
                                         "player" (pr-str (mapv long (:sim/player-pos s)))))
                                  s')))))
(let [ang sim/anger]
  (alter-var-root #'sim/anger
                  (constantly (fn [s eid]
                                (let [m (get-in s [:sim/mobs eid])]
                                  (when (and (= :enderman (:type m)) (not (:angry? m)))
                                    (out (tag) "PROVOKED t" (secs s) "eid" eid)))
                                (ang s eid)))))
(let [die sim/die-mob]
  (alter-var-root #'sim/die-mob
                  (constantly (fn [s eid by-player?]
                                (let [m (get-in s [:sim/mobs eid])
                                      s' (die s eid by-player?)
                                      pearl (recipe/item-id :ender_pearl)
                                      dropped (- (count (filter #(= pearl (:item (:item %)))
                                                                (vals (:sim/items s'))))
                                                 (count (filter #(= pearl (:item (:item %)))
                                                                (vals (:sim/items s)))))]
                                  (out (tag)
                                       "KILLED t"
                                       (secs s)
                                       (name (:type m))
                                       "eid" eid
                                       "by-player" by-player?
                                       "pearl-drops" dropped)
                                  s')))))

(defn forest-spot
  "The open nylium cell nearest the middle of the chunk with the most open nylium, or nil."
  [s]
  (let [free? (sim/free-fn s)
        open? (fn [[x y z]]
                (and (contains? nylium (sim/block-at s [x y z]))
                     (every? #(free? [x (+ y %) z]) [1 2 3])))
        cells (fn [[cx cz]]
                (for [dx (range 0 16 2)
                      dz (range 0 16 2)
                      y (range 32 110)
                      :let [c [(+ (* 16 cx) dx) y (+ (* 16 cz) dz)]]
                      :when (open? c)]
                  c))
        best (apply max-key
                    (comp count second)
                    (map (fn [k] [k (vec (cells k))]) (keys (:sim/columns s))))
        [[cx cz] cs] best]
    (out (tag) "forest chunk" (pr-str [cx cz]) "open nylium samples" (count cs))
    (when (seq cs)
      (apply min-key
             (fn [[x _ z]] (+ (abs (- x (+ (* 16 cx) 8))) (abs (- z (+ (* 16 cz) 8)))))
             cs))))

(defn item [k n] {:item (recipe/item-id k) :count n})

(def kit
  {[:hotbar 0] (item :iron_sword 1)
   [:hotbar 1] (item :iron_pickaxe 1)
   [:hotbar 2] (item :cobblestone 64)
   [:hotbar 3] (item :cooked_beef 16)
   [:hotbar 4] (item :blaze_rod 7)
   [:armour 0] (item :iron_helmet 1)
   [:armour 1] (item :iron_chestplate 1)
   [:armour 2] (item :iron_leggings 1)
   [:armour 3] (item :iron_boots 1)})

(defn play
  [set-name]
  (swap! tags assoc (.getId (Thread/currentThread)) set-name)
  (let [n (simrace/read-nether (str "cache/sim-nether/" set-name ".edn.gz"))
        base {:columns (:columns n)
              :decoded (:decoded n)
              :view-distance simrace/nether-view
              :dimension dimension/nether
              :difficulty :normal
              :food 20
              :seed seed
              :tick-seed seed
              :inventory kit}
        [x y z :as spot] (forest-spot (sim/init (assoc base :spawn [0.5 64.0 0.5])))]
    (cond (nil? spot) (out set-name "SUMMARY no warped forest found")
          :else
          (let [held (atom 0)
                deaths (atom 0)
                spawn [(+ x 0.5) (double (inc y)) (+ z 0.5)]
                _ (out set-name "start at" (pr-str spawn))
                watch (fn [w]
                        (let [k (get (recipe/counts (:player/inventory w)) :ender_pearl 0)
                              d (:stats/deaths w 0)]
                          (when (not= k @held)
                            (out set-name "PEARLS t" (quot (:time/now w 0) 1000) "held" k)
                            (reset! held k))
                          (when (not= d @deaths)
                            (out set-name "DEATH t" (quot (:time/now w 0) 1000) "deaths" d)
                            (reset! deaths d))))
                [w s] (sim/run simrace/step
                               (game/init {:host "sim" :port 0 :name "Clj_sim"})
                               (sim/init (assoc base :spawn spawn))
                               #(do (watch %)
                                    (or (plan/done? %)
                                        (plan/failed? %)
                                        (> (System/currentTimeMillis) wall-end)))
                               (* 60000 max-min)
                               {:event/kind :go :go/goals [:ender-pearls]})]
            (out set-name
                 "SUMMARY done" (plan/done? w)
                 "pearls" @held
                 "deaths" (:stats/deaths w 0)
                 "sim-min" (quot (:time/now w 0) 60000)
                 "endermen-alive"
                 (count (filter #(and (= :enderman (:type %)) (not (:dead-at %)))
                                (vals (:sim/mobs s))))
                 "violations" (count (:sim/violations s))
                 "plan" (pr-str (plan/summary w)))))))

(run! deref (mapv #(future (try (play %) (catch Exception e (out % "CRASH" (str e))))) sets))
(out "done")
(shutdown-agents)
