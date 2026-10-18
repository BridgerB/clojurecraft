(ns probe.stairs-trace
  "Issue #65/#92: a snapshot's moment played on, the staircase's view of every heading printed."
  (:require [clojurecraft.blocks :as blocks]
            [clojurecraft.capture :as capture]
            [clojurecraft.intent :as intent]
            [clojurecraft.path :as path]
            [clojurecraft.plan :as plan]
            [clojurecraft.scene :as scene]
            [clojurecraft.sim :as sim]
            [clojurecraft.simrace :as sr]
            [clojurecraft.stairs :as stairs]
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

(defn chain
  "The intent and its children, small values."
  [world]
  (pr-str (map #(select-keys %
                             [:intent/kind :intent/stage :intent/reason :intent/status :intent/turns
                              :intent/dir :intent/level-steps :intent/for])
               (take-while some? (iterate :intent/child (:plan/intent world))))))

(defn -main
  "snapshot seconds every-s: play the moment on, print the bot each every-s."
  [path seconds every]
  (let [snap (capture/read-world path)
        w (sr/world-for! snap)
        m (sr/moment-of snap w)
        t0 (:t m)
        every (* 1000 (Long/parseLong every))
        last-at (volatile! -1)
        stop? (fn [world]
                (let [k (quot (- (:time/now world) t0) every)]
                  (when (not= k @last-at)
                    (vreset! last-at k)
                    (let [feet (intent/feet world)
                          kind (:intent/kind (:plan/intent world))]
                      (println "AT"
                               (quot (- (:time/now world) t0) 1000)
                               (mapv #(/ (long (* 10.0 %)) 10.0) (:player/pos world))
                               "feet"
                               feet
                               (chain world))
                      (when (#{:stairs-down :stairs-up} kind)
                        (doseq [d stairs/directions]
                          (println "  HEAD" (pr-str (heading world feet d))))
                        (println "  boxed-at" (pr-str (:plan/boxed-at world))
                                 "visited" (pr-str (:intent/visited (:plan/intent world)))))
                      (when (or (zero? k) (zero? (mod k 4)))
                        (doseq [l (scene/slice world)] (println "  SLICE" l)))))
                  (or (plan/done? world) (plan/failed? world))))
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
