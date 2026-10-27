(ns clojurecraft.entity
  "The entities the server shows the bot, as values: every one it adds is kept in
   :world/entities by id ({:entity/type :entity/pos :entity/seen-at}, with :entity/vel once the
   server gives one, for a living one :entity/health and :entity/flags from its metadata, and for
   a dropped item :entity/item, what it holds),
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
(def item-index 8)                    ; ItemEntity.DATA_ITEM, an item stack: what a drop holds
(def phase-index 16)                  ; EnderDragon.DATA_PHASE, an int: its EnderDragonPhase id (gym dragon-a's recording: 0 circling, 3 landing, 6 sitting)
(def creepy-index 17)                 ; EnderMan.DATA_CREEPY, a boolean: it has a target (probe run 38876275250)
(def radius-index 8)                  ; AreaEffectCloud.DATA_RADIUS, a float: a dragon's breath cloud grows from 3 to 7 (gym dragon-d's recordings)
(def death-status 3)                  ; an entity-event's status for a living entity's death (EntityEvent.DEATH)
(def on-fire-bit 0x01)                ; in the shared flags: burning (a blaze's state, a mob in lava)
(def dragon-seen-radius 96.0)         ; blocks from the bot within which the dragon's removal is its death, not a flight out of sight

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

(defn dragon? "Is the entity the ender dragon?" [e] (= :ender_dragon (kind e)))

(defn on-fire?
  "Do its shared flags say the entity burns?"
  [e]
  (pos? (bit-and (:entity/flags e 0) on-fire-bit)))

;; ---------------------------------------------------------------- metadata

(defn metadata-facts
  "The attributes an entity of type type-id gets from a skimmed metadata map ({index value},
   packet/read-metadata): its shared flags at flags-index, for a living type only its health at
   health-index, and for a dropped item only the item id of the stack at item-index; a value of
   another shape there (no server sends one) is left out. The same health index is an
   interaction's height and a boat's hurt direction, so health is never read off a type the game
   gives no health; the same item index is a fireball's look (issue #262: a drop is told apart by
   what it holds, a blaze rod from cobblestone); for an enderman only, its creepy flag at
   creepy-index (issue #263: true while it has a target, the hunt's sign one is coming); for the
   dragon only, its phase at phase-index (issue #267: sitting on its perch or flying); for an
   area effect cloud only, its radius at radius-index (the same index as a drop's stack: a
   dragon's breath grows, and the bot keeps out of all of it). An index the skim did not reach
   is simply absent: what was known stays."
  [type-id metadata]
  (let [flags (get metadata flags-index)
        health (get metadata health-index)
        stack (get metadata item-index)
        creepy (get metadata creepy-index)
        phase (get metadata phase-index)
        radius (get metadata radius-index)]
    (cond-> {}
      (and (= :area_effect_cloud (blocks/entity-names type-id)) (number? radius))
      (assoc :entity/radius (double radius))
      (and (int? flags) (<= 0 flags 255)) (assoc :entity/flags flags)
      (and (number? health) (contains? blocks/mobs (blocks/entity-names type-id)))
      (assoc :entity/health (double health))
      (and (= blocks/item-entity-type type-id) (map? stack) (int? (:item stack)))
      (assoc :entity/item (:item stack))
      (and (= :enderman (blocks/entity-names type-id)) (boolean? creepy))
      (assoc :entity/creepy? creepy)
      (and (= :ender_dragon (blocks/entity-names type-id)) (int? phase))
      (assoc :entity/phase phase))))

(defn angle
  "Degrees of an angle the server sends as a byte, in 1/256 turns (add-entity, the moves)."
  [b]
  (* (double b) (/ 360.0 256.0)))

(defn creepy?
  "Has the server said enderman e has a target (:entity/creepy?): it is coming for someone,
   most likely the bot that stared at it or hit it."
  [e]
  (true? (:entity/creepy? e)))

(defn hostile?
  "Would the entity attack the bot: alive, of the game's monster category, and not one of the
   neutral ones unless the server says it has a target (creepy?: gym dragon-b run 1 and dragon-c
   runs 2 and 3 were slain by an enderman the bot had stared at while it faced the flying
   dragon, and never fought back, the enderman being neutral)."
  [e]
  (and (not (dead? e))
       (= :monster (:category (blocks/mobs (kind e))))
       (or (not (contains? neutral (kind e))) (creepy? e))))

;; ---------------------------------------------------------------- the questions a fight asks

(defn hostiles
  "{eid entity} of the tracked entities that are hostile? now, or an attacker actively hitting
   the bot."
  [world]
  (into {}
        (filter (fn [[eid e]]
                  (or (hostile? e) (and (= eid (:player/attacker world)) (not (dead? e))))))
        (:world/entities world)))

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

(defn healths
  "{kind health} of the living entities whose health the server has said, for each kind the one
   nearest the point from: what the bot knows of the mobs round it, for the RESULT line and the
   gym's entities trial, which compares the nearest pig's with the server's own word."
  [world from]
  (->> (:world/entities world)
       (filter (fn [[_ e]] (contains? e :entity/health)))
       (group-by (fn [[_ e]] (kind e)))
       (map (fn [[k es]] [k (:entity/health (second (nearest es from)))]))
       (into {})))

(defn dragon-gone?
  "Does the removal of entity eid say the dragon died (issue #267): it is the dragon, no boss bar
   is shown (:world/boss-bars: the fight's bar goes with its dragon, and a summoned dragon has
   none), and it was last seen within dragon-seen-radius of the bot, so a dragon that flew out of
   the bot's tracking is not taken for dead."
  [world eid]
  (let [e (get-in world [:world/entities eid])
        pos (:player/pos world)]
    (boolean (and (dragon? e)
                  (empty? (:world/boss-bars world))
                  pos
                  (<= (physics/distance pos (:entity/pos e)) dragon-seen-radius)))))
