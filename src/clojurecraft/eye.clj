(ns clojurecraft.eye
  "What the bot sees of a creature beyond where it is and how hurt: where its head looks
   (:entity/head-yaw, from rotate-head: an enderman facing the bot, a mob lining up a shot), what
   it holds and wears (:entity/equipment, from set-equipment: a wither skeleton's sword, a
   piglin's crossbow or gold), and the power of a fireball (:entity/power, projectile-power). Each
   is a defmethod on game/on-packet, open dispatch, values in and out; all are facts about a
   tracked entity, kept as new namespaced keys (the observability plan, issue #263: the bot threw
   all of it away). The entity-event statuses and an add-entity's head yaw are kept in game.clj,
   where those handlers already are."
  (:require [clojurecraft.entity :as entity]
            [clojurecraft.game :as game]
            [clojurecraft.recipe :as recipe]))

(set! *warn-on-reflection* true)

(defn equipment-of
  "{slot-name item-name} of a set-equipment packet's entries ([[slot item] ...], packet/read-equipment),
   in entity/equipment-slots' names; an empty slot (nil) is left out, and so is a slot or an item
   the tables do not name."
  [entries]
  (into {}
        (keep (fn [[slot item]]
                (let [s (entity/equipment-slots slot)
                      n (when item (recipe/item-name (:item item)))]
                  (when (and s n) [s n]))))
        entries))

(defn worn
  "The equipment map after a set-equipment's entries on top of old: each slot the packet names is
   replaced (or emptied, when the packet says empty), the others stay, as the server sends only
   what changed."
  [old entries]
  (let [emptied (keep (fn [[slot item]] (when-not item (entity/equipment-slots slot))) entries)]
    (merge (apply dissoc old emptied) (equipment-of entries))))

(defmethod game/on-packet [:play :rotate-head]
  [w {:keys [entity-id head-yaw]}]
  (cond-> w head-yaw (game/track entity-id assoc :entity/head-yaw (entity/angle head-yaw))))

(defmethod game/on-packet [:play :set-equipment]
  [w {:keys [entity-id equipment]}]
  (game/track w entity-id update :entity/equipment worn equipment))

(defmethod game/on-packet [:play :projectile-power]
  [w {:keys [entity-id power]}]
  (cond-> w (number? power) (game/track entity-id assoc :entity/power (double power))))
