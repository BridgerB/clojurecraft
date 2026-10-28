---
title: Mobs in the server model
description: How sim.clj spawns, moves, fights and kills mobs by mob.clj's rules - the night rule, straight approach, melee, arrows, the creeper's fuse, the 775 attack with its hurt window, loot - and the fixtures every combat stage's sim tests start from.
type: reference
tags: [bot, tooling, sim, combat]
aliases: [mob.clj, :sim/mobs, :mobs fixture, with-mobs, tick-mobs, attack handler, night rule, day-time]
status: verified
lastUpdated: 2026-10-22
verifiedAgainst: HEAD
sourceRefs:
  - src/clojurecraft/mob.clj#def kinds
  - src/clojurecraft/mob.clj#def warped-kinds
  - src/clojurecraft/mob.clj#defn nether-spot?
  - src/clojurecraft/mob.clj#defn pack-step
  - src/clojurecraft/sim.clj#defn wild-roll
  - src/clojurecraft/sim.clj#defn try-nether-spawn
  - src/clojurecraft/sim.clj#defn spawn-pack
  - src/clojurecraft/mob.clj#defn route
  - src/clojurecraft/mob.clj#defn moves
  - src/clojurecraft/mob.clj#defn toward-aim
  - src/clojurecraft/mob.clj#defn away-aim
  - src/clojurecraft/mob.clj#defn landing
  - src/clojurecraft/sim.clj#defn trail
  - src/clojurecraft/sim.clj#defn blink
  - src/clojurecraft/sim.clj#defn teleport-mob
  - test/clojurecraft/mob_test.clj#an-angry-enderman-far-off-teleports-toward-the-player
  - test/clojurecraft/mob_test.clj#an-angry-enderman-walks-round-a-wall-to-the-player
  - test/clojurecraft/mob_test.clj#a-warped-forest-spawns-packs-of-endermen-by-day-and-netherrack-spawns-nothing
  - test/clojurecraft/mob_test.clj#the-overworld-spends-no-roll-of-the-nethers-stream
  - src/clojurecraft/mob.clj#defn weapon-damage
  - src/clojurecraft/mob.clj#defn blast-damage
  - src/clojurecraft/mob.clj#defn step-toward
  - src/clojurecraft/mob.clj#defn spawn-spots
  - src/clojurecraft/mob.clj#defn stared?
  - src/clojurecraft/mob.clj#defn absorbed
  - src/clojurecraft/sim.clj#defn armoured
  - test/clojurecraft/mob_test.clj#armour-takes-its-share-of-a-mobs-hit
  - src/clojurecraft/sim.clj#defn with-mobs
  - src/clojurecraft/sim.clj#defn retell-entities
  - test/clojurecraft/sim_test.clj#a-respawned-bot-is-told-the-mobs-about-it-again
  - src/clojurecraft/sim.clj#defn tick-mobs
  - src/clojurecraft/sim.clj#defn try-spawn
  - src/clojurecraft/mob.clj#defn spawner-awake?
  - src/clojurecraft/mob.clj#defn spawner-spawns
  - src/clojurecraft/sim.clj#defn tick-spawners
  - src/clojurecraft/sim.clj#defn spawn-batch
  - test/clojurecraft/mob_test.clj#a-spawner-near-the-player-makes-blazes-within-eight-hundred-ticks
  - test/clojurecraft/sim_test.clj#the-seventh-rod-from-a-spawner-in-the-models-nether
  - src/clojurecraft/sim.clj#defn hurt-mob
  - src/clojurecraft/sim.clj#defmethod on-packet [:play :attack]
  - src/clojurecraft/sim.clj#defn populate-column
  - src/clojurecraft/sim.clj#defn mob-roll
  - src/clojurecraft/simrace.clj#defn day-time-of
  - test/clojurecraft/mob_test.clj#an-iron-sword-kills-a-zombie-in-four-hits-and-it-drops-and-goes
  - test/clojurecraft/mob_test.clj#a-creeper-swells-for-a-second-and-a-half-and-blasts
  - test/clojurecraft/mob_test.clj#a-night-spawns-a-hostile-in-the-ring-and-a-day-does-not
  - test/clojurecraft/mob_test.clj#the-bot-killed-by-a-zombie-respawns
related:
  - "[[bot/tooling/_moc|Tooling]]"
  - "[[server-model]]"
  - "[[sim-hunger]]"
  - "[[survival]]"
  - "[[sib-775-attack-packet]]"
  - "[[ender-pearls]]"
---

# Mobs in the server model

The real races run at Normal (#257): night mobs hit the bot and kill it. The model had no mobs, so a sim lane never died to one (issue #260). `mob.clj` holds each kind's numbers as data and the rules as pure functions over a cell lookup, the way `fluid.clj` models water; `sim.clj` keeps the mobs (`:sim/mobs {eid {:type :pos :health ...}}`) and applies the rules every tick (`tick-mobs`). The packets are the ones the bot's mob model reads (#258): `add-entity` (the six-field prefix), `set-entity-data` with `{9 health}`, `move-entity-pos` deltas (or `entity-position-sync` for a fall too long for one), `entity-event` status 3 at a death, `remove-entities` when the body goes.

## The kinds (`mob/kinds`)
Health, hitbox, walking speed in blocks a tick (a zombie's 0.23 attribute walks 2.3 blocks a second), follow range, and how each hurts: zombie and spider melee (3 and 2 on Normal, every 20 ticks, in reach on the plane), skeleton arrows (3 at up to 15 with a clear line, every 40 ticks, standing at 10), creeper fuse (lit within 3, out past 7, 30 ticks, a power-3 blast: 43 at the feet, `blast-damage`), enderman neutral (7, only once hurt or stared at: `stared?` is vanilla's cone and line), blaze fireballs (5 at 16, hovering), animals passive, the dragon perched, a crystal that blasts at power 6 when hit. Loot rows `[item lo hi]` (pearl 0-1, rod 0-1, beef 1-3, rotten flesh 0-2 ...). `scaled` applies the difficulty (easy halves plus one, hard one and a half).

## Spawning and despawning
- The day turns in `:sim/day-time` (given by `:day-time`, a model with a `:difficulty` keeps it); night is 13000-23000 of each 24000. `simrace/day-time-of` starts a run at one of eight times by its seed, so some lanes start at night.
- `try-spawn`, every 100 ticks under the cap of 70 hostiles within 128: a column rolled in the ring 24-48 from the player, its spots within 16 of the player's feet that have solid under them, room for a body and darkness (`mob/roofed?`: a solid block that is not leaves overhead, dark day and night since no torch is modelled; or any spot at night), one rolled, a kind rolled by weight (zombie, skeleton, creeper, spider 100 each, enderman 10). A model's rate, not vanilla's per-chunk rule.
- `tick-spawners` (the Nether only, at a difficulty above peaceful): a blaze spawner block in a captured Nether is a block state like any other, so capture (#353) keeps it. The model finds the spawners in the nine columns round the player whenever it enters a column (`:sim/spawners {cell due-ms}`), and `tick-spawner` runs each as vanilla's BaseSpawner does (`mob/spawner`): awake with the player within 16 of its centre; the first tick awake rolls a delay of 200 to 800 ticks; then a batch of four tries (`spawn-batch`, vanilla's SpawnCount; it was two to four until issue #525's batch, which moves sim replays), cut to the room under its cap of six blazes within four blocks (`spawner-room`, `spawner-crowded?`), each try five rolls to a cell of the 9x3x9 box as BaseSpawner picks it (`spawner-spot`: x and z triangular, (r1 - r2) * 4 from the spawner's cell, y one of three) where a blaze's two cells are free and no other blaze was put (`spawner-fits?`); light is the model's fixed `interior-light` of 0, under the blaze's limit of 11. Rolls come from `mob-roll`, as every other. Blazes are not `:hostile` in `mob/kinds`, so the overworld's night rule never picks one and `despawn` never takes one. A broken spawner stops; `arrive` forgets the list.
- `try-nether-spawn` (the Nether only, at a difficulty above peaceful, `wild-spawning?`; issue #263): vanilla spawns the Nether's hostiles by biome, and a warped forest's monster list is the enderman alone, weight 1, in packs of four (`mob/warped-kinds`, `mob/enderman-pack`). The captured Nether's bytes carry the biome containers but `chunk/read-section` keeps the blocks alone, so the model tells a warped forest by its floor: warped nylium under the feet (`mob/warped-floor?`), as the bot does. Every 100 ticks under the cap, a column is rolled in the same ring as `try-spawn`'s, its spots within 16 of the feet with nylium under them and an enderman's three cells free (`mob/nether-spot?`, `mob/nether-spots`; any light: the model's light is 0 everywhere, under the Nether's `monster_spawn_light_level` of 7, so the shroomlit cells vanilla refuses are not told apart), one rolled, and a pack walked out from it (`spawn-pack`): each member `nextInt(6) - nextInt(6)` off the last on x and on z at the pack's height (`mob/pack-step`), placed where its cell is a spot and 24 or more from the player, skipped where not, as `NaturalSpawner.spawnCategoryForPosition` does. A column with no nylium spawns nothing: the other biomes' hostiles (piglins, hoglins, ghasts, magma cubes, skeletons, striders) are not modelled. The rolls come from the Nether's own stream (`wild-roll`, `:sim/wild-rng`, seeded from `:seed` and `wild-salt`), never `mob-roll`'s, so the overworld's night rule rolls as it did before it (`the-overworld-spends-no-roll-of-the-nethers-stream`: `try-nether-spawn` is the identity on every overworld tick), and a Nether run that meets no warped forest rolls its fortress's spawners as before: snapshots and replays from before keep their luck. Once endermen stand in the model, their turns and despawn rolls come from `mob-roll` like every mob's. The spawned endermen are `:hostile`, so `despawn` takes them as it takes the overworld's, and they are neutral until stared at or hurt (`provoke`, `anger`). `a-warped-forest-spawns-packs-of-endermen-by-day-and-netherrack-spawns-nothing` pins the rule. Probe 39779112668 (probe/263-sim-endermen, the bot with an iron kit put in each set's warped forest at Normal, 60 simulated minutes): every set's forest spawned packs from the first try (R2 reached the cap of 70); in R4 the hunt provoked, killed and picked up six pearls in eight minutes, then failed `:unreachable` waiting on a drop; in R1-R3 it provoked one or two and killed none, most likely as the model's endermen walk straight at the player and neither path round walls nor teleport (below), so one provoked beyond a wall never reaches the burrow's end.
- `despawn`: a hostile not persistent goes at once beyond 128, and by a roll of 1 in 800 a tick beyond 32 (`Mob.checkDespawn`).
- `populate`: each chunk within three of the player gets its animals once (`:sim/populated`): one time in ten a herd of three of one kind on the grass at its middle, persistent, as a server populates a chunk it generates.
- Every roll comes from the mobs' own stream (`mob-roll`, `:sim/mob-rng`), never the loot's, so gravel's flint falls the same with mobs or without.

## A mob's tick (`act-mob`)
A dead one is removed once its death is over (20 ticks, the dragon 200). An undead burns in the overworld's daylight with no roof over its head (1 a second, no loot). Otherwise, with the living player as its target (`targets?`: in follow range, a neutral one only once angry) it walks straight at the player (`mob/step-toward`: the step when the body stands there, one block up when one is in the way, else it stays, then it falls; a body stands where its `mob/tall` cells are free, three for an enderman and two for every other walker, so a tunnel two high keeps an enderman out, issue #263), then hits, shoots or swells by its kind. A kind marked `:paths` (the enderman, zombie, skeleton, creeper and spider) walks round a wall that stops it (below); a kind without it stops at the wall.

### The enderman's way round and its teleports (issue #263)
Probe 39779112668 showed the pearl hunt in R1-R3 provoking endermen that never reached the bot's burrow: a straight walk stops at a wall or stands on the ground over the burrow. Two rules close the gap with vanilla, both for kinds marked in `mob/kinds`. `:teleports` is the enderman alone. `:paths` is the enderman, zombie, skeleton, creeper and spider: real race 40663149575 lane 8, Clj_r14, was chased down by zombies 3719 and 3720 in a cave while the sim gate of the same change (#589) saw no chase, its zombies stopped at the first wall:
- `:paths` (`trail`): while its straight step leaves it where it stood with the player out of its reach (`mob/in-reach?`, `strike`'s test), it searches a route at most every 10 ticks (`mob/route-every`, about `MeleeAttackGoal`'s 4 + nextInt(7) recalculation, unrolled): `mob/route` is a breadth-first search over `mob/moves` (the four side cells at its height where its three cells are free, falling at most 3 to a floor, `Mob.getMaxFallDistance`; one up where the head clears) visiting 1024 cells at most (`mob/route-budget`, the follow range 64 times 16 as vanilla's `PathFinder`), ending at the cell nearest the player (the pathfinder's partial path). It walks the route cell centre by cell centre (`:route`), dropping it when a step makes no way. A tunnel two high still keeps it out: the route ends at the mouth, nearest the bot.
- `:teleports` (`blink`, before the walk, as `EndermanLookForPlayerGoal.tick` runs before the movement): stared at (`mob/stared?`) within 4 it teleports away (`mob/away-aim`, `EnderMan.teleport`: up to 32 off on x and z, -32 to 31 on y); not stared at and farther than 16 it counts 30 ticks (`:far-ticks`, the goal's `adjustedTickDelay(30)`) and then teleports toward the player (`mob/toward-aim`, `EnderMan.teleportTowards`: 16 along the line from its middle to the eye, spread 8 on x and z and -8 to 7 on y), the count back to zero where it lands and kept where it does not (it tries again the next tick, vanilla's every other). A stare resets the count. Where an aim lands is `mob/landing` (`LivingEntity.randomTeleport`): straight down from the aim's cell to the first floor, the three cells there clear of blocks, water and lava, every cell loaded; the feet go on the floor (not the aim's fraction above it) and only the aim's column is tested, not the cells the 0.6-wide body straddles. A jump that long is told as `entity-position-sync` (`tell-move`). Each aim takes three rolls of `mob-roll`, so only an angry enderman's teleports spend the mobs' stream: a seed's overworld run without one rolls as before. `an-angry-enderman-far-off-teleports-toward-the-player`, `an-angry-enderman-stared-at-up-close-teleports-away`, `an-angry-enderman-walks-round-a-wall-to-the-player` and the pure `a-route-steps-round-a-wall-down-a-drop-and-ends-nearest-when-it-cannot-arrive` and `an-enderman-aims-its-teleports-as-vanilla-and-lands-on-a-floor-with-room` pin them. Probe 39847287615 (probe/263-sim-teleport, the same pearl probe on this model, seed 3, 60 simulated minutes): R4 took its twelve pearls in 23 minutes (21 provoked, 21 killed); R1 and R2 took nine each (22 and 23 provoked, 21 and 22 killed), against none before; R3 one (3 provoked, 2 killed), its last angry enderman stuck 8 blocks off where no route nearer exists (5,740 empty searches). The first death in R1, R2 and R4 (t 1421, 835 and 1240 s) was each time an enderman the bot provoked at 10.8 to 17.8 health just outside its burrow, hitting it three to five times with a clear line within five seconds (probe 39848524906 logs every hit): the bot's fight, not the model.

## The player's attack
`attack` (775's packet, `{:entity-id}`): a living mob whose box is within 3.2 of the eye (`mob/box-distance`) is hurt by the held item (`weapon-damage`: a sword 4-8, an axe 7-10, a pickaxe 2-6, a shovel 2.5-6.5, the hand 1; no charge, the bot swings every 650 ms), the item worn (a sword one, a tool two), 0.1 exhaustion spent. `hurt-mob` keeps the hurt window: within 500 ms of its last hurt only a harder hit lands, for its excess, so the siblings' 40 swings in 66 ms deal one. A hurt mob turns on the player (`anger`; an enderman's creepy flag is sent then, index 17, as the server sends it when it takes a target); at zero it dies, its loot dropped as item entities when the player killed it.

## Armour
Every mob hit, arrow and blast on the player passes through `armoured` (issue #263): the points and toughness of the pieces in window 0's four armour slots (`mob/armour-points`, read from `ArmorMaterials`) take their share by `mob/absorbed` (CombatRules: armour counts for points − damage / (2 + toughness / 4), at least a fifth of the points and at most 20, 4% a point), after the difficulty scales the hit. Without it an enderman's 7 a second beat every sword before the fight's break-off ([[ender-pearls]]). Starving, drowning and lava are not mob hits and pass untouched. The pieces never wear out.

## Fixtures
`init`'s `:mobs [{:type kind :pos [x y z]}]` places a fixture's mobs, sent at login and never despawned; `with-mobs` does the same to a model already in play (the tests' arenas). A mob given acts with or without a difficulty (nil is Normal's damage); the player's health is told when a mob is present (`sync-health`).

## Limits
Not modelled: pathfinding for the blaze (it hovers) and the animals, knockback, an arrow's or fireball's flight (the damage lands as it is fired), a blast breaking blocks, an enderman's teleports out of water, rain, daylight or a non-living hurt (the model has no such hurt for it, no rain and no light; its teleports at its target are modelled, above), spiders climbing, mobs swimming, armour wearing out, `set-time` (the bot is not told the time of day), tracking range (every mob is sent however far), the Nether's hostiles outside a warped forest (`try-nether-spawn` spawns endermen on nylium and nothing else), and the biome itself (a warped forest's netherrack patches spawn nothing; a shroomlit cell spawns as a dark one does).

A respawn makes the client forget every entity, so the model tells it every mob and item again after the respawn packet (`retell-entities`, sim race 38900840841, issue #62): without it a bot that died on a stair never saw the zombie behind it, `:defend` never fought, and it died on the same stair until `:timeout`.

## See also
- [[server-model]] - the model this extends.
- [[sim-hunger]] - the hunger the hits and heals cost.
- [[survival]] - the bot's own eating and fighting rows.
