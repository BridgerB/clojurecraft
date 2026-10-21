(ns probe.portal2-op
  "Probe (portal2): play a snapshot as simrace/judged does and print what the build's last live
   tick before the failure saw: the op, the feet, the stands approach-stand weighs."
  (:require [clojure.string :as str]
            [clojurecraft.build :as build]
            [clojurecraft.capture :as capture]
            [clojurecraft.intent :as intent]
            [clojurecraft.plan :as plan]
            [clojurecraft.sim :as sim]
            [clojurecraft.simrace :as sr]
            [clojurecraft.terrain :as terrain]))

(defn -main
  [& [path]]
  (let [snap (capture/read-world path)
        w (sr/world-for! snap)
        m (sr/moment-of snap w)
        stage (sr/stage-of snap)
        prev (atom nil)
        stop? (fn [x]
                (let [i (:plan/intent x)]
                  (when (and i (= :build (:intent/kind i)) (not (intent/failed? i)))
                    (reset! prev x)))
                (or (plan/done? x) (plan/failed? x) (contains? (:plan/reached x) stage)))
        _
        (sim/play-from sr/step m stop? (sr/window-of snap) (sr/go-of w (:snapshot/until snap)) nil)
        x @prev
        i (:plan/intent x)
        plan (build/plan-for (:intent/build i)
                             (:intent/origin i)
                             (:intent/facing i)
                             (:intent/access i 0))
        op (build/next-op x plan)
        feet (intent/feet x)]
    (println "PROBE intent" (pr-str (dissoc i :intent/child)))
    (println "PROBE child" (pr-str (:intent/child i)))
    (println "PROBE feet" feet "tick" (:time/tick x))
    (println "PROBE op" (pr-str op))
    (println "PROBE reachable-stand self"
             (pr-str (build/reachable-stand x (:build/pos op) (:build/pos op))))
    (println "PROBE reachable-stand stand"
             (pr-str (build/reachable-stand x (:build/stand op) (:build/pos op))))
    (println "PROBE nearest-stand-cell" (pr-str (build/nearest-stand-cell x i (:build/pos op))))
    (println "PROBE way-back" (pr-str (build/way-back x i)))
    (println "PROBE approach" (pr-str (build/approach-stand x i op)))
    (doseq [dy [-2 -1 0 1 2 3]]
      (println "PROBE layer dy" dy)
      (doseq [dz (range -6 7)]
        (println "PROBE"
                 (str/join (for [dx (range -8 9)]
                             (let [c (mapv + feet [dx dy dz])
                                   b (terrain/block-at x c)]
                               (cond (= c feet) \@
                                     (nil? b) \?
                                     (#{:air :cave_air} b) \.
                                     (= :water b) \~
                                     (= :lava b) \L
                                     (= :obsidian b) \O
                                     :else \#)))))))
    (println "PROBE op-cells"
             (pr-str (select-keys op [:build/pos :build/stand :build/step :build/cell])))
    (shutdown-agents)))
