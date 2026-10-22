(ns probe.swim-trace
  "Probe: the intents of a judged snapshot (id changes), per-tick position while a walk is active
   after a cross, and the blocks round the feet at the end."
  (:require [clojure.string :as str]
            [clojurecraft.blocks :as blocks]
            [clojurecraft.capture :as capture]
            [clojurecraft.intent :as intent]
            [clojurecraft.plan :as plan]
            [clojurecraft.sim :as sim]
            [clojurecraft.simrace :as sr]
            [clojurecraft.terrain :as terrain]))

(defn grid
  [wd [fx fy fz]]
  (doseq [y (range (+ fy 3) (- fy 4) -1)]
    (println "GRID y" y)
    (doseq [z (range (- fz 4) (+ fz 5))]
      (println "GRID"
               (str/join (for [x (range (- fx 5) (+ fx 6))]
                           (let [b (terrain/block-at wd [x y z])
                                 k (terrain/fluid-kind wd [x y z])]
                             (cond (and (= x fx) (= y fy) (= z fz)) \@
                                   (= k :water) \~
                                   (nil? b) \?
                                   (blocks/solid? b) \#
                                   :else \.))))))))

(defn trace!
  [path]
  (let [snap (capture/read-world path)
        w (sr/world-for! snap)
        {:keys [world] :as m} (sr/moment-of snap w)
        stage (sr/stage-of snap)
        seen (atom nil)
        n (atom 0)
        n3 (atom 0)
        last-wd (atom nil)
        stop? (fn [wd]
                (reset! last-wd wd)
                (let [i (:plan/intent wd)
                      ch (:intent/child i)
                      id (when i [(:intent/id i) (:intent/id ch) (:intent/status ch)])]
                  (when (and id (not= id @seen) (< @n 120))
                    (reset! seen id)
                    (swap! n inc)
                    (println "TRACE"
                             (:time/now wd)
                             (pr-str (select-keys i
                                                  [:intent/id :intent/kind :intent/for
                                                   :intent/target :intent/reason]))
                             "child"
                             (pr-str (select-keys ch
                                                  [:intent/kind :intent/for :intent/status
                                                   :intent/reason]))
                             "feet" (pr-str (intent/feet wd))
                             "pos" (pr-str (:player/pos wd))
                             "trap" (:plan/trap wd)))
                  (when (and (= :walk (:intent/kind i))
                             (> (:time/now wd) 5181450)
                             (< @n3 80)
                             (zero? (mod (:time/tick wd 0) 2)))
                    (swap! n3 inc)
                    (println "TICK"
                             (:time/now wd)
                             (pr-str (:player/pos wd))
                             "vel" (pr-str (:player/vel wd))
                             "ground" (:player/on-ground? wd)
                             "water" (:player/in-water? wd)
                             "eye" (:player/eye-in-water? wd)
                             "ctl" (pr-str (:player/controls wd))))
                  (or (plan/done? wd) (plan/failed? wd) (contains? (:plan/reached wd) stage))))]
    (sim/play-from sr/step m stop? (sr/window-of snap) (sr/go-of w (:snapshot/until snap)) nil)
    (when-let [wd @last-wd]
      (println "END" (pr-str (:player/pos wd)))
      (grid wd (intent/feet wd)))))

(doseq [p (str/split (System/getenv "SNAPS") #",")] (trace! p))
