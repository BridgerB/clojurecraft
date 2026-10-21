(ns probe.castsite
  "Step each snapshot from its moment, printing the intents' changes."
  (:require [clojure.math :as math]
            [clojurecraft.capture :as capture]
            [clojurecraft.intent :as intent]
            [clojurecraft.plan :as plan]
            [clojurecraft.probe :as probe]
            [clojurecraft.sim :as sim]
            [clojurecraft.simrace :as simrace]))

(defn view
  "What changes between lines: the intent and its child's kind, aim and reason, no cells."
  [w]
  (let [i (:plan/intent w)
        c (:intent/child i)]
    [(select-keys i [:intent/kind :intent/for :intent/build :intent/status :intent/reason])
     (select-keys c [:intent/kind :intent/for :intent/status :intent/reason]) (:plan/wait w)]))

(defn trace
  [path]
  (let [snap (capture/read-world path)
        w (simrace/world-for! snap)
        m (simrace/moment-of snap w)
        prev (volatile! nil)
        n (volatile! 0)
        stop? (fn [world]
                (let [v (view world)]
                  (when (and (not= v @prev) (< @n 400))
                    (vswap! n inc)
                    (println (:time/now world) (pr-str (mapv long (intent/feet world))) (pr-str v)))
                  (vreset! prev v)
                  (or (plan/done? world) (plan/failed? world))))]
    (println "TRACE" path (pr-str (:snapshot/signature snap)))
    (sim/play-from simrace/step
                   m
                   stop?
                   (simrace/window-of snap)
                   (simrace/go-of w (:snapshot/until snap))
                   nil)))

(defn -main [& paths] (doseq [p paths] (trace p)) (shutdown-agents))

(defn ticks
  "Print every every-th tick between from and to ms of the snapshot at path: the body, the
   controls and the walk's route."
  [path from to every]
  (let [snap (capture/read-world path)
        w (simrace/world-for! snap)
        m (simrace/moment-of snap w)
        k (volatile! 0)
        stop? (fn [world]
                (let [t (:time/now world)
                      i (:plan/intent world)]
                  (when (and (<= from t to) (zero? (mod (vswap! k inc) every)))
                    (println t
                             (pr-str (mapv #(/ (math/round (* 100.0 %)) 100.0) (:player/pos world)))
                             (pr-str (mapv #(/ (math/round (* 100.0 %)) 100.0) (:player/vel world)))
                             (pr-str (keys (filter val (:player/controls world))))
                             (pr-str (select-keys world
                                                  [:player/in-water? :player/on-ground?
                                                   :player/in-lava? :player/horizontal-collision?]))
                             (pr-str (select-keys i
                                                  [:intent/kind :intent/stage :intent/target
                                                   :intent/at :intent/replans :intent/route]))
                             (pr-str (get (:intent/waypoints i) (:intent/at i)))
                             (pr-str (count (:intent/waypoints i)))))
                  (> t to)))]
    (println "TICKS" path from to)
    (sim/play-from simrace/step
                   m
                   stop?
                   (simrace/window-of snap)
                   (simrace/go-of w (:snapshot/until snap))
                   nil)))

(defn -main2
  [path from to every]
  (ticks path (parse-long (str from)) (parse-long (str to)) (parse-long (str every)))
  (shutdown-agents))

(defn ask
  "Play the snapshot at path to ms, then print what each qualified function of fns says of the
   world there, and the cells round the feet."
  [path ms fns]
  (let [snap (capture/read-world path)
        w (simrace/world-for! snap)
        m (simrace/moment-of snap w)
        stop? (fn [world] (>= (:time/now world) ms))
        r (sim/play-from simrace/step
                         m
                         stop?
                         (simrace/window-of snap)
                         (simrace/go-of w (:snapshot/until snap))
                         nil)
        world (:world r)]
    (println "ASK" path ms (pr-str (mapv long (intent/feet world))))
    (println "INTENT" (pr-str (dissoc (:plan/intent world) :intent/waypoints)))
    (println "PLAN"
             (pr-str (select-keys world
                                  [:plan/attempts :plan/unwalkable :plan/wait :plan/boxed-at
                                   :plan/blacklist])))
    (doseq [line (probe/around world 6)] (println "AROUND" line))
    (doseq [f fns]
      (println f
               "=>"
               (try (let [s (pr-str ((requiring-resolve (symbol (str f))) world))]
                      (subs s 0 (min 800 (count s))))
                    (catch Exception e (str "ERR " e)))))))

(defn -main3 [path ms & fns] (ask path (parse-long (str ms)) fns) (shutdown-agents))
