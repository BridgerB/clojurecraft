(ns probe.portal2-pillar
  (:require [clojurecraft.capture :as capture]
            [clojurecraft.plan :as plan]
            [clojurecraft.sim :as sim]
            [clojurecraft.simrace :as sr]
            [clojurecraft.terrain :as terrain]))

(def prev (atom nil))
(def seen (atom #{}))

(defn show
  [world]
  (let [i (:plan/intent world)
        c (:intent/child i)]
    (when (and (:intent/no-shaft i) (not (contains? @seen (:intent/id i))) @prev)
      (swap! seen conj (:intent/id i))
      (let [{pc :child pw :world} @prev]
        (println "NOSHAFT"
                 (pr-str {:t (:time/now world)
                          :id (:intent/id i)
                          :why (:intent/no-shaft i)
                          :prev-child pc
                          :prev-pos (:player/pos pw)
                          :prev-on-ground (:player/on-ground? pw)
                          :last-ack (:stats/last-ack pw)
                          :now-pos (:player/pos world)
                          :under-block (some->> (:intent/under pc)
                                                (terrain/block-at pw))
                          :held (get-in pw [:player/inventory (:player/held-slot pw)])}))))
    (reset! prev (when (map? c) {:child c :world world}))))

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
        r (sim/play-from step m stop? 900000 (sr/go-of w (:snapshot/until snap)) nil)]
    (println "END" (:time/now (:world r)))
    (shutdown-agents)))

(apply -main *command-line-args*)
