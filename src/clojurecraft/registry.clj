(ns clojurecraft.registry
  "The registries a fight is told in, generated from the vanilla 26.1.2 server (bb datagen): damage
   types (the order the server syncs them: a damage-event's source type is the id), attributes
   (update-attributes: armour, max health), mob effects (wither, poison, fire resistance) and sound
   events. Id to name lookups; nothing here is mutable."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]))

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
