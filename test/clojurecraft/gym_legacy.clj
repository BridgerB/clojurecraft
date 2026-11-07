(ns clojurecraft.gym-legacy
  "The gym registry and its helpers exactly as gym.clj wrote them in Clojure (rf/phase5 at 879c015), kept only so gym-test can show gyms.edn expands to the same rows. Delete this file once that test has been green on main."
  (:require [clojurecraft.end :as end]
            [clojurecraft.land :refer [at-offset]]))

(set! *warn-on-reflection* true)

(def trial-ms 18000000)                 ; every trial's cap: 5 hours, a plan job's whole run less setup. A trial ends when the bot finishes or its plan fails; a cap shorter than the job only hides how slow a stage is

(defn summon
  "The command that summons entity (a name: \"zombie\") at the landing moved by d [dx dy dz],
   with nbt (SNBT) when given: a row's :gym/summon entry."
  ([entity d] (summon entity d nil))
  ([entity d nbt] (str "summon minecraft:" entity " " (at-offset d) (when nbt (str " " nbt)))))

(defn no-ai-zombie
  "A zombie that never moves or attacks (NoAI) and never despawns, at d: the first thing a fight row
   kills."
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

(defn slow-blaze-spawner
  "A blaze spawner at d that makes one blaze at a time, every three to six seconds, two at most near
   it."
  [d]
  (str
   "setblock "
   (at-offset d)
   " minecraft:spawner{SpawnData:{entity:{id:\"minecraft:blaze\"}},SpawnCount:1,MaxNearbyEntities:2,RequiredPlayerRange:16,SpawnRange:2,MinSpawnDelay:60,MaxSpawnDelay:120}"))

(defn endermen
  "n endermen at d that never despawn: the pearls' other source, the End's crowd."
  [n d]
  (vec (repeat n (summon "enderman" d "{PersistenceRequired:1b}"))))

(def dragon
  "A dragon of known state at the fountain: any other gone first, the exit portal its death opens
   shut again, then one summoned with full health and DragonPhase 0 (circling). A summoned dragon
   has no fight manager and starts in phase 10, hovering, so the siblings set both (ruststeve
   gym.rs, steve gym/registry.ts)."
  ["kill @e[type=minecraft:ender_dragon]"
   "fill -2 55 -2 2 70 2 minecraft:air replace minecraft:end_portal"
   "summon minecraft:ender_dragon 0 80 0 {DragonPhase:0,Health:200f}"])

(defn crystals
  "End crystals at each of ds (offsets from the landing), every other crystal gone first: a
   crystal row knows exactly what it must break."
  [ds]
  (into ["kill @e[type=minecraft:end_crystal]"]
        (map #(summon "end_crystal" % "{ShowBottom:0b}"))
        ds))

(def no-crystals "Kills every end crystal loaded." "kill @e[type=minecraft:end_crystal]")

(defn spike-tops
  "The fills that take blocks of kind (a name) off the ten spikes' tops round each one's middle
   (end/spikes, the same as the game's)."
  [kind]
  (vec (for [{[x z] :spike/center} end/spikes]
         (str "fill " (- x 2)
              " 60 " (- z 2)
              " " (+ x 2)
              " 110 " (+ z 2)
              " minecraft:air replace minecraft:" kind))))

(def spike-cages
  "A :gym/setup, run with the island forceloaded: every crystal gone (a crystal lights fire in its
   own cell each tick, a block over its bedrock), then the iron cages and the fire taken off the
   spikes' tops, so a crystal summoned on top stands on its bedrock."
  (into [no-crystals] (concat (spike-tops "iron_bars") (spike-tops "fire"))))

(def spike-crystals
  "Summons: the dragon and every crystal gone again (the setup's kill misses one whose entities
   have not loaded yet: gym dragon-a's killed five of ten), the fire off the tops again, then a
   crystal on each spike's top, the highest block of its middle column, its bedrock: where the
   dragon fight puts them. An integer x and z a positioned names is a block's middle already;
   dragon-a's ~0.5 put every crystal on the corner of its column."
  (into ["kill @e[type=minecraft:ender_dragon]" no-crystals]
        (concat (spike-tops "fire")
                (for [{[x z] :spike/center} end/spikes]
                  (str "execute positioned " x
                       " 0 " z
                       " positioned over world_surface run summon minecraft:end_crystal ~ ~ ~"
                       " {ShowBottom:0b}")))))

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

(def race-armour
  "The armour the race bot makes (gear.clj): iron helmet, chestplate and leggings, no boots."
  (vec (take 3 iron-armour)))

(def no-fire
  "A :gym/summon entry: the blazes' fireballs set no blocks alight (SmallFireball places fire only
   when mob griefing is on). A fire lights the cells round it past 11, and a spawner's blaze spawns
   only where the light is 11 or less. The bot itself still burns when hit."
  "gamerule mob_griefing false")

(defn rods-held
  "A truth that the bot holds n blaze rods or more (n under 10)."
  [n]
  {:truth/cmd "clear {name} minecraft:blaze_rod 0" :truth/re (str "Found ([" n "-9]|[1-9][0-9]) ")})

(defn no-ai-enderman
  "An enderman that never moves, attacks or teleports (NoAI) and never despawns, at d: the first
   thing the pearl rows kill."
  [d]
  (summon "enderman" d "{NoAI:1b,PersistenceRequired:1b}"))

(def pearl-kit
  "What a pearl row gives: an iron sword, two iron pickaxes for the burrows the hunt fights from,
   the iron helmet and chestplate #259's armour row makes (put on by the :wear-armour row), and food
   for the fights' hunger: what a chained run carries by then. Two pickaxes because the chain's
   :cast-one and :portal rows keep a spare (:goal/spares, #485), so a run comes into the Nether
   holding two, and because one is 250 digs."
  ["minecraft:iron_sword 1" "minecraft:iron_pickaxe 2" "minecraft:iron_helmet 1"
   "minecraft:iron_chestplate 1" "minecraft:cooked_beef 16"])

(def pearl-arena
  "The live pearl row's arena: a block of stone 27 square from eight under the landing to six over
   it, the room 25 square and seven high carved out of it (a floor eight deep, a wall one thick and
   seven high round it), open to a clear midnight sky, light blocks five over the floor: the burrow
   is cut in stone, the endermen stand ten blocks off, far enough to be provoked by a stare
   (pearls/stare-from) and never by accident, no sun or rain on them (either sends an enderman off),
   and no dark for a zombie to spawn in (a monster spawns only at block light 0). The sealed Nether
   room is seven square. The wall: the lit square's light reaches ten blocks past its edge and no
   farther, the dark beyond spawns zombies, skeletons and creepers at midnight, and they walked in,
   over the row's 300 s, to the burrow's stairs. Walled, nothing walks in, no arrow has a line, and
   the endermen, which teleport only in daylight or when hurt by what is not alive, stay on the lit
   square. No roof: a surface landing teleports onto the highest motion-blocking block, which a
   light block is not."
  ["time set midnight" "weather clear"
   "fill {x-13} {y-8} {z-13} {x+13} {y+6} {z+13} minecraft:stone"
   "fill {x-12} {y} {z-12} {x+12} {y+6} {z+12} minecraft:air"
   "fill {x-12} {y+5} {z-12} {x+12} {y+5} {z+12} minecraft:light[level=15]"])

(defn pearls-held
  "A truth that the bot holds n or more ender pearls (re, a pattern of the count: \"[1-9]\")."
  [re]
  {:truth/cmd "clear {name} minecraft:ender_pearl 0" :truth/re (str "Found " re " ")})

(def portal-arena
  "The portal rows' arena round the landing: stone ten deep under air, and a trench of lava sources
   one wide set into its surface east of the landing, every source with stone beside it to refill
   from (a scoop takes a source for good)."
  ["fill {x-8} {y-10} {z-8} {x+16} {y-1} {z+16} minecraft:stone"
   "fill {x-8} {y} {z-8} {x+16} {y+14} {z+16} minecraft:air"
   "fill {x+14} {y-1} {z-6} {x+14} {y-1} {z+6} minecraft:lava"])

(def portal-kit
  "What a portal row gives: two buckets of lava, one of water, an empty one, a handful of
   cobblestone (a cast cut into the rock places four, the walls its own holes opened), flint and
   steel, and the stone pickaxe the rows hold for the digs."
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

(defn piece
  "The fill-in placeholders for cell [px py pz] of the stronghold's portal room piece
   (StrongholdPieces.PortalRoom, 11 by 8 by 16): the bot lands at its (5, 1, 2), on the room's
   floor inside its door, so the piece's origin is [-5 -1 -2] from the landing."
  [[px py pz]]
  (at-offset [(- px 5) (dec py) (- pz 2)]))

(defn piece-fill
  "The fill command from piece cell a to piece cell b with block (a name, with its states and
   fill mode as the command takes them)."
  [a b block]
  (str "fill " (piece a) " " (piece b) " minecraft:" block))

(def portal-room
  "The vanilla portal room round the landing, block for block from StrongholdPieces.PortalRoom: a
   hollow box of stone bricks, the staircase of three stairs blocks up to the frames' height with
   the spawner in the middle of its top step (a bare spawner spawns nothing), the platform with the
   3x3 lava pit, the two lava troughs by the door, and the twelve frames floating two over the
   floor, each facing inward as the game's own do (the northern three face south:
   EndPortalFrameBlock's pattern takes no other facing). The iron bars are left out: nothing need
   look in."
  [(piece-fill [0 0 0] [10 7 15] "stone_bricks hollow") (piece-fill [4 1 5] [6 1 7] "stone_bricks")
   (piece-fill [4 2 6] [6 2 7] "stone_bricks") (piece-fill [4 3 7] [6 3 7] "stone_bricks")
   (piece-fill [4 1 4] [6 1 4] "stone_brick_stairs[facing=south]")
   (piece-fill [4 2 5] [6 2 5] "stone_brick_stairs[facing=south]")
   (piece-fill [4 3 6] [6 3 6] "stone_brick_stairs[facing=south]")
   (piece-fill [3 1 8] [7 1 12] "stone_bricks") (piece-fill [4 1 9] [6 1 11] "lava")
   (piece-fill [1 1 1] [1 1 3] "lava") (piece-fill [9 1 1] [9 1 3] "lava")
   (piece-fill [4 3 8] [6 3 8] "end_portal_frame[facing=south]")
   (piece-fill [4 3 12] [6 3 12] "end_portal_frame[facing=north]")
   (piece-fill [3 3 9] [3 3 11] "end_portal_frame[facing=east]")
   (piece-fill [7 3 9] [7 3 11] "end_portal_frame[facing=west]")
   (str "setblock " (piece [5 3 6]) " minecraft:spawner")])

(def gyms
  "The gym registry. Truth commands count items without removing any (`clear <name> <item> 0`
   answers \"Found N matching item(s)...\"); {name} is the bot's name; patterns are strings so
   the table stays EDN."
  [{:gym/goal :wood
    :gym/until "wood"
    :gym/prereqs []
    :gym/timeout-ms trial-ms
    :gym/truth [{:truth/cmd "clear {name} #minecraft:logs 0" :truth/re "Found [1-9]"}]}
   {:gym/goal :kit
    :gym/until "table"
    :gym/prereqs []
    :gym/timeout-ms trial-ms
    :gym/truth [{:truth/cmd "clear {name} minecraft:crafting_table 0" :truth/re "Found [1-9]"}
                {:truth/cmd "clear {name} minecraft:stick 0" :truth/re "Found ([4-9]|[1-9][0-9])"}]}
   {:gym/goal :pickaxe
    :gym/until "pickaxe"
    :gym/prereqs []
    :gym/timeout-ms trial-ms
    :gym/truth [{:truth/cmd "clear {name} minecraft:wooden_pickaxe 0" :truth/re "Found [1-9]"}]}
   ;; the pickaxe is given, so the row measures stone, not the wood and crafting it already measures
   {:gym/goal :cobblestone
    :gym/until "cobblestone"
    :gym/prereqs ["minecraft:wooden_pickaxe 1"]
    :gym/timeout-ms trial-ms
    :gym/truth [{:truth/cmd "clear {name} minecraft:cobblestone 0"
                 :truth/re "Found ([3-9]|[1-9][0-9])"}
                {:truth/cmd "clear {name} minecraft:wooden_pickaxe 0" :truth/re "Found [1-9]"}]}
   ;; the wooden pickaxe, sticks and a table are given, so the row measures stone and the table craft
   {:gym/goal :stone-pickaxe
    :gym/until "stone-pickaxe"
    :gym/prereqs ["minecraft:wooden_pickaxe 1" "minecraft:stick 2" "minecraft:crafting_table 1"]
    :gym/timeout-ms trial-ms
    :gym/truth [{:truth/cmd "clear {name} minecraft:stone_pickaxe 0" :truth/re "Found [1-9]"}]}
   ;; The furnace: a placed block is not in the inventory, so the server's word is that the
   ;; cobblestone was spent and no furnace is held; the bot's RESULT needs the placed block seen.
   {:gym/goal :furnace
    :gym/until "furnace"
    :gym/prereqs ["minecraft:cobblestone 8" "minecraft:crafting_table 1"]
    :gym/timeout-ms trial-ms
    :gym/truth [{:truth/cmd "clear {name} minecraft:cobblestone 0" :truth/re "No items were found"}
                {:truth/cmd "clear {name} minecraft:furnace 0" :truth/re "No items were found"}]}
   {:gym/goal :ingots
    :gym/until "ingots"
    :gym/prereqs ["minecraft:furnace 1" "minecraft:raw_iron 4" "minecraft:coal 1"]
    :gym/timeout-ms trial-ms
    :gym/truth [{:truth/cmd "clear {name} minecraft:iron_ingot 0"
                 :truth/re "Found ([4-9]|[1-9][0-9])"}]}
   {:gym/goal :bucket
    :gym/until "bucket"
    :gym/prereqs ["minecraft:iron_ingot 3" "minecraft:crafting_table 1"]
    :gym/timeout-ms trial-ms
    :gym/truth [{:truth/cmd "clear {name} minecraft:bucket 0" :truth/re "Found [1-9]"}]}
   {:gym/goal :water-bucket
    :gym/until "water-bucket"
    :gym/prereqs ["minecraft:bucket 1" "minecraft:stone_pickaxe 2"]
    :gym/timeout-ms trial-ms
    :gym/truth [{:truth/cmd "clear {name} minecraft:water_bucket 0" :truth/re "Found [1-9]"}]}
   ;; a gravel is given, so the row measures digging it again for flint, not finding gravel
   {:gym/goal :flint-and-steel
    :gym/until "flint-and-steel"
    :gym/prereqs ["minecraft:iron_ingot 1" "minecraft:gravel 1"]
    :gym/timeout-ms trial-ms
    :gym/truth [{:truth/cmd "clear {name} minecraft:flint_and_steel 0" :truth/re "Found [1-9]"}]}
   ;; the whole stage from what #9 ends with, as the sim's end-to-end run starts. Forty minutes:
   ;; down to y 16, iron, coal, gravel and the smelts there, then the stairs back up to water.
   {:gym/goal :iron-kit
    :gym/until "iron-kit"
    :gym/prereqs ["minecraft:stone_pickaxe 1" "minecraft:crafting_table 1" "minecraft:cobblestone 8"
                  "minecraft:oak_log 4"]
    :gym/timeout-ms trial-ms
    :gym/truth [{:truth/cmd "clear {name} minecraft:water_bucket 0" :truth/re "Found [1-9]"}
                {:truth/cmd "clear {name} minecraft:flint_and_steel 0" :truth/re "Found [1-9]"}]}
   {:gym/goal :cast-one
    :gym/until "cast-one"
    :gym/prereqs portal-kit
    :gym/setup portal-arena
    :gym/timeout-ms trial-ms
    :gym/truth [(block-count "minecraft:obsidian" "cloned [1-9]")]}
   {:gym/goal :portal
    :gym/until "portal"
    :gym/prereqs portal-kit
    :gym/setup portal-arena
    :gym/timeout-ms trial-ms
    :gym/truth [(block-count "minecraft:obsidian" "cloned (1[0-9]|[2-9][0-9]) block")
                (block-count "minecraft:nether_portal" "cloned [1-9]")]}
   {:gym/goal :nether
    :gym/until "nether"
    :gym/prereqs portal-kit
    :gym/setup portal-arena
    :gym/timeout-ms trial-ms
    :gym/truth [{:truth/cmd "data get entity {name} Dimension" :truth/re "the_nether"}]}
   {:gym/goal :fortress
    :gym/until "fortress"
    :gym/land :nether-fortress
    :gym/prereqs ["minecraft:iron_pickaxe 1" "minecraft:cobblestone 64"]
    :gym/timeout-ms trial-ms
    :gym/truth
    [{:truth/cmd "execute as {name} at @s if dimension minecraft:the_nether"
      :truth/re "Test passed"}
     {:truth/cmd
      "execute as {name} at @s run clone ~-6 ~-6 ~-6 ~6 ~6 ~6 ~-6 ~20 ~-6 filtered minecraft:nether_bricks"
      :truth/re "cloned [1-9]"}]}
   {:gym/goal :entities
    :gym/until "entities"
    :gym/prereqs []
    :gym/setup
    ["summon minecraft:pig {x} {y+2} {z} {NoAI:1b,Invulnerable:1b,PersistenceRequired:1b,Health:7.5f}"]
    :gym/timeout-ms trial-ms
    :gym/truth
    [{:truth/cmd
      "execute as {name} at @s run data get entity @e[type=minecraft:pig,limit=1,sort=nearest] Health"
      :truth/re "entity data: {result}f"
      :truth/result [:mobs :pig]}]}
   {:gym/goal :arena-nether
    :gym/until "arena-nether"
    :gym/fixture? true
    :gym/land :nether-arena
    :gym/difficulty "normal"
    :gym/prereqs ["minecraft:iron_sword 1" "minecraft:cooked_beef 8"]
    :gym/summon [(no-ai-zombie [2 0 2])]
    :gym/timeout-ms trial-ms
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
    :gym/timeout-ms trial-ms
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
    :gym/timeout-ms trial-ms
    :gym/truth
    [{:truth/cmd
      "execute as {name} at @s unless block ~ ~ ~ minecraft:water unless block ~ ~1 ~ minecraft:water"
      :truth/re "Test passed"} {:truth/cmd "data get entity {name} OnGround" :truth/re "1b"}]}
   {:gym/goal :eat
    :gym/until "eat"
    :gym/land :hungry
    :gym/prereqs ["minecraft:bread 4"]
    :gym/timeout-ms trial-ms
    :gym/truth [{:truth/cmd "data get entity {name} foodLevel" :truth/re "data: (1[4-9]|20)$"}
                {:truth/cmd "clear {name} minecraft:bread 0" :truth/re "Found [1-3] |No items"}]}
   {:gym/goal :armour
    :gym/until "armour"
    :gym/prereqs ["minecraft:flint_and_steel 1" "minecraft:water_bucket 1" "minecraft:bucket 1"
                  "minecraft:crafting_table 1" "minecraft:iron_ingot 13"]
    :gym/timeout-ms trial-ms
    :gym/truth [{:truth/cmd "execute if items entity {name} armor.head minecraft:iron_helmet"
                 :truth/re "Test passed"}
                {:truth/cmd "execute if items entity {name} armor.chest minecraft:iron_chestplate"
                 :truth/re "Test passed"}]}
   ;; The attacks' spacing is read from the recording. This row and defend run at Normal: the gym
   ;; server is peaceful by default and refuses to summon a monster there
   {:gym/goal :attack
    :gym/until "attack"
    :gym/difficulty "normal"
    :gym/prereqs ["minecraft:stone_sword 1"]
    :gym/setup ["time set noon"
                "summon minecraft:husk {x+3} {y} {z} {NoAI:1b,PersistenceRequired:1b}"]
    :gym/timeout-ms trial-ms
    :gym/truth
    [{:truth/cmd
      "execute positioned {x} {y} {z} unless entity @e[type=minecraft:husk,distance=..24]"
      :truth/re "Test passed"}]}
   ;; Not all three: the row is done when none is within 4, and in gym defend-259a the others
   ;; wandered off while the first was fought. Judged at the landing, never as the bot: execute as
   ;; the bot answered RCON with nothing in every trial of that run
   {:gym/goal :defend
    :gym/until "defend"
    :gym/difficulty "normal"
    :gym/prereqs ["minecraft:iron_sword 1"]
    :gym/setup ["time set midnight"
                "scoreboard objectives add zkills minecraft.killed:minecraft.zombie"
                "item replace entity {name} armor.head with minecraft:iron_helmet"
                "item replace entity {name} armor.chest with minecraft:iron_chestplate"
                "item replace entity {name} armor.legs with minecraft:iron_leggings"
                "item replace entity {name} armor.feet with minecraft:iron_boots"]
    ;; summoned once the bot stands in the landing with its kit, then the settle.
    :gym/summon ["summon minecraft:zombie {x+2} {y} {z} {PersistenceRequired:1b}"
                 "summon minecraft:zombie {x-2} {y} {z+1} {PersistenceRequired:1b}"
                 "summon minecraft:zombie {x} {y} {z-2} {PersistenceRequired:1b}"]
    :gym/timeout-ms trial-ms
    :gym/truth [{:truth/cmd "scoreboard players get {name} zkills" :truth/re "has [1-9]"}
                {:truth/cmd "data get entity {name} Health" :truth/re "data: [1-9]"}]}
   {:gym/goal :blaze-rods
    :gym/name "blaze-1"
    :gym/until "blaze-rods"
    :gym/land :nether-arena
    :gym/difficulty "normal"
    :gym/prereqs ["minecraft:iron_sword 1" "minecraft:cooked_beef 8" "minecraft:blaze_rod 6"]
    :gym/summon (into invulnerable
                      [no-fire (blaze-spawner [3 0 3])
                       (summon "blaze" [-2 1 -2] "{PersistenceRequired:1b}")])
    :gym/timeout-ms trial-ms
    :gym/truth [(rods-held 7)]}
   ;; blaze-7: the same room, all seven rods from its blazes, the bot in iron armour and hurt by
   ;; their fireballs, so the break-off under 10 health and the meal after it are played. Fire
   ;; Resistance, as ruststeve's arena gave ("fire resistance only stops the burn"). The spawner is
   ;; the slow one (slow-blaze-spawner): blaze-7c died to volleys of three blazes at once
   {:gym/goal :blaze-rods
    :gym/name "blaze-7"
    :gym/until "blaze-rods"
    :gym/land :nether-arena
    :gym/difficulty "normal"
    :gym/prereqs ["minecraft:iron_sword 1" "minecraft:cooked_beef 16"]
    :gym/summon (into iron-armour
                      [fire-resistance no-fire (slow-blaze-spawner [3 0 3])
                       (summon "blaze" [-2 1 -2] "{PersistenceRequired:1b}")])
    :gym/timeout-ms trial-ms
    :gym/truth [(rods-held 7)]}
   ;; blaze-fortress-fireproof: from the fortress row's pad 48 west of a located fortress, the
   ;; spawner found in the chunks loaded round it, tunnelled to, camped by, seven rods from its
   ;; blazes. It measures the FIGHT only, not the bot's survival: Fire Resistance (a fireball and
   ;; the burning after it cost nothing, as blaze-7 has it) and four pieces of armour. Its passes
   ;; are not evidence that a race bot lives there: main's two on 2026-10-29. The race rows below
   ;; are the truth. Named blaze-fortress until then, and read as a pass for the race.
   {:gym/goal :blaze-rods
    :gym/name "blaze-fortress-fireproof"
    :gym/until "blaze-rods"
    :gym/land :nether-fortress
    :gym/difficulty "normal"
    :gym/prereqs ["minecraft:iron_pickaxe 1" "minecraft:cobblestone 64" "minecraft:iron_sword 1"
                  "minecraft:cooked_beef 16"]
    :gym/summon (conj iron-armour fire-resistance)
    :gym/timeout-ms trial-ms
    :gym/truth [(rods-held 7)]}
   ;; blaze-fortress-race: the same landing with what the race bot brings (gear.clj), not what
   ;; the gym is generous with: an iron sword, iron helmet, chestplate and leggings, sixteen
   ;; cooked beef, no Fire Resistance. blaze-fortress-fireproof stays as the mechanics check
   {:gym/goal :blaze-rods
    :gym/name "blaze-fortress-race"
    :gym/until "blaze-rods"
    :gym/land :nether-fortress
    :gym/difficulty "normal"
    :gym/prereqs ["minecraft:iron_pickaxe 1" "minecraft:crafting_table 1" "minecraft:stick 8"
                  "minecraft:cobblestone 64" "minecraft:iron_sword 1" "minecraft:cooked_beef 16"]
    :gym/summon race-armour
    :gym/timeout-ms trial-ms
    :gym/truth [(rods-held 7)]}
   ;; blaze-fortress-geared: the same landing with exactly what this branch's gear step makes
   ;; (gear.clj) and its entry gate requires (fortress/fed-for-nether?, four foods): an iron sword,
   ;; iron helmet, chestplate and leggings in the inventory for the :wear-armour row to put on, four
   ;; cooked beef, no Fire Resistance. The honest test of the branch; blaze-fortress-race keeps the
   ;; unarmoured kit it was judged with
   {:gym/goal :blaze-rods
    :gym/name "blaze-fortress-geared"
    :gym/until "blaze-rods"
    :gym/land :nether-fortress
    :gym/difficulty "normal"
    :gym/prereqs ["minecraft:iron_pickaxe 1" "minecraft:cobblestone 64" "minecraft:iron_sword 1"
                  "minecraft:iron_helmet 1" "minecraft:iron_chestplate 1"
                  "minecraft:iron_leggings 1" "minecraft:cooked_beef 4"]
    :gym/timeout-ms trial-ms
    :gym/truth [(rods-held 7)]}
   ;; Five that never move or teleport (NoAI), each within the hunt's strike range of the landing,
   ;; since a kill drops a pearl only one time in two: one pearl is then 31 trials in 32
   {:gym/goal :ender-pearl
    :gym/until "pearls-1"
    :gym/land :nether-arena
    :gym/difficulty "normal"
    :gym/prereqs pearl-kit
    :gym/summon (mapv no-ai-enderman [[2 0 0] [-2 0 0] [0 0 2] [0 0 -2] [2 0 2]])
    :gym/timeout-ms trial-ms
    :gym/truth [(pearls-held "[1-9][0-9]*")]}
   ;; then live: three that fight back for 7 a hit at Normal, ten blocks off in a stone arena at
   ;; midnight (pearl-arena), the bot in the kit's iron helmet and chestplate; a pearl held, the
   ;; bot alive with health left
   {:gym/goal :ender-pearl
    :gym/until "pearls-1"
    :gym/name "pearls-1-live"
    :gym/difficulty "normal"
    :gym/prereqs pearl-kit
    :gym/setup pearl-arena
    :gym/summon (into [] (mapcat #(endermen 1 %)) [[10 0 0] [-10 0 2] [0 0 10]])
    :gym/timeout-ms trial-ms
    :gym/truth [(pearls-held "[1-9][0-9]*")
                {:truth/cmd "data get entity {name} Health" :truth/re "data: [1-9]"}]}
   ;; the stage: twelve pearls from the endermen of the warped forest nearest the landing's
   ;; Nether coordinates, where they spawn by themselves at Normal and nothing else hostile does
   {:gym/goal :ender-pearls
    :gym/until "pearls-12"
    :gym/land :warped-forest
    :gym/difficulty "normal"
    :gym/prereqs (conj pearl-kit "minecraft:cooked_beef 32")
    :gym/timeout-ms trial-ms
    :gym/truth [(pearls-held "(1[2-9]|[2-9][0-9]|[1-9][0-9][0-9])")]}
   {:gym/goal :ender-pearls
    :gym/name "pearls-12-race"
    :gym/until "pearls-12"
    :gym/land :warped-forest
    :gym/difficulty "normal"
    :gym/prereqs (into pearl-kit
                       ["minecraft:cooked_beef 32" "minecraft:cobblestone 64"
                        "minecraft:netherrack 64"])
    :gym/timeout-ms trial-ms
    :gym/truth [(pearls-held "(1[2-9]|[2-9][0-9]|[1-9][0-9][0-9])")]}
   {:gym/goal :ender-pearls
    :gym/name "pearls-12-race-tools"
    :gym/until "pearls-12"
    :gym/land :warped-forest
    :gym/difficulty "normal"
    :gym/prereqs (into pearl-kit
                       ["minecraft:cooked_beef 32" "minecraft:cobblestone 64"
                        "minecraft:netherrack 64" "minecraft:crafting_table 1" "minecraft:stick 8"])
    :gym/timeout-ms trial-ms
    :gym/truth [(pearls-held "(1[2-9]|[2-9][0-9]|[1-9][0-9][0-9])")]}
   ;; No fire resistance: the race's bot has none at this stage
   {:gym/goal :ender-pearls
    :gym/name "pearls-12-from-fortress"
    :gym/until "pearls-12"
    :gym/land :nether-fortress
    :gym/difficulty "normal"
    :gym/prereqs (into pearl-kit ["minecraft:cooked_beef 32" "minecraft:blaze_rod 7"])
    :gym/timeout-ms trial-ms
    :gym/truth [(pearls-held "(1[2-9]|[2-9][0-9]|[1-9][0-9][0-9])")]}
   {:gym/goal :ender-pearls
    :gym/name "pearls-12-from-fortress-open"
    :gym/until "pearls-12"
    :gym/land :nether-fortress-open
    :gym/difficulty "normal"
    :gym/prereqs (into pearl-kit ["minecraft:cooked_beef 32" "minecraft:blaze_rod 7"])
    :gym/timeout-ms trial-ms
    :gym/truth [(pearls-held "(1[2-9]|[2-9][0-9]|[1-9][0-9][0-9])")]}
   ;; Nothing to fight or fetch, so the row measures the 2x2 crafts alone (six of blaze powder,
   ;; twelve of eyes)
   {:gym/goal :eyes-of-ender
    :gym/until "eyes"
    :gym/difficulty "normal"
    :gym/prereqs ["minecraft:blaze_rod 7" "minecraft:ender_pearl 12"]
    :gym/timeout-ms trial-ms
    :gym/truth [{:truth/cmd "clear {name} minecraft:ender_eye 0"
                 :truth/re "Found (1[2-9]|[2-9][0-9]) "}]}
   {:gym/goal :eye-throw
    :gym/until "throw"
    :gym/difficulty "normal"
    :gym/setup ["time set noon"]
    :gym/prereqs ["minecraft:ender_eye 2"]
    :gym/timeout-ms trial-ms
    :gym/truth [{:truth/cmd
                 "execute positioned {x} {y} {z} run locate structure minecraft:stronghold"
                 :truth/re "\\[(-?\\d+), (?:~|-?\\d+), (-?\\d+)\\]"
                 :truth/check :bearing
                 :truth/result [:throws]}]}
   ;; Eyes to spare for the throws a shatter costs; pickaxes for the tunnels where no walk goes; a
   ;; sword, armour and food for the night the walks may run into at Normal
   {:gym/goal :stronghold
    :gym/until "stronghold"
    :gym/land :near-stronghold
    :gym/difficulty "normal"
    :gym/prereqs ["minecraft:ender_eye 6" "minecraft:iron_sword 1" "minecraft:iron_pickaxe 3"
                  "minecraft:iron_helmet 1" "minecraft:iron_chestplate 1"
                  "minecraft:cooked_beef 16"]
    :gym/timeout-ms trial-ms
    :gym/truth [{:truth/cmd "execute if block {at} minecraft:end_portal_frame"
                 :truth/re "Test passed"
                 :truth/at [:frame]}]}
   ;; then down to it: the same landing and kit, cobblestone for the tunnel's floors, judged by a
   ;; frame at the cell the bot names and the bot within 7 of it (at the frame's corner), asked at
   ;; the frame's coordinates, never as the bot: execute as the bot answered RCON with nothing in
   ;; gym defend-259a
   {:gym/goal :portal-room
    :gym/until "portal-room"
    :gym/land :near-stronghold
    :gym/difficulty "normal"
    :gym/prereqs ["minecraft:ender_eye 6" "minecraft:iron_sword 1" "minecraft:iron_pickaxe 3"
                  "minecraft:iron_helmet 1" "minecraft:iron_chestplate 1" "minecraft:cooked_beef 16"
                  "minecraft:cobblestone 64"]
    :gym/timeout-ms trial-ms
    :gym/truth [{:truth/cmd "execute if block {at} minecraft:end_portal_frame"
                 :truth/re "Test passed"
                 :truth/at [:frame]}
                {:truth/cmd "execute positioned {at} if entity @a[name={name},distance=..7]"
                 :truth/re "Test passed"
                 :truth/at [:frame]}]}
   {:gym/goal :end-portal
    :gym/until "end-portal"
    :gym/land :portal-room
    :gym/difficulty "normal"
    :gym/setup portal-room
    :gym/prereqs ["minecraft:ender_eye 14"]
    :gym/timeout-ms trial-ms
    :gym/truth [{:truth/cmd "execute if block {at} minecraft:end_portal"
                 :truth/re "Test passed"
                 :truth/at [:portal]}]}
   ;; then through it: the same room and kit, judged by the server finding the bot in the End
   {:gym/goal :enter-end
    :gym/until "enter-end"
    :gym/land :portal-room
    :gym/difficulty "normal"
    :gym/setup portal-room
    :gym/prereqs ["minecraft:ender_eye 14"]
    :gym/timeout-ms trial-ms
    :gym/truth [{:truth/cmd "execute as {name} at @s if dimension minecraft:the_end"
                 :truth/re "Test passed"}]}
   ;; The bot takes no harm (invulnerable): the End's fight makes a dragon of its own on the bot's
   ;; arrival, after the summons, which no command here can keep away, and gym dragon-b's three runs
   ;; died to its fireballs and its knocks off a pillar, the row measuring the dragon and not the
   ;; crystals (the wall's worth is read off the recording: dragon-b run 1 struck its first crystal
   ;; from behind it for 1 damage)
   {:gym/goal :crystals
    :gym/until "crystals"
    :gym/land :end
    :gym/difficulty "normal"
    :gym/setup spike-cages
    :gym/prereqs ["minecraft:cobblestone 64" "minecraft:cobblestone 64" "minecraft:iron_pickaxe 1"
                  "minecraft:iron_sword 1" "minecraft:cooked_beef 16"]
    :gym/summon (into spike-crystals invulnerable)
    :gym/timeout-ms trial-ms
    :gym/truth [{:truth/cmd
                 "execute in minecraft:the_end unless entity @e[type=minecraft:end_crystal]"
                 :truth/re "Test passed"}]}
   ;; the crystals gone and the End fight's own dragon circling (the bot's arrival in the End starts
   ;; the fight and spawns its dragon twenty ticks on), the bot in iron with a sword, cobblestone
   ;; and a pickaxe for any crystal left; judged by no dragon left in the End
   {:gym/goal :dragon
    :gym/until "dragon"
    :gym/land :end
    :gym/difficulty "normal"
    :gym/setup [no-crystals]
    :gym/prereqs ["minecraft:iron_sword 1" "minecraft:iron_helmet 1" "minecraft:iron_chestplate 1"
                  "minecraft:iron_leggings 1" "minecraft:iron_boots 1" "minecraft:cooked_beef 16"
                  "minecraft:cobblestone 64" "minecraft:iron_pickaxe 1"
                  "minecraft:carved_pumpkin 1"]
    :gym/summon [no-crystals]
    :gym/timeout-ms trial-ms
    :gym/truth [{:truth/cmd
                 "execute in minecraft:the_end unless entity @e[type=minecraft:ender_dragon]"
                 :truth/re "Test passed"}]}
   ;; the speedrunners' kill, measured: the dragon row with six beds and the bot invulnerable
   ;; (Resistance V, Fire Resistance), so the damage a bed deals the perched dragon is read from the
   ;; boss bar (boss-event update-health) before and after each, not the bot's survival; a bed
   ;; explodes in the End and, in the open at 4.5 blocks, would kill the bot
   {:gym/name "dragon-beds"
    :gym/goal :dragon
    :gym/until "dragon"
    :gym/land :end
    :gym/difficulty "normal"
    :gym/setup [no-crystals]
    :gym/prereqs ["minecraft:iron_sword 1" "minecraft:iron_helmet 1" "minecraft:iron_chestplate 1"
                  "minecraft:iron_leggings 1" "minecraft:iron_boots 1" "minecraft:cooked_beef 16"
                  "minecraft:cobblestone 64" "minecraft:iron_pickaxe 1" "minecraft:carved_pumpkin 1"
                  "minecraft:white_bed 6"]
    :gym/summon (into [no-crystals] invulnerable)
    :gym/timeout-ms trial-ms
    :gym/truth [{:truth/cmd
                 "execute in minecraft:the_end unless entity @e[type=minecraft:ender_dragon]"
                 :truth/re "Test passed"}]}
   ;; the same without the Resistance: the bot walls itself from the bed's blast (bed-wall, two
   ;; blocks, beds/expected-damage) and takes what is left; its health is the measure
   {:gym/name "dragon-beds-real"
    :gym/goal :dragon
    :gym/until "dragon"
    :gym/land :end
    :gym/difficulty "normal"
    :gym/setup [no-crystals]
    :gym/prereqs ["minecraft:iron_sword 1" "minecraft:iron_helmet 1" "minecraft:iron_chestplate 1"
                  "minecraft:iron_leggings 1" "minecraft:iron_boots 1" "minecraft:cooked_beef 16"
                  "minecraft:cobblestone 64" "minecraft:iron_pickaxe 1" "minecraft:carved_pumpkin 1"
                  "minecraft:white_bed 6"]
    :gym/summon [no-crystals]
    :gym/timeout-ms trial-ms
    :gym/truth [{:truth/cmd
                 "execute in minecraft:the_end unless entity @e[type=minecraft:ender_dragon]"
                 :truth/re "Test passed"}]}
   {:gym/name "chain"
    :gym/goal :dragon
    :gym/until "dragon"
    :gym/goals [:eyes-of-ender :stronghold :portal-room :end-portal :enter-end :crystals :dragon]
    :gym/land :near-stronghold
    :gym/difficulty "normal"
    :gym/prereqs ["minecraft:blaze_rod 7" "minecraft:ender_pearl 12" "minecraft:iron_sword 1"
                  "minecraft:iron_pickaxe 4" "minecraft:iron_helmet 1" "minecraft:iron_chestplate 1"
                  "minecraft:iron_leggings 1" "minecraft:iron_boots 1" "minecraft:cooked_beef 16"
                  "minecraft:cobblestone 64" "minecraft:cobblestone 64" "minecraft:cobblestone 64"
                  "minecraft:carved_pumpkin 1"]
    :gym/timeout-ms trial-ms
    :gym/truth [{:truth/cmd
                 "execute in minecraft:the_end unless entity @e[type=minecraft:ender_dragon]"
                 :truth/re "Test passed"}]}])
