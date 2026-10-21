(ns clojurecraft.fortress2-kit
  (:require [clojurecraft.capture :as capture]
            [clojurecraft.fortress :as fortress]
            [clojurecraft.intent :as intent]
            [clojurecraft.recipe :as recipe]
            [clojurecraft.sim :as sim]
            [clojurecraft.simrace :as simrace]))

(defn swap-slot
  "Inventory inv with one stack of gravel, granite, andesite or dirt turned into an iron pickaxe."
  [inv]
  (let [ids (set (map recipe/item-id [:gravel :granite :andesite :dirt]))
        k (some (fn [[k v]] (when (contains? ids (:item v)) k)) inv)]
    (println "KITSLOT" k (get inv k))
    (assoc inv k {:item (recipe/item-id :iron_pickaxe) :count 1})))

(defn -main
  "Plays the snapshot at path for minutes with an iron pickaxe given, sampling every 60 s."
  [path minutes]
  (let [snap (capture/read-world path)
        w (simrace/world-for! snap)
        m (simrace/moment-of snap w)
        m (-> m
              (update-in [:world :player/inventory] swap-slot)
              (update-in [:sim :sim/inv] swap-slot))
        t0 (:t m)
        last-s (volatile! -1)
        stop? (fn [world]
                (let [s (quot (- (:time/now world) t0) 60000)]
                  (when (> s @last-s)
                    (vreset! last-s s)
                    (let [{:search/keys [heading leg tunnels]} (:fortress/search world)]
                      (println "KIT"
                               path
                               s
                               "feet" (intent/feet world)
                               "leg" leg
                               "heading" heading
                               "tunnels" tunnels
                               "bricks" (count (fortress/bricks world)))))
                  (boolean (some #(fortress/arrived? world %) (fortress/bricks world)))))]
    (sim/play-from simrace/step
                   m
                   stop?
                   (* (Long/parseLong minutes) 60000)
                   (simrace/go-of w (:snapshot/until snap))
                   nil)
    nil))
