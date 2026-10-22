(ns probe.swim-dbg
  "Probe: why bank-block finds nothing in the bank-world of swim-test."
  (:require [clojurecraft.blocks :as blocks]
            [clojurecraft.fixtures :as fx]
            [clojurecraft.game :as game]
            [clojurecraft.intent :as intent]
            [clojurecraft.path :as path]
            [clojurecraft.physics :as physics]
            [clojurecraft.place :as place]
            [clojurecraft.stairs :as stairs]
            [clojurecraft.swim :as swim]
            [clojurecraft.terrain :as terrain]
            [clojurecraft.world :as world]))

(let [sea (into {} (for [x (range 3 16) z (range 16) y (range 56 63)] [[x y z] blocks/water-base]))
      bank
      (into
       {}
       (for [x (range 3) z (range 16) y (range 56 64)] [[x y z] (blocks/default-state :stone)]))
      cliff
      (into
       {}
       (for [x (range 2) z (range 16) y (range 64 72)] [[x y z] (blocks/default-state :stone)]))
      w (assoc (game/init fx/opts)
               :bot/phase :play
               :player/pos [3.3 63.2 8.5]
               :player/loaded? true
               :player/on-ground? false
               :player/in-water? false
               :player/eye-in-water? false
               :player/inventory {0 {:item (blocks/items :cobblestone) :count 20}}
               :time/tick 200
               :world/chunks {[0 0] (world/column (merge sea bank cliff))})
      feet (intent/feet w)
      surface (swim/surface-y w :water feet)
      top (swim/float-top w feet surface)]
  (println "DBG feet" feet "surface" surface "top" top "filler" (stairs/filler w 1))
  (println "DBG under-sky" (terrain/under-sky? w feet))
  (doseq [x [3 4]]
    (let [cell [x top 8]
          bank [x (inc top) 8]]
      (println "DBG" cell
               "fluid" (terrain/fluid-kind w cell)
               "clear bank" (path/clear? w bank)
               "clear top2" (path/clear? w [x (+ top 2) 8])
               "open" (swim/in-the-open? w bank)
               "inside" (place/inside-player? w cell)
               "support" (place/support w cell)
               "dist" (physics/distance (game/eye w) (physics/centre cell)))))
  (println "DBG bank-block" (swim/bank-block w feet surface)
           "obstacle" (swim/obstacle w feet [2 64 8])))
