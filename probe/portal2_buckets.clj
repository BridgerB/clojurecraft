(ns probe.portal2-buckets
  (:require [clojurecraft.capture :as capture]
            [clojurecraft.inventory :as inventory]
            [clojurecraft.plan :as plan]
            [clojurecraft.sim :as sim]
            [clojurecraft.simrace :as sr]))

(def prev (atom nil))

(defn counts [world] (mapv #(inventory/item-count world %) [:bucket :water_bucket :lava_bucket]))

(defn show
  [world]
  (let [c (counts world)
        i (:plan/intent world)
        child (:intent/child i)]
    (when (not= c @prev)
      (reset! prev c)
      (println "BUCKETS"
               (pr-str {:t (:time/now world)
                        :counts c
                        :pos (:player/pos world)
                        :intent (select-keys i [:intent/kind :intent/op])
                        :op (select-keys (:intent/op i)
                                         [:build/op :build/step :build/item :build/cell])
                        :child (select-keys child
                                            [:intent/kind :intent/target :intent/item :intent/fluid
                                             :intent/status :intent/reason])})))))

(defn -main
  [& [path]]
  (let [snap (capture/read-world path)
        w (sr/world-for! snap)
        {:keys [world] :as m} (sr/moment-of snap w)
        stop? #(or (plan/done? %) (plan/failed? %))
        step (fn [world ev]
               (let [w (sr/step world ev)]
                 (show w)
                 w))
        r (sim/play-from step m stop? 420000 (sr/go-of w (:snapshot/until snap)) nil)]
    (println "END" (:time/now (:world r)))
    (shutdown-agents)))

(apply -main *command-line-args*)
