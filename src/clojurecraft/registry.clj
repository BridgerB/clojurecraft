(ns clojurecraft.registry
  "The registries a fight is told in, generated from the vanilla 26.1.2 server (bb datagen): damage
   types (the order the server syncs them: a damage-event's source type is the id), attributes
   (update-attributes: armour, max health), mob effects (wither, poison, fire resistance) and sound
   events. Id to name lookups; nothing here is mutable."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(set! *warn-on-reflection* true)

(defn- resource [n] (edn/read-string (slurp (io/resource (str "clojurecraft/" n ".edn")))))

(defn by-id "{id name} of a {name id} table." [table] (into {} (map (fn [[n i]] [i n])) table))

(def damage-types
  "{id name}: a damage-event's source type, by name"
  (by-id (resource "damage-types")))
(def attributes "{id name}: an update-attributes entry's attribute" (by-id (resource "attributes")))
(def mob-effects "{id name}: an effect's registry id" (by-id (resource "mob-effects")))

(defn damage-type
  "The name of damage type id (:mob_attack, :lava, :in_fire, :fall ...), or :unknown for an id the
   table does not have."
  [id]
  (get damage-types id :unknown))

(def sound-ids "{name id}: the sound events' registry ids, by name" (resource "sounds"))
(def sounds "{id name}: a sound packet's registry id" (by-id sound-ids))

(defn sound-name
  "The name of the sound a decoded sound holder ({:id registry-id} or inline {:name identifier})
   gives, a keyword (:entity.blaze.shoot), or :unknown for an id the table does not have. An
   inline identifier loses its minecraft: namespace."
  [{:keys [id name]}]
  (cond id (get sounds id :unknown)
        (string? name) (keyword (str/replace name #"^minecraft:" ""))
        :else :unknown))
