(ns clojurecraft.entity
  "The entities the server shows the bot, as values: every one it adds is kept in
   :world/entities by id ({:entity/type :entity/pos :entity/seen-at}, with :entity/vel once the
   server gives one, and for a living one :entity/health and :entity/flags from its metadata),
   until the server removes it. A death is a fact the server states (entity-event status 3,
   :entity/died-at) before it removes the body. Then the questions a fight asks of them: which
   are hostile, which is nearest, whether one is gone. Items, mobs and other players are all
   entities; a reader that wants one kind asks for it (kind, living?, item?). Issue #258: the
   world held item drops only, and combat, blazes, endermen and the dragon need every entity."
  (:require [clojurecraft.blocks :as blocks]
            [clojurecraft.physics :as physics]))

(set! *warn-on-reflection* true)

(def health-index 9)                  ; LivingEntity.DATA_HEALTH_ID, a float (probe run 38876275250)
(def flags-index 0)                   ; Entity.DATA_SHARED_FLAGS_ID, a byte: 0x01 on fire, 0x02 crouching, 0x20 invisible
(def death-status 3)                  ; an entity-event's status for a living entity's death (EntityEvent.DEATH)
(def on-fire-bit 0x01)                ; in the shared flags: burning (a blaze's state, a mob in lava)

(def neutral
  "Monsters that leave a player alone until it provokes them, so they are not hostile on sight:
   an enderman attacks only when looked in the face or hit (steve's hunts: \"don't look at
   enderman's eyes before attacking\"), a zombified piglin only when one of them is hit."
  #{:enderman :zombified_piglin})

;; ---------------------------------------------------------------- what an entity is

(defn kind
  "The entity's type name (:pig, :item, :blaze), from the generated entity types; nil for an id
   the table does not have."
  [e]
  (blocks/entity-names (:entity/type e)))

(defn item? "Is the entity a dropped item stack?" [e] (= blocks/item-entity-type (:entity/type e)))

(defn living?
  "Is the entity a living one: a mob or a player, the kinds the game gives health (blocks/mobs)?"
  [e]
  (contains? blocks/mobs (kind e)))

(defn dead?
  "Has the server said the entity died (an entity-event death, kept as :entity/died-at)?"
  [e]
  (contains? e :entity/died-at))

(defn hostile?
  "Would the entity attack the bot on sight: alive, of the game's monster category, and not one
   of the neutral ones."
  [e]
  (and (not (dead? e))
       (= :monster (:category (blocks/mobs (kind e))))
       (not (contains? neutral (kind e)))))

(defn on-fire?
  "Do its shared flags say the entity burns?"
  [e]
  (pos? (bit-and (:entity/flags e 0) on-fire-bit)))

;; ---------------------------------------------------------------- metadata

(defn metadata-facts
  "The attributes an entity of type type-id gets from a skimmed metadata map ({index value},
   packet/read-metadata): its shared flags at flags-index, and, for a living type only, its
   health at health-index; a value of another shape there (no server sends one) is left out. The
   same index is an interaction's height and a boat's hurt
   direction, so health is never read off a type the game gives no health. An index the skim
   did not reach is simply absent: what was known stays."
  [type-id metadata]
  (let [flags (get metadata flags-index)
        health (get metadata health-index)]
    (cond-> {}
      (and (int? flags) (<= 0 flags 255)) (assoc :entity/flags flags)
      (and (number? health) (contains? blocks/mobs (blocks/entity-names type-id)))
      (assoc :entity/health (double health)))))

;; ---------------------------------------------------------------- the questions a fight asks

(defn hostiles
  "{eid entity} of the tracked entities that are hostile? now."
  [world]
  (into {} (filter (fn [[_ e]] (hostile? e))) (:world/entities world)))

(defn nearest
  "The [eid entity] of entities (a {eid entity} map, :world/entities or a part of it) nearest the
   point from, within radius when one is given; nil when there is none."
  ([entities from] (nearest entities from Double/POSITIVE_INFINITY))
  ([entities from radius]
   (some->> entities
            (map (fn [[eid e]] [(physics/distance from (:entity/pos e)) eid e]))
            (filter (fn [[d]] (<= d radius)))
            seq
            (apply min-key first)
            rest
            vec)))

(defn gone?
  "Is the entity eid gone from the bot's world: removed by the server, or dead by its word? A
   kill is the target being gone (ruststeve and steve both judge a kill so)."
  [world eid]
  (let [e (get-in world [:world/entities eid])] (or (nil? e) (dead? e))))
