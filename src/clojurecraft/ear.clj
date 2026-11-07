(ns clojurecraft.ear
  "What the server lets the bot hear and see happen beyond its own sight (the observability plan,
   hearing): sounds at a point or on an entity (:world/sounds) and level events (:world/level-events:
   a ghast's warning and shot, a blaze's shot, a block broken). Vanilla plays a blaze's shot as
   level event 1018 and an enderman's stare, scream and teleport as sounds, so a shot fired from
   behind a wall or above, or an enderman turning on the bot, is heard here when it is not seen.
   Each handler is a defmethod on game/on-packet, and keeps the last kept-heard of what was heard
   within heard-radius of the bot, as values: nothing here reads a clock but :time/now."
  (:require [clojurecraft.game :as game]
            [clojurecraft.physics :as physics]
            [clojurecraft.registry :as registry]))

(set! *warn-on-reflection* true)

(def kept-heard 20)                    ; sounds, and level events, kept: the newest this many
(def heard-radius 48.0)                ; blocks from the feet within which a sound or level event is kept (a global level event is kept from anywhere)
(def sound-scale 8.0)                  ; a sound packet's position is in eighths of a block

(def level-events
  "{type name}: the level events that matter to a reader of a fight, by the game's LevelEvent
   constants (the others are kept under their number)."
  {1015 :ghast-warn
   1016 :ghast-shoot
   1017 :dragon-shoot
   1018 :blaze-shoot
   1022 :wither-break-block
   1023 :wither-spawn
   1024 :wither-shoot
   1028 :dragon-death
   1032 :portal-travel
   2001 :block-broken
   2006 :dragon-breath})

(defn level-event-name
  "The name of level event type (:blaze-shoot, :block-broken ...), or the number itself for a type
   the table does not name."
  [type]
  (get level-events type type))

(defn kept
  "v with x added at the end, the newest kept-heard of it."
  [v x]
  (let [v (conj (vec v) x)] (if (> (count v) kept-heard) (subvec v (- (count v) kept-heard)) v)))

(defn near?
  "Is pos within heard-radius of the bot's feet? True when either is unknown: a sound that cannot
   be placed is kept."
  [w pos]
  (let [me (:player/pos w)] (or (nil? pos) (nil? me) (<= (physics/distance me pos) heard-radius))))

(defn sound-pos
  "A sound packet's [x y z] in blocks, from its eighths."
  [{:keys [x y z]}]
  (mapv #(/ (double %) sound-scale) [x y z]))

(defn heard-sound
  "w with sound s (a :sound-entity's with the position of the entity it is on, when tracked) kept in
   :world/sounds when within heard-radius: {:sound name :pos [x y z] :at ms}, and :eid for a
   sound on an entity."
  [w sound pos eid]
  (cond-> w
    (near? w pos)
    (update :world/sounds
            kept
            (cond-> {:sound (registry/sound-name sound) :at (:time/now w 0)}
              pos (assoc :pos pos)
              eid (assoc :eid eid)))))

(defmethod game/on-packet [:play :sound]
  [w {:keys [sound] :as p}]
  (heard-sound w sound (sound-pos p) nil))

(defmethod game/on-packet [:play :sound-entity]
  [w {:keys [sound entity-id]}]
  (heard-sound w sound (get-in w [:world/entities entity-id :entity/pos]) entity-id))

(defmethod game/on-packet [:play :level-event]
  [w {:keys [type pos data global]}]
  (cond-> w
    (or global (near? w pos))
    (update :world/level-events
            kept
            {:event (level-event-name type) :pos pos :data data :at (:time/now w 0)})))
