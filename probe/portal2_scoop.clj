(ns probe.portal2-scoop
  (:require [clojurecraft.blocks :as blocks]
            [clojurecraft.capture :as capture]
            [clojurecraft.game :as game]
            [clojurecraft.physics :as physics]
            [clojurecraft.plan :as plan]
            [clojurecraft.scoop :as scoop]
            [clojurecraft.sim :as sim]
            [clojurecraft.simrace :as sr]
            [clojurecraft.terrain :as terrain]))

(def seen (atom nil))

(defn show
  [world]
  (let [c (:intent/child (:plan/intent world))]
    (when (and (map? c)
               (= :scoop (:intent/kind c))
               (= :sent (:intent/stage c))
               (not= @seen (:intent/sequence c)))
      (reset! seen (:intent/sequence c))
      (let [eye (game/eye world)
            aim (:intent/aim c)
            look (physics/look-at eye aim)
            cells (physics/cells-along eye (physics/direction look) 4.5 0.05)]
        (println "SCOOPSENT"
                 (pr-str {:t (:time/now world)
                          :tries (:intent/tries c)
                          :eye eye
                          :target (:intent/target c)
                          :aim aim
                          :look look
                          :target-id (terrain/block-at world (:intent/target c))
                          :source? (scoop/source? world (:intent/target c) :water)
                          :cells (mapv (fn [x] [x (terrain/block-at world x)
                                                (some-> (terrain/block-at world x)
                                                        blocks/name-of)])
                                       (take 14 cells))}))))))

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
        r (sim/play-from step m stop? 400000 (sr/go-of w (:snapshot/until snap)) nil)]
    (println "END" (:time/now (:world r)))
    (shutdown-agents)))

(apply -main *command-line-args*)
