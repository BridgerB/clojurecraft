(ns clojurecraft.gym
  "The gym: real runs of one goal on GitHub runners, each on its own vanilla server and its own
   landing, judged twice, by the bot's RESULT and by the server's own answer to a truth
   command. Runs are processes speaking data, like everything else:

     clojure -M:gym trial ...   start the bot as its own JVM, land it over RCON, send its :go
                                through its stdin, judge it from its log and the server; one
                                more attempt on the same landing if it lost its connection
     clojure -M:gym report --dir artifacts/   (folds every result.edn into one summary)

   gym.yml runs one trial per runner; a plan runs several on one server, tagging each row.

   `gyms` is the registry, a table like plan/goals: which goal, the --until that runs it, what
   to give the bot first, how long it may take, and the truth commands that must all pass.
   Judging is one pure function from what the run left behind to an outcome; the report is a
   pure fold over results. A run that never got going (:harness) or lost its connection
   (:disconnect) says nothing about the goal and is not counted."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.math :as math]
            [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [clojurecraft.cli :as cli]
            [clojurecraft.dimension :as dimension]
            [clojurecraft.fixture :as fixture]
            [clojurecraft.food :as food]
            [clojurecraft.landings :as landings]
            [clojurecraft.rcon :as rcon])
  (:gen-class))

(set! *warn-on-reflection* true)

;; the shape of a registry row and of a judged result row
(s/def :gym/goal keyword?)
(s/def :gym/until string?)
(s/def :gym/prereqs (s/coll-of string? :kind vector?))
(s/def :gym/timeout-ms pos-int?)
(s/def :truth/cmd string?)
(s/def :truth/re string?)
(s/def :truth/result (s/coll-of keyword? :kind vector?)) ; a path into the bot's RESULT whose value {result} in :truth/re stands for
(s/def :truth/reply string?)
(s/def :truth/ok? boolean?)
(s/def :gym/truth
  (s/coll-of (s/keys :req [:truth/cmd :truth/re] :opt [:truth/result]) :kind vector? :min-count 1))
(s/def :gym/setup (s/coll-of string? :kind vector?)) ; RCON commands that build the row's arena round the landing (fill-in)
(s/def :gym/land keyword?)               ; how the bot is landed (land!): :surface when absent
(s/def :gym/summon (s/coll-of string? :kind vector?)) ; RCON commands run in the row's dimension once the bot stands there with its kit (fill-in): the mobs it meets
(s/def :gym/difficulty #{"peaceful" "easy" "normal" "hard"}) ; set before the bot is landed
(s/def :gym/fixture? true?)              ; a row that judges the landing alone: its --until puts no goal in play
(s/def :gym/name string?)                ; the row's own name when several rows play one goal from different starts (issue #262: blaze-1, blaze-7)
(s/def ::row
  (s/keys :req [:gym/goal :gym/until :gym/prereqs :gym/timeout-ms :gym/truth]
          :opt [:gym/setup :gym/land :gym/summon :gym/difficulty :gym/fixture? :gym/name]))
(s/def :gym/run pos-int?)
(s/def :gym/landing (s/coll-of int? :kind vector? :count 3))
(s/def :gym/landed? boolean?)
(s/def :gym/outcome #{:pass :fail :timeout :death :disconnect :harness})
(s/def :gym/ms int?)
(s/def :gym/reason (s/nilable keyword?))
(s/def :gym/truths (s/coll-of (s/keys :req [:truth/cmd :truth/reply :truth/ok?]) :kind vector?))
(s/def :gym/commit (s/nilable string?))
(s/def :gym/result (s/nilable map?))
(s/def ::result
  (s/keys :req [:gym/goal :gym/run :gym/landed? :gym/outcome :gym/truths]
          :opt [:gym/landing :gym/ms :gym/reason :gym/commit :gym/result]))

(def arena-reach 16)                  ; blocks round the landing an arena's setup is forceloaded over
(def fortress-offset 48)              ; blocks west of a located fortress the Nether pad is carved
(def pad-y 72)                        ; the Nether pad's feet height: over the lava sea (y 31), under the roof
(def arena-half 5)                    ; the nether arena's half-width: rock two thick round a room seven square
(def end-at [8 0])                    ; [x z] of the End landing: the main island's end stone 8 east of the fountain, as ruststeve's End gyms start
(def end-reach 64)                    ; blocks round the fountain loaded for the End landing: the towers stand at radius 42
(def dimension-wait-ms 30000)         ; how long the kit waits for the server to say the bot is in its row's dimension
(def hungry-food 10)                  ; the food a :hungry landing waits for before the bot is told to go

(def dimensions
  "The dimension each kind of landing (:gym/land) leaves the bot in; any other is the overworld.
   The kit is given only once the server says the bot stands there (landing!)."
  {:nether-fortress dimension/nether :nether-arena dimension/nether :end dimension/end})

(def fortress-pad
  "The fortress row's start: a hollow box of netherrack round three cells of air at the pad."
  ["fill {x-2} {y-1} {z-2} {x+2} {y+3} {z+2} minecraft:netherrack hollow"])

(def nether-arena
  "A sealed room in the Nether (issue #261), ruststeve's arena-run.sh recipe: netherrack two
   thick on every side round a room seven square and four high (an enderman stands in it, a
   blaze hovers), its floor at the landing's feet. Nothing outside reaches in: no lava, no
   ghast, no fall, so a row measures the fight and not the Nether. ruststeve built its blaze
   arena in the overworld because its SDK streamed Nether chunks too slowly after the
   cross-dimension teleport; ours reads the dimension's columns as they come (issue #11)."
  ["fill {x-5} {y-2} {z-5} {x+5} {y+5} {z+5} minecraft:netherrack"
   "fill {x-3} {y} {z-3} {x+3} {y+3} {z+3} minecraft:air"])

(defn offset
  "A fill-in placeholder for axis (\"x\") at d blocks from the landing: {x}, {x+3}, {x-3}."
  [axis d]
  (str "{"
       axis
       (cond (pos? d) (str "+" d)
             (neg? d) d
             :else "")
       "}"))

(defn at-offset
  "The three fill-in placeholders for the landing moved by [dx dy dz]."
  [[dx dy dz]]
  (str/join " " [(offset "x" dx) (offset "y" dy) (offset "z" dz)]))

(defn summon
  "The command that summons entity (a name: \"zombie\") at the landing moved by d [dx dy dz],
   with nbt (SNBT) when given: a row's :gym/summon entry."
  ([entity d] (summon entity d nil))
  ([entity d nbt] (str "summon minecraft:" entity " " (at-offset d) (when nbt (str " " nbt)))))

(defn no-ai-zombie
  "A zombie that never moves or attacks (NoAI) and never despawns, at d: the first thing a
   fight row kills (issue #259's attack row)."
  [d]
  (summon "zombie" d "{NoAI:1b,PersistenceRequired:1b}"))

(defn blaze-spawner
  "A blaze spawner at d, ruststeve's arena-run.sh numbers: two at a time, six at most near it,
   while a player is within 16, every two to four seconds."
  [d]
  (str
   "setblock "
   (at-offset d)
   " minecraft:spawner{SpawnData:{entity:{id:\"minecraft:blaze\"}},SpawnCount:2,MaxNearbyEntities:6,RequiredPlayerRange:16,SpawnRange:2,MinSpawnDelay:40,MaxSpawnDelay:80}"))

(defn endermen
  "n endermen at d that never despawn: the pearls' other source, the End's crowd."
  [n d]
  (vec (repeat n (summon "enderman" d "{PersistenceRequired:1b}"))))

(def dragon
  "A dragon of known state at the fountain: any other gone first, then one summoned with full
   health and DragonPhase 0 (circling). A summoned dragon has no fight manager and starts in
   phase 10, hovering, so the siblings set both (ruststeve gym.rs, steve gym/registry.ts)."
  ["kill @e[type=minecraft:ender_dragon]"
   "summon minecraft:ender_dragon 0 80 0 {DragonPhase:0,Health:200f}"])

(defn crystals
  "End crystals at each of ds (offsets from the landing), every other crystal gone first: a
   crystal row knows exactly what it must break."
  [ds]
  (into ["kill @e[type=minecraft:end_crystal]"]
        (map #(summon "end_crystal" % "{ShowBottom:0b}"))
        ds))

(def night "time set midnight")       ; a :gym/summon entry: the hostiles' hour, for a row that defends

(def fire-resistance
  "A :gym/summon entry: the bot never burns (a fireball's hit still hurts)."
  "effect give {name} minecraft:fire_resistance 1000000 0 true")

(def invulnerable
  "What makes the bot take no harm, as :gym/summon entries: Resistance V (every hurt cut to
   nothing) and Fire Resistance, for a row that measures a fight and not the bot's survival
   (ruststeve's arena gave its bot Invulnerable:1b, which no command can set on a player)."
  ["effect give {name} minecraft:resistance 1000000 4 true" fire-resistance])

(def iron-armour
  "Iron armour put on the bot, as :gym/summon entries: given after the dimension change with the
   kit (item replace, since a mob at hand comes before dressing)."
  ["item replace entity {name} armor.head with minecraft:iron_helmet"
   "item replace entity {name} armor.chest with minecraft:iron_chestplate"
   "item replace entity {name} armor.legs with minecraft:iron_leggings"
   "item replace entity {name} armor.feet with minecraft:iron_boots"])

(def no-fire
  "A :gym/summon entry: the blazes' fireballs set no blocks alight (SmallFireball places fire only
   when mob griefing is on). A fire lights the cells round it past 11, and a spawner's blaze
   spawns only where the light is 11 or less: gym blaze-7a run 3 (run 38891910129) had the floor
   by the spawner lit after its first fireballs, and the spawner made no blaze in the three
   minutes after. The bot itself still burns when hit."
  "gamerule mob_griefing false")

(defn rods-held
  "A truth that the bot holds n blaze rods or more (n under 10)."
  [n]
  {:truth/cmd "clear {name} minecraft:blaze_rod 0" :truth/re (str "Found ([" n "-9]|[1-9][0-9]) ")})

(def portal-arena
  "The portal rows' arena round the landing: stone ten deep under air, and a trench of lava
   sources one wide set into its surface east of the landing, every source with stone beside it to
   refill from (a scoop takes a source for good). The surface case of issue #124: the frame is cut
   into the rock under the pool's level and the room dug out in front of it, so the stone must be
   deep enough for a frame six under the surface."
  ["fill {x-8} {y-10} {z-8} {x+16} {y-1} {z+16} minecraft:stone"
   "fill {x-8} {y} {z-8} {x+16} {y+14} {z+16} minecraft:air"
   "fill {x+14} {y-1} {z-6} {x+14} {y-1} {z+6} minecraft:lava"])

(def portal-kit
  "What a portal row gives: two buckets of lava, one of water, an empty one, a handful of
   cobblestone (a cast cut into the rock places four, the walls its own holes opened: issue #124),
   flint and steel, and the stone pickaxe the rows hold for the digs."
  ["minecraft:lava_bucket 2" "minecraft:water_bucket 1" "minecraft:bucket 1"
   "minecraft:cobblestone 8" "minecraft:flint_and_steel 1" "minecraft:stone_pickaxe 1"])

(defn block-count
  "A truth that counts the blocks named block in the arena, from the floor of its stone to over
   its air (the frame is cut into the stone): a clone of it filtered to them, to air high over it,
   whose reply says how many (re, a pattern of that reply)."
  [block re]
  {:truth/cmd (str "clone {x-8} {y-10} {z-8} {x+16} {y+12} {z+16} {x-8} {y+60} {z-8} filtered "
                   block)
   :truth/re re})

(def gyms
  "The gym registry. Truth commands count items without removing any (`clear <name> <item> 0`
   answers \"Found N matching item(s)...\"); {name} is the bot's name; patterns are strings so
   the table stays EDN."
  [{:gym/goal :wood
    :gym/until "wood"
    :gym/prereqs []
    :gym/timeout-ms 150000
    :gym/truth [{:truth/cmd "clear {name} #minecraft:logs 0" :truth/re "Found [1-9]"}]}
   {:gym/goal :kit
    :gym/until "table"
    :gym/prereqs []
    :gym/timeout-ms 240000
    :gym/truth [{:truth/cmd "clear {name} minecraft:crafting_table 0" :truth/re "Found [1-9]"}
                {:truth/cmd "clear {name} minecraft:stick 0" :truth/re "Found ([4-9]|[1-9][0-9])"}]}
   {:gym/goal :pickaxe
    :gym/until "pickaxe"
    :gym/prereqs []
    :gym/timeout-ms 300000
    :gym/truth [{:truth/cmd "clear {name} minecraft:wooden_pickaxe 0" :truth/re "Found [1-9]"}]}
   ;; the pickaxe is given, so the row measures stone, not the wood and crafting it already measures
   {:gym/goal :cobblestone
    :gym/until "cobblestone"
    :gym/prereqs ["minecraft:wooden_pickaxe 1"]
    :gym/timeout-ms 300000
    :gym/truth [{:truth/cmd "clear {name} minecraft:cobblestone 0"
                 :truth/re "Found ([3-9]|[1-9][0-9])"}
                {:truth/cmd "clear {name} minecraft:wooden_pickaxe 0" :truth/re "Found [1-9]"}]}
   ;; the wooden pickaxe, sticks and a table are given, so the row measures stone and the table craft
   {:gym/goal :stone-pickaxe
    :gym/until "stone-pickaxe"
    :gym/prereqs ["minecraft:wooden_pickaxe 1" "minecraft:stick 2" "minecraft:crafting_table 1"]
    :gym/timeout-ms 300000
    :gym/truth [{:truth/cmd "clear {name} minecraft:stone_pickaxe 0" :truth/re "Found [1-9]"}]}
   ;; issue #6, one row per stage of the iron kit, each given what the stages before it make.
   ;; The furnace: a placed block is not in the inventory, so the server's word is that the
   ;; cobblestone was spent and no furnace is held; the bot's RESULT needs the placed block seen.
   {:gym/goal :furnace
    :gym/until "furnace"
    :gym/prereqs ["minecraft:cobblestone 8" "minecraft:crafting_table 1"]
    :gym/timeout-ms 180000
    :gym/truth [{:truth/cmd "clear {name} minecraft:cobblestone 0" :truth/re "No items were found"}
                {:truth/cmd "clear {name} minecraft:furnace 0" :truth/re "No items were found"}]}
   {:gym/goal :ingots
    :gym/until "ingots"
    :gym/prereqs ["minecraft:furnace 1" "minecraft:raw_iron 4" "minecraft:coal 1"]
    :gym/timeout-ms 240000
    :gym/truth [{:truth/cmd "clear {name} minecraft:iron_ingot 0"
                 :truth/re "Found ([4-9]|[1-9][0-9])"}]}
   {:gym/goal :bucket
    :gym/until "bucket"
    :gym/prereqs ["minecraft:iron_ingot 3" "minecraft:crafting_table 1"]
    :gym/timeout-ms 180000
    :gym/truth [{:truth/cmd "clear {name} minecraft:bucket 0" :truth/re "Found [1-9]"}]}
   {:gym/goal :water-bucket
    :gym/until "water-bucket"
    :gym/prereqs ["minecraft:bucket 1" "minecraft:stone_pickaxe 2"]
    :gym/timeout-ms 300000
    :gym/truth [{:truth/cmd "clear {name} minecraft:water_bucket 0" :truth/re "Found [1-9]"}]}
   ;; a gravel is given, so the row measures digging it again for flint, not finding gravel
   {:gym/goal :flint-and-steel
    :gym/until "flint-and-steel"
    :gym/prereqs ["minecraft:iron_ingot 1" "minecraft:gravel 1"]
    :gym/timeout-ms 300000
    :gym/truth [{:truth/cmd "clear {name} minecraft:flint_and_steel 0" :truth/re "Found [1-9]"}]}
   ;; the whole stage from what #9 ends with, as the sim's end-to-end run starts. Forty minutes:
   ;; down to y 16, iron, coal, gravel and the smelts there, then the stairs back up to water; batch
   ;; iron-a5's three runs that held flint and steel and the bucket were still climbing at twenty
   {:gym/goal :iron-kit
    :gym/until "iron-kit"
    :gym/prereqs ["minecraft:stone_pickaxe 1" "minecraft:crafting_table 1" "minecraft:cobblestone 8"
                  "minecraft:oak_log 4"]
    :gym/timeout-ms 2400000
    :gym/truth [{:truth/cmd "clear {name} minecraft:water_bucket 0" :truth/re "Found [1-9]"}
                {:truth/cmd "clear {name} minecraft:flint_and_steel 0" :truth/re "Found [1-9]"}]}
   ;; issue #8: the portal cast in an arena built round the landing, from the kit
   {:gym/goal :cast-one
    :gym/until "cast-one"
    :gym/prereqs portal-kit
    :gym/setup portal-arena
    :gym/timeout-ms 600000
    :gym/truth [(block-count "minecraft:obsidian" "cloned [1-9]")]}
   {:gym/goal :portal
    :gym/until "portal"
    :gym/prereqs portal-kit
    :gym/setup portal-arena
    :gym/timeout-ms 2400000
    :gym/truth [(block-count "minecraft:obsidian" "cloned (1[0-9]|[2-9][0-9]) block")
                (block-count "minecraft:nether_portal" "cloned [1-9]")]}
   {:gym/goal :nether
    :gym/until "nether"
    :gym/prereqs portal-kit
    :gym/setup portal-arena
    :gym/timeout-ms 2400000
    :gym/truth [{:truth/cmd "data get entity {name} Dimension" :truth/re "the_nether"}]}
   ;; issue #5: wade in, get out; the server must see the bot on dry land (steve's onDryLand judge)
   ;; issue #11: from a pad carved in the Nether 48 blocks west of a located fortress, tunnel to
   ;; it; judged in the bot's own dimension: the Nether, and fortress bricks within 6 (a filtered
   ;; clone of the cube round it counts them)
   {:gym/goal :fortress
    :gym/until "fortress"
    :gym/land :nether-fortress
    :gym/prereqs ["minecraft:iron_pickaxe 1" "minecraft:cobblestone 64"]
    :gym/timeout-ms 900000
    :gym/truth
    [{:truth/cmd "execute as {name} at @s if dimension minecraft:the_nether"
      :truth/re "Test passed"}
     {:truth/cmd
      "execute as {name} at @s run clone ~-6 ~-6 ~-6 ~6 ~6 ~6 ~-6 ~20 ~-6 filtered minecraft:nether_bricks"
      :truth/re "cloned [1-9]"}]}
   ;; issue #258: a pig summoned over the landing, held still and unhurt at a health no natural
   ;; pig has; the bot's RESULT names the nearest pig's health from its metadata, and the server
   ;; must report the same for the pig nearest the bot
   {:gym/goal :entities
    :gym/until "entities"
    :gym/prereqs []
    :gym/setup
    ["summon minecraft:pig {x} {y+2} {z} {NoAI:1b,Invulnerable:1b,PersistenceRequired:1b,Health:7.5f}"]
    :gym/timeout-ms 90000
    :gym/truth
    [{:truth/cmd
      "execute as {name} at @s run data get entity @e[type=minecraft:pig,limit=1,sort=nearest] Health"
      :truth/re "entity data: {result}f"
      :truth/result [:mobs :pig]}]}
   ;; issue #261: the arenas themselves, judged with no goal in play: the bot in the dimension,
   ;; the kit held (given only after the server said so), the room and what was summoned in it.
   ;; The rows that fight in them (#258, #259, #10) copy these starts.
   {:gym/goal :arena-nether
    :gym/until "arena-nether"
    :gym/fixture? true
    :gym/land :nether-arena
    :gym/difficulty "normal"
    :gym/prereqs ["minecraft:iron_sword 1" "minecraft:cooked_beef 8"]
    :gym/summon [(no-ai-zombie [2 0 2])]
    :gym/timeout-ms 120000
    :gym/truth [{:truth/cmd "execute as {name} at @s if dimension minecraft:the_nether"
                 :truth/re "Test passed"}
                {:truth/cmd "clear {name} minecraft:iron_sword 0" :truth/re "Found 1"}
                {:truth/cmd "execute as {name} at @s if block ~ ~-1 ~ minecraft:netherrack"
                 :truth/re "Test passed"}
                {:truth/cmd
                 "execute as {name} at @s if entity @e[type=minecraft:zombie,distance=..6]"
                 :truth/re "Test passed"}]}
   {:gym/goal :arena-end
    :gym/until "arena-end"
    :gym/fixture? true
    :gym/land :end
    :gym/difficulty "normal"
    :gym/prereqs ["minecraft:iron_sword 1" "minecraft:cooked_beef 8"]
    :gym/summon (into dragon (crystals [[3 0 3]]))
    :gym/timeout-ms 120000
    :gym/truth
    [{:truth/cmd "execute as {name} at @s if dimension minecraft:the_end" :truth/re "Test passed"}
     {:truth/cmd "clear {name} minecraft:iron_sword 0" :truth/re "Found 1"}
     {:truth/cmd "execute as {name} at @s if block ~ ~-1 ~ minecraft:end_stone"
      :truth/re "Test passed"}
     {:truth/cmd "execute as {name} at @s if entity @e[type=minecraft:end_crystal,distance=..8]"
      :truth/re "Test passed"}
     {:truth/cmd "execute in minecraft:the_end if entity @e[type=minecraft:ender_dragon]"
      :truth/re "Test passed"}]}
   {:gym/goal :shore
    :gym/until "shore"
    :gym/prereqs []
    :gym/timeout-ms 180000
    :gym/truth
    [{:truth/cmd
      "execute as {name} at @s unless block ~ ~ ~ minecraft:water unless block ~ ~1 ~ minecraft:water"
      :truth/re "Test passed"} {:truth/cmd "data get entity {name} OnGround" :truth/re "1b"}]}
   ;; issue #259: landed hungry (the :hungry landing waits for the food to fall under
   ;; hungry-food) with four breads, the bot eats until the bar is at 14 or more; judged by the
   ;; server's food and the breads gone
   {:gym/goal :eat
    :gym/until "eat"
    :gym/land :hungry
    :gym/prereqs ["minecraft:bread 4"]
    :gym/timeout-ms 60000
    :gym/truth [{:truth/cmd "data get entity {name} foodLevel" :truth/re "data: (1[4-9]|20)$"}
                {:truth/cmd "clear {name} minecraft:bread 0" :truth/re "Found [1-3] |No items"}]}
   ;; issue #259: the portal's iron held and thirteen ingots spare, a helmet and a chestplate made
   ;; at a placed table and worn, judged by what the server has in the armour slots
   {:gym/goal :armour
    :gym/until "armour"
    :gym/prereqs ["minecraft:flint_and_steel 1" "minecraft:water_bucket 1" "minecraft:bucket 1"
                  "minecraft:crafting_table 1" "minecraft:iron_ingot 13"]
    :gym/timeout-ms 180000
    :gym/truth [{:truth/cmd "execute if items entity {name} armor.head minecraft:iron_helmet"
                 :truth/re "Test passed"}
                {:truth/cmd "execute if items entity {name} armor.chest minecraft:iron_chestplate"
                 :truth/re "Test passed"}]}
   ;; issue #259: a husk that does not move (NoAI; a husk does not burn by day) three blocks east
   ;; of the landing at noon, a stone sword given; judged by the husk gone. The attacks' spacing
   ;; is read from the recording
   {:gym/goal :attack
    :gym/until "attack"
    :gym/prereqs ["minecraft:stone_sword 1"]
    :gym/setup ["time set noon"
                "summon minecraft:husk {x+3} {y} {z} {NoAI:1b,PersistenceRequired:1b}"]
    :gym/timeout-ms 90000
    :gym/truth
    [{:truth/cmd
      "execute positioned {x} {y} {z} unless entity @e[type=minecraft:husk,distance=..24]"
      :truth/re "Test passed"}]}
   ;; issue #259: three zombies two blocks round the landing at midnight, the bot in iron armour
   ;; (put on by the fixture: a zombie at hand comes before dressing) with an iron sword; judged
   ;; by a zombie killed (a kill counter the setup adds: counting zombies round the landing counts
   ;; the night's new ones too, five in defend-259b run 1) and the bot alive with health left (a
   ;; death is its own outcome). Not all
   ;; three: the row is done when none is within 4, and in gym defend-259a (run 38881961947)
   ;; the others wandered off while the first was fought. Judged at the landing, never as the
   ;; bot: execute as the bot answered RCON with nothing in every trial of that run
   {:gym/goal :defend
    :gym/until "defend"
    :gym/prereqs ["minecraft:iron_sword 1"]
    :gym/setup ["time set midnight"
                "scoreboard objectives add zkills minecraft.killed:minecraft.zombie"
                "item replace entity {name} armor.head with minecraft:iron_helmet"
                "item replace entity {name} armor.chest with minecraft:iron_chestplate"
                "item replace entity {name} armor.legs with minecraft:iron_leggings"
                "item replace entity {name} armor.feet with minecraft:iron_boots"
                "summon minecraft:zombie {x+2} {y} {z} {PersistenceRequired:1b}"
                "summon minecraft:zombie {x-2} {y} {z+1} {PersistenceRequired:1b}"
                "summon minecraft:zombie {x} {y} {z-2} {PersistenceRequired:1b}"]
    :gym/timeout-ms 120000
    :gym/truth [{:truth/cmd "scoreboard players get {name} zkills" :truth/re "has [1-9]"}
                {:truth/cmd "data get entity {name} Health" :truth/re "data: [1-9]"}]}
   ;; issue #262: blaze rods. blaze-1: the nether arena with a blaze spawner in its corner and a
   ;; blaze summoned, the bot unharmed (invulnerable) with six rods given, so one more is the
   ;; goal's seventh: the fight, the pickup of a drop that may not come, the spawner camped by
   {:gym/goal :blaze-rods
    :gym/name "blaze-1"
    :gym/until "blaze-rods"
    :gym/land :nether-arena
    :gym/difficulty "normal"
    :gym/prereqs ["minecraft:iron_sword 1" "minecraft:cooked_beef 8" "minecraft:blaze_rod 6"]
    :gym/summon (into invulnerable
                      [no-fire (blaze-spawner [3 0 3])
                       (summon "blaze" [-2 1 -2] "{PersistenceRequired:1b}")])
    :gym/timeout-ms 300000
    :gym/truth [(rods-held 7)]}
   ;; blaze-7: the same room, all seven rods from its blazes, the bot in iron armour and hurt by
   ;; their fireballs, so the break-off under 10 health and the meal after it are played. Fire
   ;; Resistance, as ruststeve's arena gave ("fire resistance only stops the burn"): a burning bot
   ;; in the Nether has no water to put it out, and blaze-7a runs 1 and 2 (run 38891910129) burned
   ;; to death inside a minute
   {:gym/goal :blaze-rods
    :gym/name "blaze-7"
    :gym/until "blaze-rods"
    :gym/land :nether-arena
    :gym/difficulty "normal"
    :gym/prereqs ["minecraft:iron_sword 1" "minecraft:cooked_beef 16"]
    :gym/summon (into iron-armour
                      [fire-resistance no-fire (blaze-spawner [3 0 3])
                       (summon "blaze" [-2 1 -2] "{PersistenceRequired:1b}")])
    :gym/timeout-ms 900000
    :gym/truth [(rods-held 7)]}
   ;; blaze-fortress: from the fortress row's pad 48 west of a located fortress, the spawner found
   ;; in the chunks loaded round it, tunnelled to, camped by, seven rods from its blazes
   {:gym/goal :blaze-rods
    :gym/name "blaze-fortress"
    :gym/until "blaze-rods"
    :gym/land :nether-fortress
    :gym/difficulty "normal"
    :gym/prereqs ["minecraft:iron_pickaxe 1" "minecraft:cobblestone 64" "minecraft:iron_sword 1"
                  "minecraft:cooked_beef 16"]
    :gym/summon iron-armour
    :gym/timeout-ms 1800000
    :gym/truth [(rods-held 7)]}])

(def counted
  "Outcomes that say something about the goal; :harness and :disconnect do not."
  #{:pass :fail :timeout :death})

(def strict-failures #{:fail :timeout :death}) ; the outcomes that fail a strict (push) trial
(def bot-heap "-Xmx1g")                 ; each bot is its own JVM; one fits several on a runner
(def hold-ms 60000)                     ; how long an ok bot stays online for the judge's truth commands

(def z95 1.959964)                      ; the normal quantile for a two-sided 95% interval
(def judge-grace-ms 120000)             ; past the goal's timeout, how long the judge waits for RESULT
(def trial-overhead-minutes 8)          ; setup, server start and judging, on top of a goal's timeout

;; ---------------------------------------------------------------- data

(defn gym
  "The registry row named goal: by its own :gym/name first (several rows may play one goal from
   different starts), else the first row of that goal keyword or --until name; nil for none."
  [goal]
  (let [n (name goal)]
    (or (some #(when (= n (:gym/name %)) %) gyms)
        (some #(when (or (= n (name (:gym/goal %))) (= n (:gym/until %))) %) gyms))))

(defn fill-in
  "cmd with {name} the bot's name and {x} {y} {z} the landing's coordinates, each with an offset
   when written {x+14} or {z-8}: an arena or a truth command placed relative to the landing."
  [cmd bot [x z y]]
  (-> cmd
      (str/replace "{name}" bot)
      (str/replace #"\{([xyz])([+-]\d+)?\}"
                   (fn [[_ axis off]]
                     (str (+ ({"x" x "y" y "z" z} axis) (if off (parse-long off) 0)))))))

(defn in-dimension
  "cmd run in the dimension named dim."
  [dim cmd]
  (str "execute in " dim " run " cmd))

(defn dimension-of
  "The dimension row's landing leaves the bot in (dimensions; the overworld for the rest)."
  [row]
  (get dimensions (:gym/land row) dimension/overworld))

(defn dimension-test
  "The test the server passes once the bot named bot stands in the dimension named dim."
  [bot dim]
  (str "execute as " bot " at @s if dimension " dim))

(defn kit
  "The commands that give row's prerequisites to bot: run only once the server says the bot is
   in the row's dimension. steve gave its End kit before the cross-dimension teleport and the
   server held none of it while the client counted six beds (issue #10's memory note); so the
   gym lands, waits for the dimension, then gives (issue #261)."
  [row bot]
  (mapv #(str "give " bot " " %) (:gym/prereqs row)))

(defn summons
  "The row's :gym/summon commands for bot at landing, filled in and run in its dimension: after
   the kit, so a mob never meets a bot with empty hands."
  [row bot landing]
  (mapv #(in-dimension (dimension-of row) (fill-in % bot landing)) (:gym/summon row)))

(defn truth-cmd
  "A row's truth command for the bot's name and the run's landing (fill-in)."
  [{:truth/keys [cmd]} bot landing]
  (fill-in cmd bot landing))

(defn truth-re
  "A truth row's pattern for the bot's RESULT: the row's :truth/re, or, when the row names a
   :truth/result path, that pattern with {result} the value at the path, quoted, so the server
   must say what the bot saw (the entities trial: the pig's health, issue #258); nil when the
   RESULT has no value there, which no reply passes."
  [{:truth/keys [re] path :truth/result} result]
  (let [v (when path (get-in result path))]
    (cond (nil? path) re
          (some? v) (str/replace re "{result}" (java.util.regex.Pattern/quote (str v)))
          :else nil)))

(defn truth
  "A truth row's verdict on the server's reply to cmd (the row's command, filled in), given the
   bot's RESULT when the row compares with it (truth-re): {:truth/cmd :truth/reply :truth/ok?}."
  ([row cmd reply] (truth row cmd reply nil))
  ([row cmd reply result]
   (let [re (truth-re row result)]
     {:truth/cmd cmd
      :truth/reply (str reply)
      :truth/ok? (boolean (and re (re-find (re-pattern re) (str reply))))})))

(defn result-line
  "The RESULT map a whole line of the bot's output carries, or nil for any other line."
  [line]
  (when (str/starts-with? line "RESULT ") (edn/read-string (subs line 7))))

(defn outcome
  "How a run ended, from what it left behind. :pass only when the bot says ok and every truth
   command agrees; the bot saying ok while the server disagrees is :fail, the most useful row."
  [{:gym/keys [landed? result truths]}]
  (cond (not landed?) :harness
        (or (nil? result) (:disconnected result) (:closed result)) :disconnect
        (pos? (get-in result [:stats :stats/deaths] 0)) :death
        (= :timeout (:reason result)) :timeout
        (and (:ok result) (seq truths) (every? :truth/ok? truths)) :pass
        :else :fail))

(defn judged
  "The result row for one run: what it left behind, plus its outcome."
  [{:gym/keys [goal run landing landed? result truths ms commit] :as left}]
  {:gym/goal goal
   :gym/run run
   :gym/landing landing
   :gym/landed? (boolean landed?)
   :gym/outcome (outcome left)
   :gym/ms ms
   :gym/reason (:reason result)
   :gym/truths (vec truths)
   :gym/commit commit
   :gym/result result})

(defn wilson
  "The 95% Wilson score interval for k passes in n trials, [lo hi] in 0..1."
  [k n]
  (if (zero? n)
    [0.0 1.0]
    (let [p (/ (double k) n)
          z2 (* z95 z95)
          d (inc (/ z2 n))
          c (/ (+ p (/ z2 (* 2 n))) d)
          m (/ (* z95 (math/sqrt (+ (/ (* p (- 1 p)) n) (/ z2 (* 4 n n))))) d)]
      [(max 0.0 (- c m)) (min 1.0 (+ c m))])))

(defn pct "A fraction as a whole percent." [x] (math/round (* 100.0 (double x))))

(defn summary-line
  "One goal's verdict: pass k/n with its interval, and the runs not counted."
  [rows]
  (let [scored (filter (comp counted :gym/outcome) rows)
        k (count (filter #(= :pass (:gym/outcome %)) scored))
        n (count scored)
        [lo hi] (wilson k n)
        uncounted (frequencies (map :gym/outcome (remove (comp counted :gym/outcome) rows)))]
    (str "pass "
         k
         "/"
         n
         " ["
         (pct lo)
         "%, "
         (pct hi)
         "%]"
         "; not counted: harness " (uncounted :harness 0)
         ", disconnect " (uncounted :disconnect 0))))

(defn row-line
  "One run as a markdown table row."
  [{:gym/keys [run landing outcome ms reason truths]}]
  (str "| "
       run
       " | "
       (str/join "," (take 2 landing))
       " | "
       (name outcome)
       " | "
       (some-> ms
               (quot 1000))
       " | "
       (some-> reason
               name)
       " | "
       (str/join "; " (map :truth/reply truths))
       " |"))

(defn tally
  "\"a 2, b 1\" for the values of xs, sorted by name."
  [xs]
  (str/join ", "
            (for [[x c] (sort-by (comp str key) (frequencies xs))]
              (str (if x (name x) "none") " " c))))

(defn report
  "The markdown summary of a batch: per goal, the pass line, a table of runs, the outcome
   tally and the tally of the bot's reasons."
  [rows]
  (str/join "\n"
            (for [[goal rs] (sort-by (comp name key) (group-by :gym/goal rows))
                  :let [rs (sort-by :gym/run rs)]]
              (str/join "\n"
                        (concat [(str "### " (name goal)) "" (summary-line rs) ""
                                 "| run | landing | outcome | s | reason | truth |"
                                 "|---|---|---|---|---|---|"]
                                (map row-line rs)
                                ["" (str "outcomes: " (tally (map :gym/outcome rs)))
                                 (str "reasons: " (tally (map :gym/reason rs))) ""])))))

(defn plan-matrix
  "The trial matrix for goals x runs as JSON, {\"include\": [{\"goal\", \"run\", \"minutes\"}]}.
   Only goals (a row's :gym/name, else its --until name), run numbers and the job's minutes cross
   the job boundary; each trial recomputes the rest from the registry."
  [goals runs]
  (str "{\"include\":["
       (str/join ","
                 (for [g goals
                       :let [row (gym g)]
                       run (range 1 (inc runs))]
                   (str "{\"goal\":\""
                        (:gym/name row (:gym/until row))
                        "\",\"run\":"
                        run
                        ",\"minutes\":"
                        (+ trial-overhead-minutes (quot (:gym/timeout-ms row) 60000))
                        "}")))
       "]}"))

;;;; I/O: RCON, files, stdout ;;;;

(defmulti land!
  "Put the bot where row starts, given this run's landing [x z y] of the set: build the row's
   arena and teleport it there. Returns the landing its truths are filled in with. Keyed by
   :gym/land (:surface when absent); a new kind of start is a defmethod."
  {:arglists '([rc row name landing])}
  (fn [_rc row _name _landing] (:gym/land row :surface)))

(defmethod land! :surface
  ;; the row's arena round the landing, the teleport onto its surface, the spawn pinned there
  [rc row name [x z y :as landing]]
  (when-let [cmds (seq (:gym/setup row))]
    (fixture/with-forceload rc
                            [(- x arena-reach) (- z arena-reach) (+ x arena-reach)
                             (+ z arena-reach)]
                            #(doseq [c cmds] (rcon/command! rc (fill-in c name landing)))))
  (fixture/teleport! rc name [x z])
  (rcon/command! rc (str "spawnpoint " name " " x " " y " " z))
  landing)

(defn wait-loaded-in!
  "Block until the server reports every column of the square [x0 z0 x1 z1] loaded in dim (its
   corners), polling every 200 ms, up to 30 s; true when they loaded."
  [rc dim [x0 z0 x1 z1]]
  (let [corners [[x0 z0] [x0 z1] [x1 z0] [x1 z1]]]
    (loop [i 0]
      (cond (every? (fn [[x z]]
                      (fixture/passed?
                       (rcon/command! rc (in-dimension dim (str "execute if loaded " x " 0 " z)))))
                    corners)
            true
            (>= i 150) false
            :else (do (Thread/sleep 200) (recur (inc i)))))))

(defn place!
  "Build cmds (fill-in templates) round at [x z y] in the dimension dim and stand the bot named
   name on at: the square r round it forceloaded and waited for (a box that crosses a chunk
   edge fills nothing in an unloaded chunk), the commands run, the bot teleported to the middle
   of at's cell, the forceload released after (the bot holds its chunks itself then). Returns at."
  [rc dim name [x z y :as at] r cmds]
  (let [square [(- x r) (- z r) (+ x r) (+ z r)]
        where (str/join " " square)]
    (rcon/command! rc (in-dimension dim (str "forceload add " where)))
    (try (when-not (wait-loaded-in! rc dim square)
           (throw (ex-info "the arena's chunks never loaded" {:dimension dim :at at})))
         (doseq [c cmds] (rcon/command! rc (in-dimension dim (fill-in c name at))))
         (cli/log "tp:"
                  (rcon/command! rc (in-dimension dim (str "tp " name " " x ".5 " y " " z ".5"))))
         (finally (rcon/command! rc (in-dimension dim (str "forceload remove " where)))))
    at))

(defn food-level
  "The bot's food as the server holds it (data get entity <name> foodLevel), or nil when the
   reply says none."
  [rc name]
  (some->> (rcon/command! rc (str "data get entity " name " foodLevel"))
           (re-find #"data: (\d+)")
           second
           parse-long))

(defmethod land! :hungry
  ;; the surface landing, then hunger at its strongest until the server says the food is under
  ;; hungry-food (at most 30 s), then no more: a bot told to go while still fed is done at once,
  ;; and the effect takes a point every three ticks at best, slower than the go comes
  [rc row name landing]
  (let [landing ((get-method land! :surface) rc row name landing)]
    (rcon/command! rc (str "effect give " name " minecraft:hunger 30 255"))
    (try (loop [i 0]
           (when (and (< i 150) (>= (or (food-level rc name) food/max-food) hungry-food))
             (Thread/sleep 200)
             (recur (inc i))))
         (finally (rcon/command! rc (str "effect clear " name " minecraft:hunger"))))
    landing))

(defmethod land! :nether-fortress
  ;; the fortress nearest the landing's Nether coordinates (an eighth of the overworld's, so each
  ;; landing has its own), a pocket of netherrack fortress-offset blocks west of it at pad-y
  ;; (fortress-pad), and the bot in it
  [rc _row name [x z _]]
  (let [reply (rcon/command! rc
                             (str "execute in minecraft:the_nether positioned "
                                  (quot x 8)
                                  " 64 "
                                  (quot z 8)
                                  " run locate structure minecraft:fortress"))
        [fx fz] (or (fixture/parse-located reply)
                    (throw (ex-info "no fortress located" {:reply reply})))]
    (place! rc dimension/nether name [(- fx fortress-offset) fz pad-y] 2 fortress-pad)))

(defmethod land! :nether-arena
  ;; a sealed room (nether-arena) over the landing's Nether coordinates (an eighth of the
  ;; overworld's, so each landing has its own) at pad-y, the row's setup built in it, the bot on
  ;; its floor
  [rc row name [x z _]]
  (place! rc
          dimension/nether
          name
          [(quot x 8) (quot z 8) pad-y]
          arena-half
          (into nether-arena (:gym/setup row))))

(defmethod land! :end
  ;; the main island (issue #261), ruststeve's End start: the island round the fountain loaded,
  ;; the bot on the surface at end-at (its first arrival starts the dragon fight, crystals and
  ;; all), then the row's setup round where it stands. Every landing of the set starts here: the
  ;; End has one main island. No portal and no stronghold: the rows past them start in the End
  [rc row name _]
  (let [dim dimension/end
        [x z] end-at
        square [(- end-reach) (- end-reach) end-reach end-reach]
        where (str/join " " square)]
    (rcon/command! rc (in-dimension dim (str "forceload add " where)))
    (try (when-not (wait-loaded-in! rc dim square)
           (throw (ex-info "the End's chunks never loaded" {:square square})))
         (cli/log "tp:"
                  (rcon/command!
                   rc
                   (in-dimension dim (fixture/on-surface x z (str "tp " name " ~0.5 ~ ~0.5")))))
         (let [[_ y _] (or (fixture/position rc name)
                           (throw (ex-info "the bot left the world" {:name name})))
               at [x z (long (math/floor y))]]
           (doseq [c (:gym/setup row)] (rcon/command! rc (in-dimension dim (fill-in c name at))))
           at)
         (finally (rcon/command! rc (in-dimension dim (str "forceload remove " where)))))))

(defn wait-in!
  "Block until the server says the bot named name stands in the dimension dim (dimension-test),
   polling every 250 ms, up to dimension-wait-ms; true when it does."
  [rc name dim]
  (let [until (+ (System/currentTimeMillis) dimension-wait-ms)]
    (loop []
      (cond (fixture/passed? (rcon/command! rc (dimension-test name dim))) true
            (> (System/currentTimeMillis) until) false
            :else (do (Thread/sleep 250) (recur))))))

(defn landing!
  "The fixture's work for one run: wait for the bot, set the row's difficulty, reset it
   (survival, empty inventory), land it (land!: on this run's landing of the set, or where the
   row starts from it), and only once the server says it stands in the row's dimension give
   its prerequisites (kit) and summon what it meets (summons). Returns what happened:
   {:gym/landed? true :gym/landing :gym/at :gym/started}, or {:gym/landed? false :gym/error
   ...}; never throws. A bot that never reaches the dimension is a failed landing, never a
   kit given in the wrong world (issue #261)."
  [{:keys [goal run name host rcon-port rcon-pass landings]}]
  (let [row (gym goal)
        landing (landings/landing-for (landings/read-landings landings) (parse-long (str run)))
        started (System/currentTimeMillis)]
    (try (rcon/with-rcon
          (or host "127.0.0.1")
          (parse-long (str rcon-port))
          rcon-pass
          (fn [rc]
            (when-not (fixture/wait-online! rc name fixture/online-timeout)
              (throw (ex-info "bot never came online" {:name name})))
            (when-let [d (:gym/difficulty row)] (rcon/command! rc (str "difficulty " d)))
            (rcon/command! rc (str "gamemode survival " name))
            (rcon/command! rc (str "clear " name))
            (let [landing (land! rc row name landing)
                  dim (dimension-of row)]
              (when-not (wait-in! rc name dim)
                (throw (ex-info "the bot never reached its dimension" {:name name :dimension dim})))
              (doseq [c (concat (kit row name) (summons row name landing))] (rcon/command! rc c))
              (Thread/sleep (long fixture/settle-ms))
              {:gym/landed? true
               :gym/landing landing
               :gym/at (fixture/position rc name)
               :gym/started started})))
         (catch Exception e
           (cli/log "landing failed:" e)
           {:gym/landed? false :gym/landing landing :gym/error (str e) :gym/started started}))))

(defn tee-output!
  "Read the bot process p's output as it is written, a whole line at a time: copy each to log
   (for people and the artifacts) and deliver the value of the RESULT line to result (a promise),
   or nil when the output ends without one. The bot's answer comes back through the pipe its
   :go went down, never through a file another process is writing: polling the log, live
   iron-kit run 19's judge read its 44 KB RESULT half written and failed the trial on the
   reader's error. Runs on a thread of its own; returns it."
  [^Process p log result]
  (doto (Thread. ^Runnable
                 (fn []
                   (try (with-open [r (io/reader (.getInputStream p))
                                    w (io/writer log)]
                          (doseq [line (line-seq r)]
                            (.write w (str line "\n"))
                            (.flush w)
                            (when-let [v (result-line line)] (deliver result v))))
                        (finally (deliver result nil)))))
    (.setDaemon true)
    (.start)))

(defn judge!
  "Judge one run: wait for the bot's RESULT (result, a promise tee-output! delivers), then read what the fixture left (a missing landed
   file is a landing that never happened), ask the server each truth command while the bot
   still holds, write the result row and print it as one GYMRESULT line. Returns the row."
  [{:keys [goal run name host rcon-port rcon-pass result landed out wait-ms commit]}]
  (let [row (gym goal)
        result (deref result
                      (if wait-ms (parse-long wait-ms) (+ (:gym/timeout-ms row) judge-grace-ms))
                      nil)
        left (if (.exists (io/file landed))
               (edn/read-string (slurp landed))
               {:gym/landed? false :gym/error "no landed file"})
        truths (when result
                 (try (rcon/with-rcon (or host "127.0.0.1")
                                      (parse-long (str rcon-port))
                                      rcon-pass
                                      (fn [rc]
                                        (mapv #(let [c (truth-cmd % name (:gym/landing left))]
                                                 (truth % c (rcon/command! rc c) result))
                                              (:gym/truth row))))
                      (catch Exception e (cli/log "truth failed:" e) [])))
        judged-row (judged (assoc left
                                  :gym/goal (:gym/goal row)
                                  :gym/run (parse-long (str run))
                                  :gym/result result
                                  :gym/truths truths
                                  :gym/commit commit
                                  :gym/ms (- (System/currentTimeMillis) (:gym/started left 0))))]
    (spit out (pr-str judged-row))
    (prn 'GYMRESULT (dissoc judged-row :gym/result))
    (flush)
    judged-row))

(defn attempt!
  "One bot on a running server: start it as its own JVM (classpath cp, in bot-dir), land it
   (landing!) and send its :go through its stdin (none for a fixture row), judge it from its
   log and the server (judge!), and leave result.edn, landed.edn, bot.log and run.edn in dir.
   Returns the result row with tags merged in (the plan's experiment, arm and unit)."
  [{:keys [goal run name dir cp bot-dir port rcon-port pass landings commit tags]
    :or {bot-dir "." port 25565 rcon-port 25575}}]
  (.mkdirs (io/file dir))
  (let [row (gym goal)
        bot-log (io/file dir "bot.log")
        cmd ["java" bot-heap "-cp" cp "clojure.main" "-m" "clojurecraft.main" "--host" "127.0.0.1"
             "--port" (str port) "--name" name "--until" (:gym/until row) "--events" "stdin"
             "--timeout-ms" (str (:gym/timeout-ms row)) "--hold-ms" (str hold-ms) "--record"
             (str (io/file dir "run.edn"))]
        ^java.util.List cmd (cond-> cmd commit (into ["--commit" commit])) ; the recording names its code
        p (.start
           (doto (ProcessBuilder. cmd) (.directory (io/file bot-dir)) (.redirectErrorStream true)))
        result (promise)
        tee (tee-output! p bot-log result)
        landed
        (landing!
         {:goal goal :run run :name name :rcon-port rcon-port :rcon-pass pass :landings landings})
        landed-file (io/file dir "landed.edn")]
    (spit landed-file (pr-str landed))
    (cli/log "landed" (pr-str landed))
    (with-open [w (io/writer (.getOutputStream p))]
      (when-not (:gym/fixture? row) ; a fixture row plays nothing: a :go with no goals is no event
        (.write w (str (pr-str (fixture/go-event (:gym/until row) (:gym/at landed))) "\n"))))
    (let [tagged (merge (judge! {:goal goal
                                 :run run
                                 :name name
                                 :rcon-port rcon-port
                                 :rcon-pass pass
                                 :result result
                                 :landed (str landed-file)
                                 :out (str (io/file dir "result.edn"))
                                 :commit commit})
                        tags)]
      (.destroy p)
      (.waitFor p)
      (.join ^Thread tee 5000) ; the log whole before it is uploaded
      (spit (io/file dir "result.edn") (pr-str tagged))
      tagged)))

(defn trial!
  "One trial: an attempt!, and when it lost its connection (which says nothing about the goal)
   one more on the same landing, under dir/retry. A trial that throws leaves a :harness row,
   never nothing. Returns the row that counts."
  [{:keys [goal run dir tags] :as t}]
  (try
    (let [row (attempt! t)]
      (if (= :disconnect (:gym/outcome row)) (attempt! (assoc t :dir (io/file dir "retry"))) row))
    (catch Exception e
      (cli/log "trial failed:" e)
      (let [row (merge {:gym/goal (:gym/goal (gym goal))
                        :gym/run run
                        :gym/outcome :harness
                        :gym/reason :harness-error
                        :gym/error (ex-message e)}
                       tags)]
        (.mkdirs (io/file dir))
        (spit (io/file dir "result.edn") (pr-str row))
        row))))

(defn located-in
  "Every result file under dir whose name is in names, read, with the directory it came from:
   [{:row :path}]. A retried trial keeps only its retry: the first attempt lost its connection
   and says nothing about the goal."
  [dir names]
  (let [files (filter #(names (.getName ^java.io.File %)) (file-seq (io/file dir)))
        retried (set (for [^java.io.File f files
                           :when (= "retry" (.getName (.getParentFile f)))]
                       (str (.getParentFile (.getParentFile f)))))]
    (vec (for [^java.io.File f files
               :let [d (str (.getParentFile f))]
               :when (not (retried d))]
           {:row (edn/read-string (slurp f)) :path d}))))

(defn results-in
  "Every result.edn under dir that counts, read (a retried trial's retry, not its first attempt)."
  [dir]
  (mapv :row (located-in dir #{"result.edn"})))

(defn -main
  "clojure -M:gym <command> --key value ...
     plan     --goals wood,table --runs N       the trial matrix as JSON
     landings --set A --plan ci/gym.edn --rcon-port P --rcon-pass S --out landings.edn
     trial    --goal wood --run 3 --name N --rcon-pass S --landings f --dir trial
              [--port 25565] [--rcon-port 25575] [--commit sha] [--strict true]
     report   --dir artifacts"
  [command & args]
  (let [{:keys [goals runs goal run set plan port rcon-port rcon-pass host out dir strict landings
                commit]
         :as opts}
        (cli/options args)]
    (case command
      "plan" (println (plan-matrix (str/split goals #",") (parse-long runs)))
      "landings"
      (let [ls (rcon/with-rcon (or host "127.0.0.1")
                               (parse-long (str rcon-port))
                               rcon-pass
                               #(landings/landings! % (landings/read-set plan set)))]
        (spit out (pr-str ls))
        (println (count ls) "landings in set" set))
      "trial"
      (let [{:gym/keys [outcome]} (trial! {:goal goal
                                           :run (parse-long run)
                                           :name (:name opts)
                                           :dir (or dir "trial")
                                           :cp (cli/classpath! ".")
                                           :port (parse-long (or port "25565"))
                                           :rcon-port (parse-long (or rcon-port "25575"))
                                           :pass rcon-pass
                                           :landings landings
                                           :commit commit})]
        (shutdown-agents)
        (System/exit (if (and (= "true" strict) (strict-failures outcome)) 1 0)))
      "report" (println (report (results-in dir))))
    (shutdown-agents)))
