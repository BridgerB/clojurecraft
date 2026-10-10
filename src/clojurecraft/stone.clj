(ns clojurecraft.stone
  "Getting cobblestone: with a pickaxe in hand, walk to the nearest remembered stone the bot can
   stand beside, dig it, pick the drop up; with no stone in sight, cut a staircase down to
   where stone begins. The :mine act of the goal table (clojurecraft.make) runs this, the way
   :gather runs clojurecraft.wood. Requiring this namespace registers the act."
  (:require [clojurecraft.blocks :as blocks]
            [clojurecraft.dig :as dig]
            [clojurecraft.game :as game]
            [clojurecraft.memory :as memory]
            [clojurecraft.path :as path]
            [clojurecraft.plan :as plan]
            [clojurecraft.stairs]
            [clojurecraft.terrain :as terrain]))

(def search-radius 24)                ; blocks from the eye searched for remembered stone
(def stair-depth 6)                   ; stairs cut when no stone is in sight, before looking again
(def stone-states "Every state of the blocks that drop cobblestone: stone." (blocks/states-where (fn [[n]] (= n :stone))))

(defn pickaxe
  "[slot item-id] of the pickaxe the bot can dig stone with, or nil: stone needs no tier, but
   without a pickaxe the dig takes 2300 ms and a race cannot afford the hand."
  [world]
  (dig/best-tool (first stone-states) (:player/inventory world) {}))

(defn exposed?
  "Can a player stand beside stone at pos and dig it: one of its horizontal neighbours is a
   cell a route could stand in."
  [world [x y z]]
  (boolean (some #(path/standable? world %) (for [[dx dz] [[1 0] [-1 0] [0 1] [0 -1]]] [(+ x dx) y (+ z dz)]))))

(defn nearest-stone
  "The nearest remembered stone within search-radius of the eye the bot can stand beside, not
   blacklisted, within 12 blocks of the eye's height; nil when none."
  [world]
  (let [eye (game/eye world)
        ey (second eye)
        blacklist (:plan/blacklist world #{})]
    (memory/nearest-of eye search-radius
                       (->> (memory/positions-now world stone-states)
                            (remove blacklist)
                            (filter (fn [[_ y _]] (<= (abs (- y ey)) 12)))
                            (filter #(exposed? world %))))))

(defn continue-mine
  "The next intent of a stone chain in flight, or nil: walk → dig → collect, tagged
   :intent/for :stone so a walk for another purpose is never continued as a dig."
  [world]
  (let [{:intent/keys [kind target] :as last} (:plan/last world)]
    (when (= :stone (:intent/for last))
      (case kind
        :walk {:intent/kind :dig :intent/target target :intent/for :stone}
        :dig {:intent/kind :collect :intent/target target :intent/for :stone}
        nil))))

(defn mine-next
  "The next step of getting cobblestone: continue the chain; else, with a pickaxe, walk to the
   nearest exposed stone or cut stairs down toward stone; with no pickaxe, wait :no-pickaxe."
  [world]
  (or (continue-mine world)
      (cond
        (nil? (pickaxe world)) {:plan/wait :no-pickaxe}
        :else (if-let [stone (nearest-stone world)]
                {:intent/kind :walk :intent/target stone :intent/for :stone}
                {:intent/kind :stairs-down :intent/to-y (- (long (Math/floor (second (:player/pos world)))) stair-depth)}))))

(defmethod plan/act :mine [world _] (mine-next world))
