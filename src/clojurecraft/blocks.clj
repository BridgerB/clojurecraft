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

(def ^:private max-state (long (apply max (map #(nth % 3) table))))

(def ^:private rows
  (let [arr (object-array (inc max-state))]
    (doseq [[_ _ lo hi :as row] table, s (range lo (inc hi))] (aset arr s row))
    arr))

(defn row [^long id] (when (<= 0 id max-state) (aget ^objects rows id)))
(defn name-of [id] (some-> (row id) (nth 0)))
(defn type-of [id] (some-> (row id) (nth 1)))

(def ^:private solid-flags
  (let [arr (boolean-array (inc max-state))]
    (doseq [[_ type lo hi] table, s (range lo (inc hi))]
      (aset arr s (not (contains? passable-types type))))
    arr))

(defn solid?
  "Does the block state collide as a full cube (approximation: every non-passable type does)?"
  [^long id]
  (and (<= 0 id max-state) (aget ^booleans solid-flags id)))

(defn- log-name? [n] (str/ends-with? (name n) "_log"))

(def ^:private log-flags
  (let [arr (boolean-array (inc max-state))]
    (doseq [[n _ lo hi] table :when (log-name? n), s (range lo (inc hi))] (aset arr s true))
    arr))

(defn log? [^long id] (and (<= 0 id max-state) (aget ^booleans log-flags id)))

(def log-items (set (for [[n id] items :when (log-name? n)] id)))
(defn log-item? [id] (contains? log-items id))

(def air 0)
(def item-entity-type (:item entity-types))
