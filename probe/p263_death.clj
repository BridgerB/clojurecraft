;; Issue #263, round 8: what killed batch pearls-warped8-b's bots the first time (plan run
;; 39775345028: run 1 at about 24 minutes in lookouts, run 3 at about 6 minutes after a held fight
;; timed out :unreachable and the walks to its burrow failed :no-path). Folds the whole bot at the
;; recording's own commit and, from the first second given to the last, prints every second (and
;; every change of health) where it stands, its health, its intent, its burrow and the mouth's
;; column, and every entity within 16 blocks.
;;
;;   clojure -M p263_death.clj <run.edn.zst> <from-s> <to-s>
(require '[clojurecraft.main :as main]
         '[clojurecraft.game :as game]
         '[clojurecraft.record :as record]
         '[clojurecraft.terrain :as terrain]
         '[clojurecraft.blocks :as blocks]
         '[clojurecraft.pearls :as pearls]
         '[clojurecraft.scene :as scene]
         '[clojurecraft.entity :as entity]
         '[clojurecraft.intent :as intent]
         '[clojurecraft.physics :as physics]
         '[clojure.string :as str])

(defn out [& xs] (println (str/join " " xs)))

(def path-arg (first *command-line-args*))
(def from-ms (* 1000 (parse-long (second *command-line-args*))))
(def to-ms (* 1000 (parse-long (nth *command-line-args* 2))))

(defn at [world c] (name (or (blocks/name-of (terrain/block-at world c)) :unloaded)))

(defn column
  [world [x y z]]
  (str/join " " (for [dy (range -1 4)] (str (+ y dy) ":" (at world [x (+ y dy) z])))))

(defn dump
  [world off]
  (let [pos (:player/pos world)]
    (out "== +" (/ off 1000.0)
         "feet" (pr-str (intent/feet world))
         "health" (:player/health world)
         "intent" (pr-str (scene/brief-map (:plan/intent world))))
    (out "  shelter" (pr-str (:pearls/shelter world))
         "mouth" (pr-str (:pearls/mouth world))
         "shelter-fn" (pr-str (pearls/shelter world))
         "blacklist" (pr-str (:plan/blacklist world)))
    (when-let [m (:pearls/mouth world)] (out "  mouth column" (column world m)))
    (when-let [s (:pearls/shelter world)] (out "  end column" (column world s)))
    (doseq [[eid e] (sort-by (fn [[_ e]] (physics/distance pos (:entity/pos e)))
                             (:world/entities world))
            :when (and (not (entity/item? e)) (<= (physics/distance pos (:entity/pos e)) 16.0))]
      (out "   "
           eid
           (name (or (entity/kind e) :unknown))
           (pr-str (scene/rounded (:entity/pos e)))
           "dist" (format "%.1f" (physics/distance pos (:entity/pos e)))
           "creepy" (entity/creepy? e)
           "health" (:entity/health e)))))

(def world0 (game/init {:host "probe" :port 0 :name "C1"}))

(record/reduce-entries
 (fn [{:keys [world t0 next-ms health] :as acc} e]
   (cond (not (:event/kind e)) acc
         :else
         (let [w (assoc (main/step world e) :bot/effects [])
               now (:event/now e)
               t0 (or t0 now)
               off (when (and now t0) (- now t0))
               h (:player/health w)
               due? (and off (<= from-ms off to-ms) (or (>= off next-ms) (not= h health)))]
           (when due? (dump w off))
           {:world w :t0 t0 :next-ms (if due? (+ off 1000) next-ms) :health h})))
 {:world world0 :next-ms 0}
 path-arg)
(out "done")
(shutdown-agents)
