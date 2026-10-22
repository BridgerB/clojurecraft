(ns probe.portal2-pillar
  (:require [clojurecraft.capture :as capture]
            [clojurecraft.intent :as intent]
            [clojurecraft.plan :as plan]
            [clojurecraft.sim :as sim]
            [clojurecraft.simrace :as sr]
            [clojurecraft.terrain :as terrain]))

(def seen (atom #{}))

(defn show
  [world]
  (let [c (:intent/child (:plan/intent world))]
    (when (and (map? c)
               (= :pillar (:intent/kind c))
               (intent/failed? c)
               (not (contains? @seen (:time/now world))))
      (swap! seen conj (:time/now world))
      (println "PILLARFAIL"
               (pr-str {:t (:time/now world)
                        :pos (:player/pos world)
                        :on-ground (:player/on-ground? world)
                        :last-ack (:stats/last-ack world)
                        :child (select-keys c
                                            [:intent/reason :intent/under :intent/sent-at
                                             :intent/sequence :intent/item :intent/top
                                             :intent/refusals :intent/since])
                        :under-block (some->> (:intent/under c)
                                              (terrain/block-at world))
                        :held (get-in world [:player/inventory (:player/held-slot world)])})))))

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
        r (sim/play-from step m stop? 480000 (sr/go-of w (:snapshot/until snap)) nil)]
    (println "END" (:time/now (:world r)))
    (shutdown-agents)))

(apply -main *command-line-args*)
