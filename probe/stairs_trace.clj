(ns probe.stairs-trace
  "Issue #65/#92: a snapshot's moment played on, the staircase's view of every heading printed."
  (:require [clojurecraft.blocks :as blocks]
            [clojurecraft.capture :as capture]
            [clojurecraft.intent :as intent]
            [clojurecraft.path :as path]
            [clojurecraft.physics :as physics]
            [clojurecraft.plan :as plan]
            [clojurecraft.scene :as scene]
            [clojurecraft.sim :as sim]
            [clojurecraft.simrace :as sr]
            [clojurecraft.stairs :as stairs]
            [clojurecraft.stone :as stone]
            [clojurecraft.terrain :as terrain]))

(defn nm
  "A cell's block name and fluid."
  [world c]
  [(some-> (terrain/block-at world c)
           blocks/name-of) (terrain/fluid-kind world c)])

(defn heading
  "What the staircase sees toward d from feet."
  [world feet d]
  (let [cells (stairs/stair-cells feet d)
        lc (stairs/level-cells feet d)]
    {:d d
     :stair (mapv #(nm world %) cells)
     :under (nm world (let [[x y z] (last cells)] [x (dec y) z]))
     :clear (stairs/clear-way? world cells)
     :safe (stairs/safe? world cells)
     :down-safe (stairs/down-safe? world cells)
     :down-lay (stairs/down-lay world feet cells)
     :level (mapv #(nm world %) lc)
     :lclear (stairs/level-clear? world lc)
     :lsafe (stairs/level-safe? world lc)
     :llay (stairs/level-lay world feet lc)
     :exp (mapv #(intent/exposes-fluid? world %) lc)}))

(defn up-heading
  "What a climb sees toward d from feet."
  [world feet d]
  (let [cells (stairs/up-cells feet d)]
    {:d d
     :up (mapv #(nm world %) cells)
     :up-safe (stairs/up-safe? world cells)
     :sealed (stairs/up-sealed? world cells)
     :dry (stairs/up-dry? world cells)
     :water-over (stairs/water-over? world cells)
     :up-lay (stairs/up-lay world feet cells)
     :seal (stairs/seal world feet cells)
     :shore (stairs/shore-distance world feet d)
     :dry-head (stairs/dry-head-distance world feet d)}))

(defn above
  "The standable cell on top of the column of cell c, or nil."
  [world [x _ z]]
  (some (fn [y]
          (when (some-> (terrain/block-at world [x y z])
                        blocks/solid?)
            (let [c [x (inc y) z]] (when (path/standable? world c) c))))
        (range terrain/top-y -65 -1)))

(defn chain
  "The intent and its children, small values."
  [world]
  (pr-str (map (fn [i]
                 (into {}
                       (filter (fn [[_ v]]
                                 (or (keyword? v)
                                     (number? v)
                                     (boolean? v)
                                     (and (vector? v) (< (count v) 5) (every? number? v)))))
                       i))
               (take-while some? (iterate :intent/child (:plan/intent world))))))

(defn -main
  "snapshot seconds every-ms from-s to-s: play the moment on, print the bot each every-ms, the
   staircase's analysis between from-s and to-s."
  [path seconds every from to]
  (let [snap (capture/read-world path)
        w (sr/world-for! snap)
        m (sr/moment-of snap w)
        t0 (:t m)
        every (Long/parseLong every)
        from (* 1000 (Long/parseLong from))
        to (* 1000 (Long/parseLong to))
        last-at (volatile! -1)
        stop?
        (fn [world]
          (let [el (- (:time/now world) t0)
                k (quot el every)]
            (when (not= k @last-at)
              (vreset! last-at k)
              (let [feet (intent/feet world)
                    kind (:intent/kind (:plan/intent world))
                    in? (<= from el to)]
                (println "AT"
                         (/ el 1000.0)
                         (mapv #(/ (long (* 10.0 %)) 10.0) (:player/pos world))
                         "feet"
                         feet
                         "filler"
                         (stairs/carried-filler world)
                         (pr-str (select-keys world
                                              [:player/in-water? :player/on-ground?
                                               :player/controls]))
                         (chain world))
                (when (and in? (#{:collect :walk} kind))
                  (let [i (:plan/intent world)
                        near (intent/nearest-item world
                                                  (some-> (:intent/target i)
                                                          physics/centre))
                        goal (or (:entity/pos near)
                                 (some-> (:intent/target i)
                                         physics/centre))]
                    (println
                     "  COLLECT goal" (pr-str goal)
                     "item"
                     (pr-str (some-> near
                                     (select-keys [:entity/pos :entity/item])))
                     "ledge?" (when goal (intent/ledge-ahead? world goal))
                     "straddle?"
                     (when (and goal (pos? (physics/horizontal-distance (:player/pos world) goal)))
                       (let [[px _ pz] (:player/pos world)
                             [gx _ gz] goal
                             d (physics/horizontal-distance [px 0 pz] [gx 0 gz])]
                         (intent/straddling-ahead? world [(/ (- gx px) d) (/ (- gz pz) d)])))
                     "ground" (:player/on-ground? world)))
                  (let [[fx fy fz] feet]
                    (doseq [y (range (- fy 4) (inc fy))]
                      (println "  COL y"
                               y
                               (pr-str (for [z (range (dec fz) (+ fz 2))]
                                         (for [x (range (- fx 2) (+ fx 2))]
                                           (let [[n f] (nm world [x y z])]
                                             (cond f (str "~" (name f))
                                                   n (subs (name n) 0 (min 3 (count (name n))))
                                                   :else "nil")))))))))
                (when (and in? (#{:stairs-down :stairs-up} kind))
                  (doseq [d stairs/directions]
                    (println "  HEAD" (pr-str (heading world feet d)))
                    (when (= :stairs-up kind) (println "  UP" (pr-str (up-heading world feet d)))))
                  (println "  boxed-at" (pr-str (:plan/boxed-at world))
                           "visited" (pr-str (:intent/visited (:plan/intent world)))
                           "go-at" (pr-str (:plan/go-at world))))
                (when (and in?
                           (zero? (mod (quot (- (:time/now world) t0) 1000) 5))
                           (zero? (mod k 1)))
                  (let [row (first (filter #(= :mine-stone (:goal/id %)) plan/goals))
                        cands (take 12 (stone/exposed-of-states world (stone/states-of-row row)))]
                    (println "  SPENT"
                             (stone/spent? world)
                             "boxed-count"
                             (count (:plan/boxed-at world))
                             "retreat"
                             (pr-str (stone/retreat world (:plan/boxed-at world)))
                             "home"
                             (pr-str (:plan/go-at world))
                             (pr-str (stone/toward-home world))
                             "deeper" (pr-str (stone/deeper world))
                             "tunnel" (pr-str (stone/tunnel-heading world))
                             "mine-next" (pr-str (stone/mine-next world row))
                             "over" (pr-str (stone/over-stone world row))
                             "stairs" (pr-str (stone/stairs world row)))
                    (println "  CANDS"
                             (pr-str (mapv (fn [c] [c
                                                    (let [up (above world c)]
                                                      [up
                                                       (when up
                                                         (intent/routable? world
                                                                           {:intent/kind :walk
                                                                            :intent/target up
                                                                            :intent/for
                                                                            :mine-stone}))])
                                                    (intent/routable? world
                                                                      {:intent/kind :walk
                                                                       :intent/target c
                                                                       :intent/for :mine-stone
                                                                       :intent/pickup? true})])
                                           cands)))))
                (when (and in? (zero? (mod k 4)))
                  (doseq [l (scene/slice world)] (println "  SLICE" l))))))
          (or (plan/done? world) (plan/failed? world)))
        r (sim/play-from sr/step
                         m
                         stop?
                         (* 1000 (Long/parseLong seconds))
                         (sr/go-of w (:snapshot/until snap))
                         nil)]
    (println "END"
             (:plan/status (:world r))
             (:plan/reason (:world r))
             (pr-str (:player/pos (:world r))))
    (shutdown-agents)))
