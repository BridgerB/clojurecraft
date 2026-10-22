;; Issue #263, round 8: why the enderman the lure brings to the burrow's mouth never comes down
;; the four-high stairs (batch pearls-warped7-b's run 3: six held fights :unreachable on 80).
;; Folds the protocol reducer alone (game/step, no planner) over the run's recording and, at
;; the fight's moments, prints the blocks round the mouth, the stairs' headroom, where the
;; enderman stands and the hunt's own tests of the cells.
;;
;;   clojure -M probe/p263_mouth.clj out/job3/pearls-12-head-3/run-3/run.edn
(require '[clojurecraft.game :as game]
         '[clojurecraft.record :as record]
         '[clojurecraft.terrain :as terrain]
         '[clojurecraft.blocks :as blocks]
         '[clojurecraft.pearls :as pearls]
         '[clojurecraft.scene :as scene]
         '[clojurecraft.entity :as entity]
         '[clojurecraft.physics :as physics]
         '[clojure.string :as str])

(defn out [& xs] (println (str/join " " xs)))

(def path (first *command-line-args*))
(def mouth [2470 54 2202])                       ; bot.log: the lure walks' target
(def stairs [[2470 53 2203] [2470 52 2204] [2470 51 2205]]) ; the feet on each stair, from the digs
(def tunnel [[2470 51 2206] [2470 51 2207]])
(def eid 80)
(def t0 1792645761094)                           ; the first tick's :now, 05:09:21 UTC
(def moments [93500 129000 185000 264000 429000]) ; ms after t0: fight 1 begins, mid fight 1, the lure at the mouth, fight 2, fight 4
(def track [86000 200000])                        ; the enderman's position every two seconds over this window

(defn at [world c] (blocks/name-of (terrain/block-at world c)))

(defn column
  "The names at [x z] from y0 to y1, bottom first."
  [world [x _ z] y0 y1]
  (str/join " " (for [y (range y0 (inc y1))] (str y ":" (name (or (at world [x y z]) :unloaded))))))

(defn grid
  "The blocks at height y over x 2462..2478, z 2196..2210 as scene glyphs, one line a z."
  [world y]
  (let [xs (range 2462 2479)
        zs (range 2196 2211)
        rows (vec (for [z zs] (vec (for [x xs] (at world [x y z])))))
        glyph (scene/legend [[0 rows]])
        present (set (apply concat rows))]
    (doseq [[z row] (map vector zs rows)]
      (out (format "  y%d z%d %s"
                   y
                   z
                   (str/join (map-indexed (fn [i n]
                                            (let [x (nth xs i)]
                                              (cond (and (= [x z] [2470 2202]) (= y 54)) "@"
                                                    :else (glyph n))))
                                          row)))))
    (out "  legend"
         (str/join " "
                   (for [[n g] (sort-by val glyph)
                         :when (contains? present n)]
                     (str g "=" (if n (name n) "unloaded")))))))

(defn dump
  [world label]
  (let [e (get-in world [:world/entities eid])
        pos (:player/pos world)]
    (out "==" label
         "now" (:time/now world)
         "bot"
         (pr-str (some-> pos
                         scene/rounded))
         "e80"
         (pr-str (some-> (:entity/pos e)
                         scene/rounded))
         "creepy" (entity/creepy? e)
         "dist" (when (and pos e) (format "%.1f" (physics/distance pos (:entity/pos e)))))
    (out " mouth column" (column world mouth 50 60))
    (doseq [c stairs] (out " stair" (pr-str c) "column" (column world c 49 59)))
    (doseq [c tunnel] (out " tunnel" (pr-str c) "column" (column world c 49 56)))
    (out " enderman stand? at mouth" (pearls/stand? world mouth)
         "stairs" (pr-str (mapv #(pearls/stand? world %) stairs))
         "tunnel" (pr-str (mapv #(pearls/stand? world %) tunnel)))
    (out " shelter? end" (pearls/shelter? world (last tunnel))
         "burrowable? from mouth [0 1]" (pearls/burrowable? world mouth [0 1]))
    (when e
      (let [c (pearls/cell (:entity/pos e))]
        (out " e80 cell" (pr-str c)
             "stand?" (pearls/stand? world c)
             "column" (column world c 48 58))))
    (out " cells round the mouth an enderman could stand in (stand?), y 50..57:")
    (doseq [dx [-3 -2 -1 0 1 2 3]
            dz [-3 -2 -1 0 1 2 3]
            y (range 50 58)
            :let [c [(+ 2470 dx) y (+ 2202 dz)]]
            :when (pearls/stand? world c)]
      (out "   " (pr-str c)))
    (doseq [y (range 51 58)] (grid world y))))

(def world0 (game/init {:host "probe" :port 0 :name "C1r3"}))

(record/reduce-entries (fn [{:keys [world moments last-track] :as acc} e]
                         (cond (not (:event/kind e)) acc
                               :else
                               (let [w (assoc (game/step world e) :bot/effects [])
                                     now (:event/now e)
                                     off (when now (- now t0))
                                     [m & ms] moments
                                     fired? (and off m (>= off m))
                                     tracked? (and off
                                                   (<= (first track) off (second track))
                                                   (or (nil? last-track)
                                                       (>= (- off last-track) 2000)))]
                                 (when tracked?
                                   (let [en (get-in w [:world/entities eid])]
                                     (out "t" (format "%.1f" (/ off 1000.0))
                                          "bot"
                                          (pr-str (some-> (:player/pos w)
                                                          scene/rounded))
                                          "e80"
                                          (pr-str (some-> (:entity/pos en)
                                                          scene/rounded))
                                          "creepy" (entity/creepy? en)
                                          "cell"
                                          (pr-str (some-> (:entity/pos en)
                                                          pearls/cell)))))
                                 (when fired? (dump w (str "+" (/ m 1000.0) "s")))
                                 (cond (and fired? (empty? ms)) (reduced {:world w})
                                       :else
                                       {:world w
                                        :moments (if fired? ms moments)
                                        :last-track (if tracked? off last-track)}))))
                       {:world world0 :moments moments}
                       path)
(out "done")
(shutdown-agents)
