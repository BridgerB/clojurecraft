(ns clojurecraft.blocks
  "Block-state, item and entity-type tables generated from the vanilla reports, and the
   hardness, harvest and tool-material tables generated from the game's own classes and tags
   (resources/clojurecraft/*.edn). Lookups are by numeric id; nothing here is mutable."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(defn- resource [n] (edn/read-string (slurp (io/resource (str "clojurecraft/" n ".edn")))))

(def table "[[name type min-state max-state] ...]" (resource "blocks"))
(def items "{name id}" (resource "items"))
(def entity-types "{name id}" (resource "entity-types"))
(def hardness-by-name "{block-name hardness}; -1.0 is unbreakable" (resource "hardness"))
(def harvest-by-name "{block-name {:tool kind :needs tier}}" (resource "harvest"))
(def materials "{tier {:speed s :durability d :incorrect tag}}: the game's ToolMaterial constants" (resource "materials"))

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

;; ---------------------------------------------------------------- digging: hardness, harvest, tools

(defn hardness
  "How hard a block state is to break (the number the server divides by): -1.0 when it cannot
   be broken (bedrock), nil for an id the table does not know."
  [id]
  (some-> (name-of id) hardness-by-name))

(defn tool-of
  "The tool kind (:pickaxe :axe :shovel :hoe) that mines a block state faster, or nil when
   every tool is as good as the hand."
  [id]
  (some-> (name-of id) harvest-by-name :tool))

(defn needs-tier
  "The lowest tool tier (:stone :iron :diamond) a block state drops for, or nil when it drops
   for any tool and the hand."
  [id]
  (some-> (name-of id) harvest-by-name :needs))

(def tier-rank
  "Harvest tiers in order: a tool of a higher rank drops what a lower one drops. Copper sits
   with stone because the game's incorrect_for_copper_tool tag equals stone's."
  {:wooden 0 :golden 0 :stone 1 :copper 1 :iron 2 :diamond 3 :netherite 3})

(def tool-kinds
  "The tool kinds an item name can end in; a sword is a tool that mines nothing faster here."
  #{:pickaxe :axe :shovel :hoe :sword})

(def tools
  "{item-id {:tier t :kind k}} for every tool item, read off the item names (wooden_pickaxe
   is tier :wooden, kind :pickaxe); the tiers are the ones materials.edn names."
  (into {} (for [[n id] items
                 :let [[tier kind] (map keyword (str/split (name n) #"_" 2))]
                 :when (and (contains? materials tier) (contains? tool-kinds kind))]
             [id {:tier tier :kind kind}])))

(defn tool
  "{:tier t :kind k} of an item id when it is a tool, else nil."
  [item-id]
  (get tools item-id))

(def air 0)                           ; the state id of air
(def item-entity-type (:item entity-types))
