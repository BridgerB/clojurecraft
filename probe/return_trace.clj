(ns probe.return-trace
  "Probe: replay a snapshot's run from its landing and print, after the first death, the intent
   chain's kinds and the tools and materials held, every 20 s."
  (:require [clojurecraft.capture :as capture]
            [clojurecraft.recipe :as recipe]
            [clojurecraft.simrace :as simrace]))

(def real-step simrace/step)

(defn chain
  "The kinds of an intent and its children, outermost first."
  [i]
  (when i
    (cons (select-keys i [:intent/kind :intent/for :intent/stage :intent/to-y :intent/target])
          (chain (:intent/child i)))))

(defn held
  [world]
  (-> (recipe/counts (:player/inventory world))
      (select-keys [:crafting_table :stick :cobblestone :stone_pickaxe :wooden_pickaxe :iron_pickaxe
                    :oak_planks :oak_log :birch_planks :spruce_planks])))

(defn tracer
  []
  (let [last-t (atom 0)
        deaths (atom 0)]
    (fn [world event]
      (let [w (real-step world event)
            d (:stats/deaths w 0)
            t (:time/now w 0)]
        (when (> d @deaths) (reset! deaths d))
        (when (and (pos? @deaths) (>= (- t @last-t) 20000))
          (reset! last-t t)
          (println "TICK"
                   t
                   (pr-str (mapv long (:player/pos w)))
                   (pr-str (held w))
                   (pr-str (vec (chain (:plan/intent w))))))
        w))))

(defn -main
  [& paths]
  (doseq [p paths]
    (println "TRACE" p)
    (let [snap (capture/read-world p)
          w (simrace/world-for! snap)]
      (with-redefs [simrace/step (tracer)]
        (let [r (simrace/replay snap w (* 60000 50))]
          (println "REPLAY" (pr-str (select-keys r [:run/signature :run/ms :run/respawns]))))))
    (flush))
  (shutdown-agents))
