(ns clojurecraft.hear
  "What the server says about a fight beyond the health and the damage-event, kept as facts in the
   world (the observability plan, part A: the bot heard none of it before): an entity's hurt flash
   (:entity/hurt-at, so a swing that landed is told from one that missed), a mob's arm swing
   (:entity/swung-at, a strike coming), the server's fight bracket (:player/in-combat?), the
   effects on the bot (:player/effects: wither, poison, fire resistance) and its attributes
   (:player/armor, :player/max-health: whether the armour is worn is the server's word, not a
   guess from the bag). Each is a defmethod on game/on-packet, open dispatch, values in and out."
  (:require [clojurecraft.eye]
            [clojurecraft.game :as game]
            [clojurecraft.registry :as registry]))

(set! *warn-on-reflection* true)

(def main-hand-swing 0)                ; animate's action for a main-hand swing (ClientboundAnimatePacket.SWING_MAIN_HAND)
(def add-value 0)                      ; an attribute modifier's operation that adds to the base (AttributeModifier.Operation.ADD_VALUE)

(defn own? "Is eid the bot's own entity?" [w eid] (= eid (:player/entity-id w)))

(defn attribute-value
  "An update-attributes entry's value as the client would hold it for the add-value modifiers: the
   base plus every add-value amount (the multiplying ones are not worn armour and are left out)."
  [{:keys [base modifiers]}]
  (reduce (fn [v {:keys [amount operation]}] (if (= add-value operation) (+ v amount) v))
          (double base)
          modifiers))

(defn attributes-of
  "{name value} of an update-attributes packet's entries, by registry/attributes name."
  [entries]
  (into {}
        (keep (fn [a] (when-let [n (registry/attributes (:id a))] [n (attribute-value a)])))
        entries))

(defmethod game/on-packet [:play :hurt-animation]
  [w {:keys [entity-id]}]
  (if (own? w entity-id) w (game/track w entity-id assoc :entity/hurt-at (:time/now w 0))))

(defmethod game/on-packet [:play :animate]
  [w {:keys [entity-id action]}]
  (if (and (= main-hand-swing action) (not (own? w entity-id)))
    (game/track w entity-id assoc :entity/swung-at (:time/now w 0))
    w))

(defmethod game/on-packet [:play :player-combat-enter] [w _] (assoc w :player/in-combat? true))
(defmethod game/on-packet [:play :player-combat-end] [w _] (assoc w :player/in-combat? false))

(defmethod game/on-packet [:play :update-mob-effect]
  [w {:keys [entity-id effect amplifier duration]}]
  (if (own? w entity-id)
    (assoc-in w
     [:player/effects (registry/mob-effects effect :unknown)]
     {:amplifier amplifier :until (+ (:time/now w 0) (* game/server-tick-ms (long duration)))})
    w))

(defmethod game/on-packet [:play :remove-mob-effect]
  [w {:keys [entity-id effect]}]
  (if (own? w entity-id)
    (update w :player/effects dissoc (registry/mob-effects effect :unknown))
    w))

(defmethod game/on-packet [:play :update-attributes]
  [w {:keys [entity-id attributes]}]
  (if (own? w entity-id)
    (let [a (attributes-of attributes)]
      (cond-> w
        (contains? a :armor) (assoc :player/armor (get a :armor))
        (contains? a :max_health) (assoc :player/max-health (get a :max_health))))
    w))
