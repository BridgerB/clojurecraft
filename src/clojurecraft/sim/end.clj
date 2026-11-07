(ns clojurecraft.sim.end
  "The End in the model: a thrown eye of ender, the ring of frames that lights the End portal, the arrival on the main island and the dragon fight's start."
  (:refer-clojure :exclude [send])
  (:require [clojurecraft.blocks :as blocks]
            [clojurecraft.dimension :as dimension]
            [clojurecraft.end :as end]
            [clojurecraft.inventory :as inventory]
            [clojurecraft.mob :as mob]
            [clojurecraft.physics :as physics]
            [clojurecraft.recipe :as recipe]
            [clojurecraft.sim.mobs :as sim-mobs :refer [add-mob free-fn mob-roll tell-move]]
            [clojurecraft.sim.protocol :as sim-protocol :refer
             [leave-overworld respawn spawn-item stream use-held]]
            [clojurecraft.sim.window :as sim-window :refer [full sync-window take-one]]
            [clojurecraft.sim.world :as sim-world :refer [block-at held-slot send]]))

(set! *warn-on-reflection* true)

(def eye-type (get blocks/entity-types :eye_of_ender)) ; add-entity's type for a thrown eye

(def eye-body 0.9)                    ; an eye leaves from the middle of the thrower's body (EnderEyeItem: getY(0.5))

(def end-respawn
  "The respawn the model sends for the End: the dimension change an end portal makes."
  (assoc respawn
         :dimension-type (:dimension/id (dimension/by-name dimension/end))
         :dimension dimension/end))

(defmethod use-held :eye
  ;; an eye thrown toward the stronghold (:sim/stronghold): one taken, an eye of ender entity at
  ;; the body's middle flying for end/eye-target (tick-eyes); with no stronghold the eye stays in
  ;; hand, as the game throws none in a world without one
  [sim _]
  (let [[x y z :as player] (:sim/player-pos sim)
        stronghold (:sim/stronghold sim)]
    (cond (not (and player stronghold)) sim
          :else
          (let [eid (:sim/next-eid sim)
                from [x (+ y eye-body) z]
                [sim r] (mob-roll sim)
                before (full (:sim/inv sim) (inventory/layouts :inventory))]
            (-> sim
                (assoc :sim/next-eid (inc eid))
                (update :sim/inv take-one (held-slot sim))
                (sync-window :inventory before (:sim/cursor sim) false)
                (assoc-in [:sim/eyes eid]
                          {:from from
                           :pos from
                           :target (end/eye-target from stronghold)
                           :at (:sim/now sim)
                           :survives? (< r end/eye-survives)})
                (send {:packet/name :add-entity
                       :entity-id eid
                       :uuid nil
                       :type eye-type
                       :x (first from)
                       :y (second from)
                       :z (last from)}))))))

(defn tick-eyes
  "One game tick of every thrown eye: along its flight (end/eye-pos), each move told (tell-move);
   at the end of its life it is gone, and one that survives (end/eye-survives, rolled at the
   throw) drops as an eye item where it falls to."
  [sim]
  (reduce (fn [sim [eid {:keys [from pos target at survives?]}]]
            (let [ticks (quot (- (:sim/now sim) at) physics/tick-ms)
                  to (end/eye-pos from target ticks)]
              (cond (< ticks end/eye-life-ticks)
                    (-> sim
                        (tell-move eid pos to)
                        (assoc-in [:sim/eyes eid :pos] to))
                    :else
                    (-> sim
                        (update :sim/eyes dissoc eid)
                        (send {:packet/name :remove-entities :ids [eid]})
                        (cond-> survives?
                                (spawn-item (mob/fall (free-fn sim) pos)
                                            {:item (recipe/item-id :ender_eye) :count 1}))))))
          sim
          (sort-by key (:sim/eyes sim))))

(defn arrive-end
  "The model in the End: the overworld's columns, blocks, items, eyes and mobs gone (the client
   drops them at the respawn), the End's columns (:sim/end's, none for a void), the player on
   the obsidian platform (end/platform, told block by block after the chunks), the dragon
   perched at :sim/end's :dragon (the fountain's top when absent) and a crystal on every spike
   (:sim/end's :crystals, end/spikes' when absent)."
  [sim]
  (let [{:keys [columns dragon dragon-yaw crystals]} (:sim/end sim)
        sim (leave-overworld sim)
        [x y z] end/spawn
        platform (end/platform)
        sim (-> sim
                (assoc :sim/dimension dimension/end
                       :sim/columns (or columns {})
                       :sim/placed platform
                       :sim/broken #{}
                       :sim/items {}
                       :sim/eyes {}
                       :sim/mobs {}
                       :sim/fluids {}
                       :sim/falls {}
                       :sim/falling {}
                       :sim/player-pos end/spawn
                       :sim/view #{})
                (dissoc :sim/decoded :sim/view-at :sim/portal-since)
                (send end-respawn)
                (send {:packet/name :player-position
                       :teleport-id 4
                       :x x
                       :y y
                       :z z
                       :dx 0.0
                       :dy 0.0
                       :dz 0.0
                       :yaw 0.0
                       :pitch 0.0
                       :flags 0})
                stream)]
    (reduce #(add-mob %1 :end_crystal %2 {:persistent? true})
            (-> (reduce (fn [sim [c state]]
                          (send sim {:packet/name :block-update :pos c :state state}))
                        sim
                        (sort-by key platform))
                (add-mob :ender_dragon
                         (or dragon [0.5 65.0 0.5])
                         (cond-> {:persistent? true} dragon-yaw (assoc :yaw dragon-yaw))))
            (or crystals (map end/crystal-at end/spikes)))))

(defn through-end-portal
  "The model a tick on: a player whose feet are in an end portal outside the End arrives in it at
   once (arrive-end), as the game's end portal has no wait."
  [sim]
  (let [pos (:sim/player-pos sim)]
    (cond (or (nil? pos) (= dimension/end (:sim/dimension sim))) sim
          (= end/portal (block-at sim (mob/cell-of (update pos 1 + 0.1)))) (arrive-end sim)
          :else sim)))
