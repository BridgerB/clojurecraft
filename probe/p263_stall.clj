;; Issue #263, round 8: what holds a pearls-12 run for its last four hours (batch pearls-warped8-a,
;; plan run 39731473452: 5, 3 and 4 pearls by the 40th minute, then run 1 lured one angry enderman
;; from its burrow every 34 s for four hours and runs 2 and 3 stood in lookouts, no walk at all).
;; Folds the whole bot (main/step, at the recording's own commit) over a recording and, every
;; half hour and at the end, prints where it stands, its burrow and the mouth's roof, every
;; enderman it tracks, and what the hunt's helpers answer.
;;
;;   clojure -M p263_stall.clj <run.edn.zst>
(require '[clojurecraft.main :as main]
         '[clojurecraft.game :as game]
         '[clojurecraft.record :as record]
         '[clojurecraft.terrain :as terrain]
         '[clojurecraft.blocks :as blocks]
         '[clojurecraft.pearls :as pearls]
         '[clojurecraft.scene :as scene]
         '[clojurecraft.entity :as entity]
         '[clojurecraft.memory :as memory]
         '[clojurecraft.intent :as intent]
         '[clojurecraft.physics :as physics]
         '[clojure.string :as str])

(defn out [& xs] (println (str/join " " xs)))
(defn ask
  [label f]
  (try (out label (pr-str (f))) (catch Exception e (out label "THREW" (ex-message e)))))

(def path (first *command-line-args*))
(def moments (mapv #(* 1000 %) [600 1500 2400 3600 5400 7200 10800 14400 17900]))

(defn at [world c] (name (or (blocks/name-of (terrain/block-at world c)) :unloaded)))

(defn column
  [world [x _ z] y0 y1]
  (str/join " " (for [y (range y0 (inc y1))] (str y ":" (at world [x y z])))))

(defn dump
  [world label]
  (let [pos (:player/pos world)
        feet (intent/feet world)
        mouth (:pearls/mouth world)
        home (:pearls/shelter world)
        bl (:plan/blacklist world #{})]
    (out "==" label
         "now" (:time/now world)
         "feet" (pr-str feet)
         "health" (:player/health world)
         "food" (:player/food world)
         "digs-left" (pearls/digs-left world))
    (out " intent" (pr-str (scene/brief-map (:plan/intent world)))
         "last" (pr-str (scene/brief-map (:plan/last world))))
    (out " shelter" (pr-str home)
         "mouth" (pr-str mouth)
         "shelter-fn" (pr-str (pearls/shelter world))
         "stale?" (pearls/stale? world)
         "lure?" (pearls/lure? world)
         "lookouts-since-burrow" (memory/done-since world :lookout :burrow)
         "lookouts-since-walk" (memory/done-since world :lookout :walk))
    (when mouth
      (out " mouth column" (column world mouth (- (second mouth) 2) (+ (second mouth) 4))
           "enderman stand? at mouth" (pearls/stand? world mouth)))
    (when home (out " end column" (column world home (dec (second home)) (+ (second home) 3))))
    (out " blacklist" (pr-str bl) "boxed-at" (pr-str (:plan/boxed-at world)))
    (ask " arrived-count" #(count (memory/arrived world)))
    (let [es (pearls/endermen (dissoc world :plan/blacklist) 128.0)]
      (out " endermen within 128:" (count es))
      (doseq [[eid e] (sort-by (fn [[_ e]] (physics/distance pos (:entity/pos e))) es)]
        (let [c (pearls/cell (:entity/pos e))]
          (out "   "
               eid
               (pr-str (scene/rounded (:entity/pos e)))
               "dist" (format "%.1f" (physics/distance pos (:entity/pos e)))
               "dy" (- (second c) (second feet))
               "creepy" (entity/creepy? e)
               "health" (:entity/health e)
               "blacklisted" (contains? bl eid)
               "stand?" (pearls/stand? world c)
               "seen-at" (:entity/seen-at e)))))
    (ask " angry-near" #(first (pearls/angry-near world)))
    (ask " at-hand" #(first (pearls/at-hand world)))
    (ask " quarry" #(first (pearls/quarry world)))
    (ask " crowded?" #(pearls/crowded? world))
    (ask " blocking" #(first (pearls/blocking world)))
    (let [far (pearls/endermen world pearls/seek-radius)
          all (pearls/endermen world pearls/descend-radius)]
      (ask " routable"
           #(some-> (pearls/routable world far)
                    first))
      (ask " descend" #(pearls/descend world all))
      (ask " ascend" #(pearls/ascend world all))
      (ask " toward" #(pearls/toward world far))
      (ask " bore" #(pearls/bore world all))
      (ask " roam" #(pearls/roam world))
      (ask " vantage" #(pearls/vantage world far)))
    (doseq [line (scene/slice world)] (out "  " line))))

(def world0 (game/init {:host "probe" :port 0 :name "C1"}))

(let [final (record/reduce-entries (fn [{:keys [world t0 moments] :as acc} e]
                                     (cond (not (:event/kind e)) acc
                                           :else
                                           (let [w (assoc (main/step world e) :bot/effects [])
                                                 now (:event/now e)
                                                 t0 (or t0 now)
                                                 off (when (and now t0) (- now t0))
                                                 [m & ms] moments
                                                 fired? (and off m (>= off m))]
                                             (when fired? (dump w (str "+" (/ m 1000.0) "s")))
                                             {:world w :t0 t0 :moments (if fired? ms moments)})))
                                   {:world world0 :moments moments}
                                   path)]
  (dump (:world final) "end"))
(out "done")
(shutdown-agents)
