(ns probe.blaze4-trace
  (:require [clojure.string :as str]
            [clojurecraft.blocks :as blocks]
            [clojurecraft.capture :as capture]
            [clojurecraft.sim :as sim]
            [clojurecraft.simrace :as sr]
            [clojurecraft.terrain :as terrain]))

(defn ch
  [id]
  (cond (nil? id) \?
        (zero? id) \.
        (contains? blocks/lava-states id) \L
        (= :netherrack (blocks/name-of id)) \a
        (contains? #{:air :cave_air :void_air} (blocks/name-of id)) \.
        (blocks/solid? id) \#
        :else \o))

(defn dump
  [w [cx cz] r ys]
  (doseq [y ys]
    (println "-- y" y "x" (- cx r) ".." (+ cx r) "z" (- cz r) ".." (+ cz r))
    (doseq [z (range (- cz r) (inc (+ cz r)))]
      (println (format "%4d " z)
               (str/join (for [x (range (- cx r) (inc (+ cx r)))]
                           (if (and (= x cx) (= z cz)) \@ (ch (terrain/block-at w [x y z])))))))))

(defn run
  [path centre r ys]
  (let [snap (capture/read-world path)
        w (sr/world-for! snap)
        m (sr/moment-of snap w)
        done (atom false)
        stop? (fn [world]
                (when-not @done (reset! done true) (println "==" path) (dump world centre r ys))
                true)]
    (sim/play-from sr/step m stop? (sr/window-of snap) (sr/go-of w nil) nil)))

(run "test/issues/502/b10221245-R3-5-2.edn.gz" [135 297] 24 [46 48 50 52 54])
(println "done")
