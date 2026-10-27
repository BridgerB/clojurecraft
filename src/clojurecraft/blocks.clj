(ns clojurecraft.blocks
  "Block-state, item and entity-type tables generated from the vanilla reports, and the
   hardness, harvest and tool-material tables generated from the game's own classes and tags
   (resources/clojurecraft/*.edn). Lookups are by numeric id; nothing here is mutable."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(set! *warn-on-reflection* true)

(defn- resource [n] (edn/read-string (slurp (io/resource (str "clojurecraft/" n ".edn")))))

(def table "[[name type min-state max-state] ...]" (resource "blocks"))
(def items "{name id}" (resource "items"))
(def entity-types "{name id}" (resource "entity-types"))
(def entity-names
  "{id name}: entity-types turned round"
  (into {} (map (fn [[n i]] [i n])) entity-types))
(def mobs
  "{entity-name {:max-health h :category c}}: every living entity type, the only kind with health
   (the game's DefaultAttributes and MobCategory: a pig 10.0 :creature, a blaze 20.0 :monster)"
  (resource "mobs"))
(def hardness-by-name "{block-name hardness}; -1.0 is unbreakable" (resource "hardness"))
(def harvest-by-name "{block-name {:tool kind :needs tier}}" (resource "harvest"))
(def needs-tool-names
  "#{block-name}: the blocks that drop only when broken with their correct tool (the game's
   requiresCorrectToolForDrops: stone by hand drops nothing)"
  (resource "needs-tool"))
(def partial-shapes
  "{block-name top}: the blocks whose default state collides as neither nothing nor a full cube,
   and the top of the shape in blocks (the game's getCollisionShape: a cocoa pod 0.75, a slab 0.5,
   a carpet 0.0625)"
  (resource "shapes"))
(def drops-by-name
  "{block-name [[item-name chance] ...]}: what a broken block drops to a bare hand or a plain
   tool, the tries in order (the game's loot tables)"
  (resource "drops"))
(def fuel-by-name
  "{item-name burn-ticks}: every item a furnace burns (the game's FuelValues)"
  (resource "fuel"))
(def menus
  "{menu-name type-id}: the registry minecraft:menu (12 crafting, 14 furnace)"
  (resource "menus"))
(def materials
  "{tier {:speed s :durability d :incorrect tag}}: the game's ToolMaterial constants"
  (resource "materials"))

(def passable-types
  "Block definition types with no full-cube collision. The ground must never be here:
   grass_block's type is :grass, short_grass's is :tall_grass. Powder snow is here: it has no
   collision for a player without leather boots (the game's PowderSnowBlock), who sinks through
   it to the block below. As a full cube it walled sim race 38811417241 runner 2 (issue #87,
   world R2/20) into a powder-snow field, a spruce eight blocks off and no route a move long.
   The end portal is here: a player falls into it (EndPortalBlock has no collision for entities),
   which is how one goes to the End (issue #260)."
  #{:air :liquid :structure_void :light :bubble_column :flower :tall_flower :flower_bed :flower_pot
    :eyeblossom :wither_rose :bush :firefly_bush :sapling :bamboo_sapling :mangrove_propagule
    :tall_grass :double_plant :short_dry_grass :tall_dry_grass :dry_vegetation :leaf_litter
    :dead_bush :fern :mushroom :nether_sprouts :nether_roots :nether_fungus :sugar_cane
    :sweet_berry_bush :crop :torchflower_crop :pitcher_crop :potato :carrot :beetroot :nether_wart
    :attached_stem :stem :cocoa :seagrass :tall_seagrass :kelp :kelp_plant :hanging_roots
    :hanging_moss :spore_blossom :vine :cave_vines :cave_vines_plant :twisting_vines
    :twisting_vines_plant :weeping_vines :weeping_vines_plant :glow_lichen :multiface :sculk_vein
    :frogspawn :coral :coral_plant :coral_fan :coral_wall_fan :base_coral_plant :base_coral_fan
    :base_coral_wall_fan :sea_pickle :torch :wall_torch :redstone_torch :redstone_wall_torch
    :redstone_wire :lever :button :pressure_plate :weighted_pressure_plate :tripwire :trip_wire_hook
    :rail :powered_rail :detector_rail :carpet :wool_carpet :mossy_carpet :banner :wall_banner
    :standing_sign :wall_sign :ceiling_hanging_sign :wall_hanging_sign :ladder :snow_layer :web
    :fire :soul_fire :lily_pad :small_dripleaf :moving_piston :nether_portal :powder_snow
    :end_portal})

(def falling-types
  "Block definition types that fall when the cell under them is free: the game's FallingBlock
   (sand and red sand, gravel, concrete powder, anvils, the dragon egg) and BrushableBlock
   (suspicious sand and gravel), read in the 26.1.2 jar."
  #{:sand :colored_falling :concrete_powder :anvil :dragon_egg :brushable})

(defn states-where
  "The set of every state id of the table rows matching pred."
  [pred]
  (set (for [[_ _ lo hi :as row] table :when (pred row) s (range lo (inc hi))] s)))

(def rows
  "state id → its [name type min-state max-state] row."
  (into {} (for [[_ _ lo hi :as row] table s (range lo (inc hi))] [s row])))

(defn row "[name type min-state max-state] for a block-state id, or nil." [id] (get rows id))
(defn name-of
  "The block name keyword of a state id, or nil."
  [id]
  (some-> (row id)
          (nth 0)))
(defn type-of
  "The block definition type keyword of a state id (grass_block's is :grass), or nil."
  [id]
  (some-> (row id)
          (nth 1)))

(def name-states
  "block name → the set of every state id of that block."
  (reduce (fn [m [n _ lo hi]] (assoc m n (set (range lo (inc hi))))) {} table))

(defn states-of
  "Every state id of the block named n (empty for no such block)."
  [n]
  (get name-states n #{}))

(defn default-state
  "The lowest state id of the block named n, or nil: the one a server model places (a real
   server picks the state, a furnace facing away from the player, so the bot judges a placement
   by the block's name, never by this)."
  [n]
  (some->> (seq (states-of n))
           (apply min)))

(def solid-states
  "Every state that collides as a full cube (approximation: every non-passable type does)."
  (states-where (fn [[_ type]] (not (contains? passable-types type)))))

(defn solid?
  "Does the block state collide as a full cube (approximation: every non-passable type does)?"
  [id]
  (contains? solid-states id))

(def flat-shape 0.125)                ; blocks a shape may rise to and still be walked over like the floor: a carpet 0.0625; a slab 0.5 is not

(defn collision-top
  "The top, in blocks over its cell's floor, of the box block state id collides as: 1.0 for a
   full cube (solid?), the shape's top for a flat shape (partial-shapes no taller than
   flat-shape: a carpet or a pale moss carpet 0.0625, a passable type otherwise), nil for
   nothing. The server stands a player on a carpet a sixteenth over its block and refuses
   every move of one whose feet are inside it: gym batch stronghold-265c (plan run
   38934412014) run 3 landed in a pale garden and could not take one step for three minutes."
  [id]
  (if (solid? id)
    1.0
    (let [top (get partial-shapes (name-of id))]
      (when (and top (pos? (double top)) (<= (double top) flat-shape)) (double top)))))

(def air-names
  "The block names that are air: plain, in a cave, in the void."
  #{:air :cave_air :void_air})

(defn air?
  "Is block state id air of any kind (air-names): a cell the eyes' ray passes with nothing to stop
   at? A plant is not: the game clips a bucket's ray against block outlines (ClipContext.Block
   OUTLINE), and short grass, sugar cane, kelp and seagrass all have one."
  [id]
  (contains? air-names (name-of id)))

(defn falls?
  "Is block state id of a type that falls when the cell under it is free (falling-types)?"
  [id]
  (contains? falling-types (type-of id)))

(def replaceable-names
  "The blocks a placement may go into, by name: air and the few plants the game's replaceable
   tag lists (grass, ferns, a dead bush, seagrass, vines, roots, lichen). A flower, a flower
   bed (wildflowers, leaf litter), a bush or a sapling is not one: the server keeps it and
   refuses the block (race 38477665442 bot Clj_r23 placed its table into wildflowers three
   times, :rejected)."
  #{:air :cave_air :void_air :short_grass :fern :tall_grass :large_fern :dead_bush :seagrass
    :tall_seagrass :vine :hanging_roots :glow_lichen :short_dry_grass :tall_dry_grass})

(def replaceable-states
  "Every state of every replaceable block."
  (states-where (fn [[n]] (contains? replaceable-names n))))

(defn replaceable?
  "May a block be placed into the cell holding state id: is it air or a replaceable plant?"
  [id]
  (contains? replaceable-states id))

(defn placeable-into?
  "Does the server let a block take the place of state id: replaceable? (air, a grass), or a
   fluid? The game's replaceable tag lists water and lava beside the plants: a block placed
   into a source or a flow replaces it (the portal's pad is cast into the pond's edge). A bubble
   column is replaceable too (the game's block properties say .replaceable()): sim race
   39068773618 runner 8 (issue #44, world R4/37) bridged a stream over magma with cobblestone
   into its bubble column, which the model refused three times, :rejected. The bot chooses its own
   spots among replaceable? cells; this is the server's wider rule, the model's."
  [id]
  (or (replaceable? id) (= :liquid (type-of id)) (= :bubble_column (type-of id))))

(defn log-name?
  "Does a block or item name end in _log (every species, stripped ones too), or is it a Nether
   fungus's stem or hyphae (warped or crimson, stripped ones too)? The game's item tag :logs
   lists the stems beside the logs and a stem crafts to planks as a log does, so the wood chain
   takes a warped forest's trunks for the sticks and table of a new pickaxe where no tree grows
   (issue #263: the hunt's pickaxe is spent on burrows there). A pumpkin's or melon's stem is a
   plant, never a log: the warped or crimson in the name is what says trunk."
  [n]
  (let [s (name n)]
    (or (str/ends-with? s "_log")
        (and (or (str/ends-with? s "_stem") (str/ends-with? s "_hyphae"))
             (or (str/includes? s "warped") (str/includes? s "crimson"))))))

(def log-states "Every state of every log block." (states-where (fn [[n]] (log-name? n))))

(defn log? "Is the state id any log block?" [id] (contains? log-states id))

(def log-items (set (for [[n id] items :when (log-name? n)] id)))

(def leaf-states
  "Every state of every leaves block."
  (states-where (fn [[n]] (str/ends-with? (name n) "_leaves"))))

(defn leaves? "Is the state id any leaves block?" [id] (contains? leaf-states id))
(defn log-item? "Is the item id any log item?" [id] (contains? log-items id))

;; ---------------------------------------------------------------- digging: hardness, harvest, tools

(defn hardness
  "How hard a block state is to break (the number the server divides by): -1.0 when it cannot
   be broken (bedrock), nil for an id the table does not know."
  [id]
  (some-> (name-of id)
          hardness-by-name))

(defn tool-of
  "The tool kind (:pickaxe :axe :shovel :hoe) that mines a block state faster, or nil when
   every tool is as good as the hand."
  [id]
  (some-> (name-of id)
          harvest-by-name
          :tool))

(defn drops-need-tool?
  "Does a block state drop only to its correct tool (needs-tool-names)?"
  [id]
  (contains? needs-tool-names (name-of id)))

(defn needs-tier
  "The lowest tool tier (:stone :iron :diamond) a block state drops for, or nil when it drops
   for any tool and the hand."
  [id]
  (some-> (name-of id)
          harvest-by-name
          :needs))

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
  (into {}
        (for [[n id] items
              :let [[tier kind] (map keyword (str/split (name n) #"_" 2))]
              :when (and (contains? materials tier) (contains? tool-kinds kind))]
          [id {:tier tier :kind kind}])))

(defn tool
  "{:tier t :kind k} of an item id when it is a tool, else nil."
  [item-id]
  (get tools item-id))

;; ---------------------------------------------------------------- fluids

(def water-states "Every state of water." (states-where (fn [[n]] (= n :water))))
(def lava-states "Every state of lava." (states-where (fn [[n]] (= n :lava))))
(def waterlogged-types
  "Definition types whose blocks are always waterlogged: full water to the physics."
  #{:waterlogged_transparent :bubble_column :kelp :kelp_plant :seagrass :tall_seagrass})
(def water-base (apply min water-states))   ; the state of a water source (level 0)
(def lava-base (apply min lava-states))

(defn fluid
  "{:fluid/kind :water|:lava :fluid/level 0-15} for a block state that is a fluid, nil for any
   other. Level 0 is a source; 1-7 flow away from it; 8-15 are falling. A waterlogged block
   is a water source."
  [id]
  (cond (nil? id) nil
        (contains? water-states id) {:fluid/kind :water :fluid/level (- id water-base)}
        (contains? lava-states id) {:fluid/kind :lava :fluid/level (- id lava-base)}
        (contains? waterlogged-types (type-of id)) {:fluid/kind :water :fluid/level 0}
        :else nil))

(defn source?
  "Is the block state a fluid source (level 0), the kind a bucket scoops?"
  [id]
  (= 0 (:fluid/level (fluid id))))

(def air 0)                           ; the state id of air
(def item-entity-type (:item entity-types))
