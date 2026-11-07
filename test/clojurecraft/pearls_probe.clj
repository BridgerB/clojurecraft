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
            [clojurecraft.pearls.move :as pmove]
            [clojurecraft.pearls.params :as pparams]
            [clojurecraft.pearls.place :as pplace]
            [clojurecraft.pearls.sense :as psense]
            [clojurecraft.pearls.upkeep :as pupkeep]
            [clojurecraft.physics :as physics]
            [clojurecraft.plan :as plan]
            [clojurecraft.real :as real]
            [clojurecraft.record :as record]))

(defn report
  "Prints what the hunt sees in world w: feet, burrow, staleness and the value of each act."
  [w minute]
  (let [far (psense/endermen w pparams/seek-radius)
        near (psense/endermen w pparams/descend-radius)
        row (first (filter #(= :ender-pearls (:goal/id %)) plan/goals))]
    (println "---" minute
             "min"
             {:feet (intent/feet w)
              :shelter (:pearls/shelter w)
              :mouth (:pearls/mouth w)
              :home (pplace/shelter w)
              :stale? (psense/stale? w)
              :stuck? (pmove/stuck? w)
              :last (:intent/kind (:plan/last w))
              :in? (= (pplace/shelter w) (intent/feet w))
              :threat (psense/threat w)
              :angry-near (psense/angry-near w)
              :lure? (psense/lure? w)
              :at-hand (psense/at-hand w)
              :quarry (first (psense/quarry w))
              :boxed-at (:plan/boxed-at w)
              :blacklist (count (:plan/blacklist w))
              :health (:player/health w)
              :pickaxe? (pupkeep/pickaxe? w)
              :spare? (pupkeep/spare? w)
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
    (println "  roam" (pmove/roam w))
    (println "  climb-out" (pmove/climb-out w))
    (println "  dig-out" (pmove/dig-out w))
    (println "  descend" (pmove/descend w near))
    (println "  ascend" (pmove/ascend w near))
    (println "  toward" (pmove/toward w far))
    (println "  bore" (pmove/bore w near))))

(defn -main
  "Folds the recording at path and reports at each minute given."
  [path & minutes]
  (let [wanted (sort (map #(Double/parseDouble %) minutes))
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
