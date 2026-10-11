(ns clojurecraft.stone
  "Getting cobblestone: with a pickaxe in hand, walk to the nearest remembered stone the bot can
   stand beside, dig it, pick the drop up; with no stone in sight, cut a staircase down to
   where stone begins. The :mine act of the goal table (clojurecraft.make) runs this, the way
   :gather runs clojurecraft.wood. Requiring this namespace registers the act."
  (:require [clojure.math :as math]
            [clojurecraft.blocks :as blocks]
            [clojurecraft.chunk :as chunk]
            [clojurecraft.dig :as dig]
            [clojurecraft.game :as game]
            [clojurecraft.intent :as intent]
            [clojurecraft.memory :as memory]
            [clojurecraft.path :as path]
            [clojurecraft.plan :as plan]
            [clojurecraft.stairs]
            [clojurecraft.terrain :as terrain]))

(set! *warn-on-reflection* true)

(def search-radius 24)                ; blocks from the eye searched for exposed stone
(def search-height 12)                ; blocks above or below the eye searched
(def stair-depth 6)                   ; stairs cut when no stone is in sight, before looking again
(def cobblestone #{(blocks/items :cobblestone)}) ; what stone dug with a pickaxe drops: a collect's :intent/drops
(def stone-states
  "Every state of the blocks that drop cobblestone: stone."
  (blocks/states-where (fn [[n]] (= n :stone))))

(defn pickaxe
  "[slot item-id] of the pickaxe the bot can dig stone with, or nil: stone needs no tier, but
   without a pickaxe the dig takes 2300 ms and a race cannot afford the hand."
  [world]
  (dig/best-tool (first stone-states) (:player/inventory world) {}))

(defn beside
  "A cell a route could stand in beside stone at pos, at its level (one of its horizontal
   neighbours), or nil: standing there, the stone is dug from the side and its drop comes to
   rest at the feet, inside the pickup box. Dug from the surface above, the drop lies a block
   under the feet and is never picked up."
  [world [x y z]]
  (first (filter #(path/standable? world %)
                 (for [[dx dz] [[1 0] [-1 0] [0 1] [0 -1]]] [(+ x dx) y (+ z dz)]))))

(defn exposed?
  "Can a player stand beside stone at pos and dig it?"
  [world pos]
  (some? (beside world pos)))

(defn near-chunks
  "The loaded chunk columns within search-radius of the feet: the terrain a stone search reads."
  [world]
  (let [[x _ z] (:player/pos world)
        cx (bit-shift-right (long (math/floor x)) 4)
        cz (bit-shift-right (long (math/floor z)) 4)
        r (inc (quot search-radius 16))]
    (into {}
          (filter (fn [[[kx kz] _]] (and (<= (abs (- kx cx)) r) (<= (abs (- kz cz)) r)))
                  (:world/chunks world)))))

(defn nearest-stone
  "The nearest exposed stone within search-radius of the eye and search-height of its height
   that the bot can stand beside, not blacklisted; nil when none. Read from the loaded terrain
   (the live view), not memory: stone is most of the world, and only what is exposed now is
   worth a route. The chunks are scanned as they arrived, so each candidate is asked again
   through the overlay of block updates and the bot's own breaks: a stone just dug is air."
  [world]
  (let [eye (game/eye world)
        ey (second eye)
        blacklist (:plan/blacklist world #{})
        lo (- ey search-height)
        hi (+ ey search-height)]
    (memory/nearest-of eye
                       search-radius
                       (->> (chunk/find-blocks (near-chunks world) #(contains? stone-states %))
                            (map (fn [[x y z _]] [x y z]))
                            (filter (fn [[_ y _]] (<= lo y hi)))
                            (remove blacklist)
                            (filter #(contains? stone-states (terrain/block-at world %)))
                            (filter #(exposed? world %))))))

(defn continue-mine
  "The next intent of a stone chain in flight, or nil: walk → dig → collect, tagged
   :intent/for :stone so a walk for another purpose is never continued as a dig."
  [world]
  (intent/chain-next (:plan/last world) :stone cobblestone {}))

(defn mine-next
  "The next step of getting cobblestone: continue the chain; else, with a pickaxe, walk to the
   nearest exposed stone or cut stairs down toward stone; with no pickaxe, wait :no-pickaxe."
  [world]
  (or (continue-mine world)
      (cond (nil? (pickaxe world)) {:plan/wait :no-pickaxe}
            :else
            (if-let [stone (nearest-stone world)]
              {:intent/kind :walk :intent/target stone :intent/for :stone}
              {:intent/kind :stairs-down
               :intent/to-y (- (long (math/floor (second (:player/pos world)))) stair-depth)}))))

(defmethod plan/act :mine [world _] (mine-next world))
