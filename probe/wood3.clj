(ns probe.wood3
  "Wood3 probe: the play of a snapshot with every intent change, the column under the feet, and
   the trunks the planner sees."
  (:require [clojure.math :as math]
            [clojure.string :as str]
            [clojurecraft.blocks :as blocks]
            [clojurecraft.capture :as capture]
            [clojurecraft.game :as game]
            [clojurecraft.intent :as intent]
            [clojurecraft.memory :as memory]
            [clojurecraft.sim :as sim]
            [clojurecraft.simrace :as simrace]
            [clojurecraft.stairs :as stairs]
            [clojurecraft.terrain :as terrain]
            [clojurecraft.wood :as wood]))

(declare diag heights)

(defn column
  "The blocks of the feet's column from 30 over the feet down 60, as runs: [top bottom name]."
  [world [fx fy fz]]
  (let [x (long (math/floor fx))
        y (long (math/floor fy))
        z (long (math/floor fz))
        cells (for [dy (range 30 -60 -1)]
                [(+ y dy) (blocks/name-of (terrain/block-at world [x (+ y dy) z]))])]
    (println "COLUMN"
             [x y z]
             (pr-str (mapv (fn [run] [(ffirst run) (first (last run)) (second (first run))])
                           (partition-by second cells))))))

(defn brief
  [i]
  (when i
    (into (sorted-map)
          (filter (fn [[_ v]] (or (keyword? v) (number? v) (and (vector? v) (<= (count v) 4)))))
          i)))

(defn go
  [path]
  (let [snap (capture/read-world path)
        w (simrace/world-for! snap)
        {:keys [world] :as m} (simrace/moment-of snap w)
        seen (volatile! nil)
        t0 (:time/now world)
        stop? (fn [wd]
                (let [i (:plan/intent wd)
                      k [(:intent/id i) (:plan/wait wd) (:plan/status wd)]]
                  (when (not= k @seen)
                    (vreset! seen k)
                    (println "AT"
                             (- (:time/now wd) t0)
                             (some-> (:player/pos wd)
                                     (->> (mapv math/round)))
                             "wait"
                             (:plan/wait wd)
                             "status"
                             (:plan/status wd)
                             "boxed"
                             (:plan/boxed-at wd)
                             (pr-str (brief i))))
                  (or (= :failed (:plan/status wd)) false)))
        _ (println "PATH" path)
        _ (doseq [f (take-last 45 (filter #(< (:intention/at % 0) t0) (memory/intentions world)))]
            (println "BEFORE"
                     (- (:intention/at f 0) t0)
                     (:intention/event f)
                     (:intention/kind f)
                     (:intention/target f)
                     (:intention/reason f)))
        _ (column world (:player/pos world))
        _ (println "GATHER-NEXT" (pr-str (brief (wood/gather-next world))))
        _ (println "BLACKLIST" (pr-str (:plan/blacklist world))
                   "unreached" (pr-str (:plan/unreached world))
                   "failures" (pr-str (:plan/trunk-failures world)))
        r (sim/play-from simrace/step
                         m
                         stop?
                         (simrace/window-of snap)
                         (simrace/go-of w (:snapshot/until snap))
                         nil)]
    (println "ENDED"
             (pr-str (some-> r
                             :world
                             :player/pos)))
    (diag (:world r))
    (column (:world r) (:player/pos (:world r)))
    (heights (:world r))))

(defn -main [& paths] (doseq [p paths] (go p)) (shutdown-agents))

(defn diag
  "What climb-out and its neighbours see in world."
  [world]
  (let [feet (intent/feet world)
        eye (game/eye world)
        skip (wood/skipped world)
        logs (->> (memory/positions-now world blocks/log-states)
                  (memory/nearest-first eye wood/far-radius))
        trunks (->> logs
                    (remove skip)
                    (filter #(wood/standing-trunk? world %))
                    (take wood/far-candidates))]
    (println "DIAG feet" feet
             "eye" (mapv math/round eye)
             "logs-within-256" (count logs)
             "out?" (stairs/out? world feet)
             "under-sky?" (terrain/under-sky? world feet)
             "in-water?" (:player/in-water? world))
    (doseq [p (take 6 logs)]
      (println "  LOG" p
               "dx" (- (first p) (first feet))
               "dy" (- (second p) (second feet))
               "dz" (- (nth p 2) (nth feet 2))
               "skipped" (boolean (skip p))
               "standing" (boolean (wood/standing-trunk? world p))
               "routable" (boolean (intent/routable? world (wood/log-walk world p)))))
    (println "  TRUNKS" (pr-str trunks))
    (println "  hunt" (pr-str (brief (wood/hunt world)))
             "crossing" (pr-str (brief (wood/crossing world)))
             "trees-climb" (pr-str (brief (wood/trees-climb world trunks)))
             "trees-bridge" (pr-str (brief (wood/trees-bridge world trunks)))
             "trees-tunnel" (pr-str (brief (wood/trees-tunnel world)))
             "trees-descend" (pr-str (brief (wood/trees-descend world trunks)))
             "return-walk" (pr-str (brief (wood/return-walk world))))))

(defn heights
  "A map of the ground round the feet: the top solid height of every other column (x every 2, z every
   4, 40 out each way), '..' where nothing loaded, 'TT' a column holding a remembered log, '@@' the feet."
  [world]
  (let [[fx fy fz] (intent/feet world)
        logs (into #{} (map (fn [[x _ z]] [x z])) (memory/positions-now world blocks/log-states))
        top (fn [x z]
              (some (fn [y]
                      (let [id (terrain/block-at world [x y z])]
                        (when (and id (blocks/solid? id) (not (blocks/leaves? id))) y)))
                    (range (+ fy 12) (- fy 70) -1)))]
    (println "HEIGHTS feet"
             [fx fy fz]
             "x from"
             (- fx 40)
             "to"
             (+ fx 40)
             "step 2; z from"
             (- fz 40)
             "step 4")
    (doseq [dz (range -40 41 4)]
      (println
       (format "%5d" (+ fz dz))
       (str/join
        (for [dx (range -40 41 2)]
          (let [x (+ fx dx)
                z (+ fz dz)]
            (cond (and (zero? dx) (zero? dz)) "@@ "
                  (or (logs [x z]) (logs [(inc x) z]) (logs [x (inc z)]) (logs [(inc x) (inc z)]))
                  "TT "
                  :else
                  (if-let [t (top x z)]
                    (format "%02d " (mod (- t fy -50) 100))
                    ".. ")))))))))
