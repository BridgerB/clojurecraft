;; Issue #263, round 9 (pearls11): a whole pearls-12 gym run at a glance. Folds the bot at the
;; recording's own commit and prints a line every minute (feet, health, food, pearls, intent,
;; burrow, the hunt's reasons), every change of the pearls held, and at each death the forty
;; seconds before it, a line a second, with every entity within 16 blocks at the death.
;;
;;   clojure -M p263_deaths9.clj <run.edn.zst>
(require '[clojurecraft.main :as main]
         '[clojurecraft.game :as game]
         '[clojurecraft.record :as record]
         '[clojurecraft.recipe :as recipe]
         '[clojurecraft.pearls :as pearls]
         '[clojurecraft.entity :as entity]
         '[clojurecraft.intent :as intent]
         '[clojurecraft.physics :as physics]
         '[clojure.string :as str])

(defn out [& xs] (println (str/join " " xs)))

(def path-arg (first *command-line-args*))

(defn line
  [w off]
  (str "+" (quot off 1000)
       " feet " (pr-str (intent/feet w))
       " health "
       (some-> (:player/health w)
               (* 10)
               clojure.math/round
               (/ 10.0))
       " food " (:player/food w)
       " pearls " (get (recipe/counts (:player/inventory w)) :ender_pearl 0)
       " intent "
       (pr-str (select-keys (:plan/intent w)
                            [:intent/kind :intent/for :intent/target :intent/hold? :intent/stage]))
       " shelter " (pr-str (:pearls/shelter w))
       " pick " (pearls/digs-left w)))

(defn why
  [w]
  (pr-str (into {}
                (for [[k f] [[:shelter pearls/shelter] [:angry-near (comp first pearls/angry-near)]
                             [:crowded? pearls/crowded?] [:quarry (comp first pearls/quarry)]
                             [:at-hand (comp first pearls/at-hand)] [:stale? pearls/stale?]
                             [:last #(select-keys (:plan/last %) [:intent/kind :intent/status])]]]
                  [k (try (f w) (catch Exception e (str "THREW " (ex-message e))))]))))

(defn near
  [w]
  (let [pos (:player/pos w)]
    (doseq [[eid e] (:world/entities w)
            :when (and (not (entity/item? e)) (<= (physics/distance pos (:entity/pos e)) 16.0))]
      (out "   "
           eid
           (name (or (entity/kind e) :unknown))
           "dist" (format "%.1f" (physics/distance pos (:entity/pos e)))
           "creepy" (entity/creepy? e)
           "blacklisted" (contains? (:plan/blacklist w #{}) eid)))))

(def world0 (game/init {:host "probe" :port 0 :name "C1"}))

(record/reduce-entries
 (fn [{:keys [world t0 sec minute ring deaths pearls] :as acc} e]
   (cond (not (:event/kind e)) acc
         :else
         (let [w (assoc (main/step world e) :bot/effects [])
               now (:event/now e)
               t0 (or t0 now)
               off (if (and now t0) (- now t0) 0)
               s (quot off 1000)
               ring (if (not= s sec) (vec (take-last 40 (conj ring (line w off)))) ring)
               d (:stats/deaths w 0)
               p (get (recipe/counts (:player/inventory w)) :ender_pearl 0)]
           (when (not= (quot off 60000) minute) (out "MINUTE" (line w off) "why" (why w)))
           (when (not= p pearls) (out "PEARLS" (line w off)))
           (when (> d deaths) (out "DEATH" d "at +" s) (doseq [l ring] (out "  " l)) (near world))
           {:world w :t0 t0 :sec s :minute (quot off 60000) :ring ring :deaths d :pearls p})))
 {:world world0 :sec -1 :minute -1 :ring [] :deaths 0 :pearls 0}
 path-arg)
(out "done")
(shutdown-agents)
