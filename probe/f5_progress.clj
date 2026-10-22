(ns probe.f5-progress
  "Probe (issue #338/#286, fortress5): a snapshot played on from its moment, sampled every
   sample-ms of simulated time: feet, horizontal distance to the nearest fortress block of the
   captured Nether, health, food, pickaxes held, the intent."
  (:require [clojure.math :as math]
            [clojurecraft.blocks :as blocks]
            [clojurecraft.capture :as capture]
            [clojurecraft.capture-nether :as cn]
            [clojurecraft.fortress :as fortress]
            [clojurecraft.intent :as intent]
            [clojurecraft.plan :as plan]
            [clojurecraft.sim :as sim]
            [clojurecraft.simrace :as simrace]))

(def sample-ms 30000)

(defn pickaxes
  "Pickaxe stacks held."
  [world]
  (count (filter (fn [[_ {:keys [item]}]] (= :pickaxe (:kind (blocks/tool item))))
                 (:player/inventory world))))

(defn run
  "Play path's snapshot over its window, printing one SAMPLE line per sample-ms."
  [path]
  (let [snap (capture/read-world path)
        w (simrace/world-for! snap)
        bricks (cn/bricks (:decoded (:capture/nether w)))
        {:keys [world] :as m} (simrace/moment-of snap w)
        last-t (volatile! -1000000)
        sample! (fn [world]
                  (let [t (:time/now world)]
                    (when (>= (- t @last-t) sample-ms)
                      (vreset! last-t t)
                      (let [[x y z :as feet] (intent/feet world)
                            d (when (seq bricks) (reduce min (map #(cn/across [x z] %) bricks)))
                            i (:plan/intent world)]
                        (println "SAMPLE"
                                 path
                                 (pr-str {:t (- t (:time/now (:world m)))
                                          :feet feet
                                          :across (some-> d
                                                          math/round)
                                          :health (:player/health world)
                                          :food (:player/food world)
                                          :pick (pickaxes world)
                                          :kind (:intent/kind i)
                                          :taken (:intent/taken i)
                                          :target (:intent/target i)
                                          :op (:intent/op i)
                                          :move (:intent/move i)
                                          :retreats (:intent/retreats i)
                                          :status (:intent/status i)
                                          :on-ground (:player/on-ground? world)})))))
                  false)
        stop? #(or (sample! %) (plan/done? %) (plan/failed? %))
        r (sim/play-from simrace/step
                         m
                         stop?
                         (simrace/window-of snap)
                         (simrace/go-of w (:snapshot/until snap))
                         nil)]
    (println "BRICKS"
             path
             (count bricks)
             "nearest-at-start"
             (let [[x _ z] (intent/feet world)]
               (when (seq bricks) (reduce min (map #(cn/across [x z] %) bricks)))))
    (println "END"
             path
             (pr-str {:status (:plan/status (:world r)) :reason (:plan/reason (:world r))}))))

(defn -main "Sample each snapshot path." [& paths] (run! run paths) (shutdown-agents))
