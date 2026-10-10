(ns clojurecraft.blocks
  "Block-state, item and entity-type tables generated from the vanilla reports
   (resources/clojurecraft/*.edn). Lookups are by numeric id; nothing here is mutable."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(defn- resource [n] (edn/read-string (slurp (io/resource (str "clojurecraft/" n ".edn")))))

(def table "[[name type min-state max-state] ...]" (resource "blocks"))
(def items "{name id}" (resource "items"))
(def entity-types "{name id}" (resource "entity-types"))

(def passable-types
  "Block definition types with no full-cube collision. The ground must never be here:
   grass_block's type is :grass, short_grass's is :tall_grass."
  #{:air :liquid :structure_void :light :bubble_column
    :flower :tall_flower :flower_bed :flower_pot :eyeblossom :wither_rose :bush :firefly_bush
    :sapling :bamboo_sapling :mangrove_propagule :tall_grass :double_plant :short_dry_grass
    :tall_dry_grass :dry_vegetation :leaf_litter :dead_bush :fern :mushroom :nether_sprouts
    :nether_roots :nether_fungus :sugar_cane :sweet_berry_bush :crop :torchflower_crop
    :pitcher_crop :potato :carrot :beetroot :nether_wart :attached_stem :stem :cocoa
    :seagrass :tall_seagrass :kelp :kelp_plant :hanging_roots :hanging_moss :spore_blossom
    :vine :cave_vines :cave_vines_plant :twisting_vines :twisting_vines_plant :weeping_vines
    :weeping_vines_plant :glow_lichen :multiface :sculk_vein :frogspawn
    :coral :coral_plant :coral_fan :coral_wall_fan :base_coral_plant :base_coral_fan
    :base_coral_wall_fan :sea_pickle
    :torch :wall_torch :redstone_torch :redstone_wall_torch :redstone_wire :lever :button
    :pressure_plate :weighted_pressure_plate :tripwire :trip_wire_hook :rail :powered_rail
    :detector_rail :carpet :wool_carpet :mossy_carpet :banner :wall_banner :standing_sign
    :wall_sign :ceiling_hanging_sign :wall_hanging_sign :ladder :snow_layer :web :fire :soul_fire
    :lily_pad :small_dripleaf :moving_piston})

(defn states-where
  "The set of every state id of the table rows matching pred."
  [pred]
  (set (for [[_ _ lo hi :as row] table :when (pred row), s (range lo (inc hi))] s)))

(def rows
  "state id → its [name type min-state max-state] row."
  (into {} (for [[_ _ lo hi :as row] table, s (range lo (inc hi))] [s row])))

(defn row "[name type min-state max-state] for a block-state id, or nil." [id] (get rows id))
(defn name-of "The block name keyword of a state id, or nil." [id] (some-> (row id) (nth 0)))
(defn type-of
  "The block definition type keyword of a state id (grass_block's is :grass), or nil."
  [id] (some-> (row id) (nth 1)))

(def solid-states
  "Every state that collides as a full cube (approximation: every non-passable type does)."
  (states-where (fn [[_ type]] (not (contains? passable-types type)))))

(defn solid?
  "Does the block state collide as a full cube (approximation: every non-passable type does)?"
  [id]
  (contains? solid-states id))

(defn log-name?
  "Does a block or item name end in _log (every species, stripped ones too)?"
  [n] (str/ends-with? (name n) "_log"))

(def log-states "Every state of every log block." (states-where (fn [[n]] (log-name? n))))

(defn log? "Is the state id any log block?" [id] (contains? log-states id))

(def log-items (set (for [[n id] items :when (log-name? n)] id)))

(def leaf-states "Every state of every leaves block." (states-where (fn [[n]] (str/ends-with? (name n) "_leaves"))))

(defn leaves? "Is the state id any leaves block?" [id] (contains? leaf-states id))
(defn log-item? "Is the item id any log item?" [id] (contains? log-items id))

(def air 0)                           ; the state id of air
(def item-entity-type (:item entity-types))
