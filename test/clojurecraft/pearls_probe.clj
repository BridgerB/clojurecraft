(ns clojurecraft.pearls-probe
  "A probe, run on a runner: the pearl hunt's choices at a minute of a recording (issue #263).
   clojure -M:dev -m clojurecraft.pearls-probe run.edn.zst 30 60 120 prints, for the first tick at
   or after each minute from the first tick, where the bot stands and what each branch of the
   hunt returns there."
  (:require [clojure.math :as math]
            [clojurecraft.entity :as entity]
            [clojurecraft.intent :as intent]
            [clojurecraft.main]
            [clojurecraft.memory :as memory]
            [clojurecraft.pearls :as pearls]
            [clojurecraft.physics :as physics]
            [clojurecraft.plan :as plan]
            [clojurecraft.real :as real]
            [clojurecraft.record :as record]))

(defn report
  "Prints what the hunt sees in world w: feet, burrow, staleness and the value of each act."
  [w minute]
  (let [far (pearls/endermen w pearls/seek-radius)
        near (pearls/endermen w pearls/descend-radius)
        row (first (filter #(= :ender-pearls (:goal/id %)) plan/goals))]
    (println "---" minute
             "min"
             {:feet (intent/feet w)
              :shelter (:pearls/shelter w)
              :mouth (:pearls/mouth w)
              :home (pearls/shelter w)
              :stale? (pearls/stale? w)
              :stuck? (pearls/stuck? w)
              :last (:intent/kind (:plan/last w))
              :boxed-at (:plan/boxed-at w)
              :blacklist (count (:plan/blacklist w))
              :health (:player/health w)
              :pickaxe? (pearls/pickaxe? w)
              :spare? (pearls/spare? w)
              :far (into {}
                         (map (fn [[id e]] [id
                                            [(mapv #(math/round (double %)) (:entity/pos e))
                                             (math/round (physics/distance (:player/pos w)
                                                                           (:entity/pos e)))
                                             (when (entity/creepy? e) :angry)]]))
                         far)
              :within-descend (count near)
              :arrived (count (memory/arrived w))})
    (println "  next-intent" (plan/next-intent w row plan/goals))
    (println "  roam" (pearls/roam w))
    (println "  climb-out" (pearls/climb-out w))
    (println "  dig-out" (pearls/dig-out w))
    (println "  descend" (pearls/descend w near))
    (println "  ascend" (pearls/ascend w near))
    (println "  toward" (pearls/toward w far))
    (println "  bore" (pearls/bore w near))))

(defn -main
  "Folds the recording at path and reports at each minute given."
  [path & minutes]
  (let [wanted (sort (map #(Long/parseLong %) minutes))
        state (atom {:todo wanted :t0 nil})]
    (record/reduce-entries (fn [acc line]
                             (let [acc (real/fold-step acc line)
                                   now (:time/now (:world acc))]
                               (when (and (nil? (:t0 @state)) (= :tick (:event/kind line)))
                                 (swap! state assoc :t0 now))
                               (let [{:keys [todo t0]} @state]
                                 (when (and t0 (seq todo) (>= (- now t0) (* 60000 (first todo))))
                                   (report (:world acc) (first todo))
                                   (swap! state update :todo rest)))
                               (if (empty? (:todo @state)) (reduced acc) acc)))
                           (real/start)
                           path)
    (shutdown-agents)))
