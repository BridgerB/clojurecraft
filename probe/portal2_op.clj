(ns probe.portal2-op
  "Probe (portal2): play a snapshot as simrace/judged does and print what the build's last live
   tick before the failure saw: the op, the feet, the stands approach-stand weighs."
  (:require [clojure.string :as str]
            [clojurecraft.blocks :as blocks]
            [clojurecraft.build :as build]
            [clojurecraft.capture :as capture]
            [clojurecraft.intent :as intent]
            [clojurecraft.plan :as plan]
            [clojurecraft.sim :as sim]
            [clojurecraft.simrace :as sr]
            [clojurecraft.terrain :as terrain]))

(defn glyph
  "One character for the block at c."
  [x c feet]
  (let [id (terrain/block-at x c)
        n (some-> id
                  blocks/name-of)]
    (cond (= c feet) \@
          (nil? id) \?
          (#{:air :cave_air} n) \.
          (= :water n) (if (zero? (:fluid/level (terrain/fluid-at x c) 1)) \W \w)
          (= :lava n) \L
          (= :obsidian n) \O
          (= :gravel n) \g
          (= :sand n) \s
          :else \#)))

(defn -main
  [& [path]]
  (let [snap (capture/read-world path)
        w (sr/world-for! snap)
        m (sr/moment-of snap w)
        stage (sr/stage-of snap)
        prev (atom nil)
        trace (atom [])
        stop? (fn [x]
                (let [i (:plan/intent x)]
                  (when (and i (= :build (:intent/kind i)) (not (intent/failed? i)))
                    (reset! prev x)
                    (let [k [(:intent/op i) (:intent/kind (:intent/child i))]]
                      (when (not= k (:k (peek @trace)))
                        (swap! trace conj
                          {:k k
                           :tick (:time/tick x)
                           :feet (intent/feet x)
                           :op (select-keys (:intent/op i)
                                            [:build/op :build/pos :build/stand :build/step])
                           :child (select-keys (:intent/child i)
                                               [:intent/kind :intent/target :intent/status
                                                :intent/reason])})))))
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
    (doseq [t (take-last 40 @trace)] (println "PROBE trace" (pr-str (dissoc t :k))))
    (println "PROBE intent" (pr-str (dissoc i :intent/child)))
    (println "PROBE child" (pr-str (:intent/child i)))
    (println "PROBE feet" feet "tick" (:time/tick x))
    (println "PROBE op" (pr-str op))
    (println "PROBE plan-before"
             (pr-str (for [o (take-while #(not= % op) plan)]
                       [(:build/step o) (:build/op o) (:build/pos o) (build/satisfied? x o)])))
    (println "PROBE reachable-stand self"
             (pr-str (build/reachable-stand x (:build/pos op) (:build/pos op))))
    (println "PROBE reachable-stand stand"
             (pr-str (build/reachable-stand x (:build/stand op) (:build/pos op))))
    (println "PROBE nearest-stand-cell" (pr-str (build/nearest-stand-cell x i (:build/pos op))))
    (println "PROBE way-back" (pr-str (build/way-back x i)))
    (println "PROBE approach" (pr-str (build/approach-stand x i op)))
    (println "PROBE block at pos/stand"
             (pr-str [(some-> (terrain/block-at x (:build/pos op))
                              blocks/name-of)
                      (some-> (terrain/block-at x (:build/stand op))
                              blocks/name-of)]))
    (doseq [dy [-3 -2 -1 0 1 2 3]]
      (println "PROBE layer dy" dy "y" (+ (second feet) dy))
      (doseq [dz (range -7 8)]
        (println "PROBE"
                 (str/join (for [dx (range -8 9)] (glyph x (mapv + feet [dx dy dz]) feet))))))
    (shutdown-agents)))
