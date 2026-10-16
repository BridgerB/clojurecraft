---
title: Mobs in the server model
description: How sim.clj spawns, moves, fights and kills mobs by mob.clj's rules - the night rule, straight approach, melee, arrows, the creeper's fuse, the 775 attack with its hurt window, loot - and the fixtures every combat stage's sim tests start from.
type: reference
tags: [bot, tooling, sim, combat]
aliases: [mob.clj, :sim/mobs, :mobs fixture, with-mobs, tick-mobs, attack handler, night rule, day-time]
status: verified
lastUpdated: 2026-10-16
verifiedAgainst: HEAD
sourceRefs:
  - src/clojurecraft/mob.clj#def kinds
  - src/clojurecraft/mob.clj#defn weapon-damage
  - src/clojurecraft/mob.clj#defn blast-damage
  - src/clojurecraft/mob.clj#defn step-toward
  - src/clojurecraft/mob.clj#defn spawn-spots
  - src/clojurecraft/mob.clj#defn stared?
  - src/clojurecraft/mob.clj#defn absorbed
  - src/clojurecraft/sim.clj#defn armoured
  - test/clojurecraft/mob_test.clj#armour-takes-its-share-of-a-mobs-hit
  - src/clojurecraft/sim.clj#defn with-mobs
  - src/clojurecraft/sim.clj#defn tick-mobs
  - src/clojurecraft/sim.clj#defn try-spawn
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
- `despawn`: a hostile not persistent goes at once beyond 128, and by a roll of 1 in 800 a tick beyond 32 (`Mob.checkDespawn`).
- `populate`: each chunk within three of the player gets its animals once (`:sim/populated`): one time in ten a herd of three of one kind on the grass at its middle, persistent, as a server populates a chunk it generates.
- Every roll comes from the mobs' own stream (`mob-roll`, `:sim/mob-rng`), never the loot's, so gravel's flint falls the same with mobs or without.

## A mob's tick (`act-mob`)
A dead one is removed once its death is over (20 ticks, the dragon 200). An undead burns in the overworld's daylight with no roof over its head (1 a second, no loot). Otherwise, with the living player as its target (`targets?`: in follow range, a neutral one only once angry) it walks straight at the player (`mob/step-toward`: the step when the body stands there, one block up when one is in the way, else it stays, then it falls; a body stands where its `mob/tall` cells are free, three for an enderman and two for every other walker, so a tunnel two high keeps an enderman out, issue #263), then hits, shoots or swells by its kind. No pathfinding: a wall stops it.

## The player's attack
`attack` (775's packet, `{:entity-id}`): a living mob whose box is within 3.2 of the eye (`mob/box-distance`) is hurt by the held item (`weapon-damage`: a sword 4-8, an axe 7-10, a pickaxe 2-6, a shovel 2.5-6.5, the hand 1; no charge, the bot swings every 650 ms), the item worn (a sword one, a tool two), 0.1 exhaustion spent. `hurt-mob` keeps the hurt window: within 500 ms of its last hurt only a harder hit lands, for its excess, so the siblings' 40 swings in 66 ms deal one. A hurt mob turns on the player (`anger`; an enderman's creepy flag is sent then, index 17, as the server sends it when it takes a target); at zero it dies, its loot dropped as item entities when the player killed it.

## Armour
Every mob hit, arrow and blast on the player passes through `armoured` (issue #263): the points and toughness of the pieces in window 0's four armour slots (`mob/armour-points`, read from `ArmorMaterials`) take their share by `mob/absorbed` (CombatRules: armour counts for points − damage / (2 + toughness / 4), at least a fifth of the points and at most 20, 4% a point), after the difficulty scales the hit. Without it an enderman's 7 a second beat every sword before the fight's break-off ([[ender-pearls]]). Starving, drowning and lava are not mob hits and pass untouched. The pieces never wear out.

## Fixtures
`init`'s `:mobs [{:type kind :pos [x y z]}]` places a fixture's mobs, sent at login and never despawned; `with-mobs` does the same to a model already in play (the tests' arenas). A mob given acts with or without a difficulty (nil is Normal's damage); the player's health is told when a mob is present (`sync-health`).

## Limits
Not modelled: pathfinding round walls, knockback, an arrow's or fireball's flight (the damage lands as it is fired), a blast breaking blocks, endermen teleporting, spiders climbing, mobs swimming, armour wearing out, `set-time` (the bot is not told the time of day), and tracking range (every mob is sent however far).

## See also
- [[server-model]] - the model this extends.
- [[sim-hunger]] - the hunger the hits and heals cost.
- [[survival]] - the bot's own eating and fighting rows.
