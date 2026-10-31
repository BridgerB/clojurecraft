(ns clojurecraft.probe-step
  "Look inside a fortress tunnel's choice of step in a recorded run (issue #262, landing 5): fold the
   recording to a moment and, for every move toward a target, print which of the checks next-move
   makes it passes. Folded with the code that made the recording (a worktree at its commit).

     clojure -M:dev -m clojurecraft.probe-step run.edn <ms> [<tx> <ty> <tz>]

   ms is the recording's clock; the target is the running :tunnel-to's own unless given."
  (:require [clojurecraft.blocks :as blocks]
            [clojurecraft.fortress :as fortress]
            [clojurecraft.intent :as intent]
            [clojurecraft.probe :as probe]
            [clojurecraft.recipe :as recipe]
            [clojurecraft.stairs :as stairs]
            [clojurecraft.terrain :as terrain]))

(set! *warn-on-reflection* true)

(defn move-report
  "A map of what each check of next-move says of the step from feet by move."
  [w feet move]
  (let [floor (fortress/plus (fortress/plus feet move) [0 -1 0])
        at #(some-> (terrain/block-at w %)
                    blocks/name-of)]
    {:move move
     :to (fortress/plus feet move)
     :floor-cell floor
     :floor (at floor)
     :under (mapv #(at (fortress/plus floor [0 (- %) 0])) [1 2 3])
     :safe? (fortress/safe? w feet move)
     :lava-beside (vec (fortress/lava-beside w feet move))
     :dry? (fortress/dry? w feet move)
     :thin? (fortress/thin-floor? w floor)
     :bridge? (fortress/bridge? w feet move true)
     :ground-below? (fortress/ground-below? w floor)
     :ground-below-lava-ok? (fortress/ground-below? w floor true)
     :bridge-over-void? (fortress/bridge-over-void? w feet move)
     :walk-filler (fortress/walk-filler w floor)}))

(defn -main
  "Print the report for each move toward the target at the recording's clock ms."
  [path ms & [tx ty tz]]
  (let [w (probe/at-ms path (parse-long ms))
        feet (intent/feet w)
        i (:plan/intent w)
        target (if tx [(parse-long tx) (parse-long ty) (parse-long tz)] (:intent/target i))
        climb (:intent/climb i fortress/climb-past)]
    (prn :feet feet
         :target target
         :climb climb
         :intent (select-keys i [:intent/kind :intent/bridge? :intent/move :intent/retreats])
         :health (:player/health w)
         :filler (stairs/filler w 1)
         :filler16 (stairs/filler w 16)
         :held
         (into {}
               (comp (map val)
                     (filter :item)
                     (map (fn [{:keys [item count]}] [(recipe/item-name item) count])))
               (:player/inventory w))
         :next-move
         (fortress/next-move w feet target climb false #{} (boolean (:intent/bridge? i))))
    (doseq [m (fortress/moves feet target climb)] (prn (move-report w feet m))))
  (shutdown-agents))
