---
title: Ender pearls
description: How the bot gets twelve ender pearls (issue #263) - endermen hunted with the sword, never bartered; the loot table, the warped forest and the enderman's stare and teleport rules read from the jar; the :pearls plan (the kill's drop, the fight, a stare from range to provoke, a walk), the :provoke intent, the two goal rows, the sim test and the model's armour.
type: reference
tags: [bot, plan, combat, nether, goals, intents]
aliases: [:pearls, :provoke, :ender-pearls, :ender-pearl, pearls-12, pearls-1, :unprovoked, :no-enderman, stare-from, stare-range]
status: verified
lastUpdated: 2026-10-16
verifiedAgainst: HEAD
sourceRefs:
  - src/clojurecraft/pearls.clj#def hunt-radius 32.0
  - src/clojurecraft/pearls.clj#def stare-from 6.0
  - src/clojurecraft/pearls.clj#def stare-ms 1000
  - src/clojurecraft/pearls.clj#def come-ms 12000
  - src/clojurecraft/pearls.clj#defn endermen
  - src/clojurecraft/pearls.clj#defn provokable?
  - src/clojurecraft/pearls.clj#defn fresh-kill
  - src/clojurecraft/pearls.clj#defn drop-of
  - src/clojurecraft/pearls.clj#defn body?
  - src/clojurecraft/pearls.clj#defmethod intent/run :provoke
  - src/clojurecraft/pearls.clj#defn hunt
  - src/clojurecraft/pearls.clj#defmethod plan/next-intent :pearls
  - "resources/clojurecraft/goals.edn#{:goal/id :ender-pearls"
  - "resources/clojurecraft/goals.edn#the :ender-pearls hunt, done at the first pearl held"
  - src/clojurecraft/mob.clj#defn stared?
  - src/clojurecraft/mob.clj#def armour-points
  - src/clojurecraft/mob.clj#defn absorbed
  - src/clojurecraft/sim.clj#defn armoured
  - test/clojurecraft/pearls_test.clj#one-in-reach-is-fought-one-in-sight-provoked-one-hidden-walked-to
  - test/clojurecraft/pearls_test.clj#the-provoke-stares-at-the-head-then-looks-at-the-legs-until-it-comes
  - test/clojurecraft/pearls_test.clj#a-kill-is-picked-up-where-it-fell-a-kill-with-no-drop-is-left
  - test/clojurecraft/pearls_test.clj#twelve-pearls-held-the-stage-is-done
  - test/clojurecraft/pearls_test.clj#an-enderman-is-provoked-killed-and-its-pearl-picked-up-against-the-model
  - test/clojurecraft/mob_test.clj#armour-takes-its-share-of-a-mobs-hit
related:
  - "[[bot/plan/_moc|Plan]]"
  - "[[survival]]"
  - "[[sim-mobs]]"
  - "[[entities]]"
  - "[[collect-intent]]"
---

# Ender pearls

Twelve eyes of ender need twelve pearls. The roadmap (#10) first chose bartering; issue #263 reversed it: a stack of gold for twelve pearls is a mine the run never makes, so the bot hunts endermen.

## What the jar says (probe runs 38889277228, 38890566495)
- **Loot.** `loot_table/entities/enderman.json`: one roll of `ender_pearl`, `set_count` uniform 0 to 1, then `enchanted_count_increase` uniform 0 to 1 a level of looting. A pearl one kill in two: about twenty-four kills for twelve.
- **Where.** `worldgen/biome/warped_forest.json`: the only monster spawner is the enderman (weight 1, groups of four; spawn costs charge 1.0, budget 0.12). Crimson forest spawns zombified piglins, hoglins and piglins instead.
- **The enderman.** 40 health, 7 attack (Normal), speed 0.3, follow 64 (`EnderMan.createAttributes`). `isBeingStaredBy` is `isLookingAtMe` with 0.025 and its eye height (`mob/stared?` is the same cone and line). `EndermanLookForPlayerGoal`: an angry one stared at within 4 blocks (distance squared 16) teleports away; one farther than 16 (256) teleports toward its target after 30 ticks. `hurtServer`: a projectile or a potion sends it away; a hit whose source is a living entity never does, so the bot's sword never makes it teleport.

## The rows
`:ender-pearls` (`--until pearls-12`, provides 12 `ender_pearl`) and `:ender-pearl` (`--until pearls-1`, the gym's first trial, one pearl), both done by `:provided?` and planned by `:pearls`, priority 19. Both are asides for now (`plan/aside?`): no chained run takes them and `plan/current-until` stays at the fortress until the stages before them are proven; raising the chain is its own change.

## The :pearls plan (pearls.clj)
In order:
1. **The kill's drop.** `fresh-kill`: the last intent a `:fight` for `:pearls`, done, with `:intent/target-pos`, its target gone (dead or removed; a fight broken off under 10 health is done too, with its enderman alive, and is no kill). An item within `drop-reach` 3 of where it was last seen (`drop-of`) is collected (`:collect` with `:intent/drops` the pearl). The bot cannot tell a pearl from a carried block's drop (an item's stack is in metadata the skim does not read), so any item there is taken.
2. **The drop still to come.** While the body still lies there (`body?`: the entity still tracked, dead), `{:plan/wait :drop}`: the server drops the loot as it dies and removes the body a second later.
3. **The fight.** The nearest of `endermen` (alive, not blacklisted, within `hunt-radius` 32) within `strike-range` 4.5 on the plane: `:fight` (combat.clj: the sword, attacks 650 ms apart, aimed at `combat/aim`, 0.9 over its feet, never its head). Under `combat/retreat-health` 10, `:back-off` instead.
4. **The provoke.** One `provokable?` (its head within `stare-range` 24 of the eye and no nearer than `stare-from` 6, with a clear line by the terrain): `:provoke`.
5. **The walk.** The first of the three nearest a route reaches: a `:walk` to its cell. None: `{:plan/wait :no-route}`; no enderman at all: `{:plan/wait :no-enderman}`.

## The :provoke intent
`{:intent/kind :provoke :intent/target eid}`. `:stare`: the look on its head (`mob/head`) for `stare-ms` 1000, past the goal's 5-tick aggro delay; it leaves the stare early when the enderman comes nearer than `stare-from`. `:wait`: the look on its legs, which provokes nothing and frees it from the freeze a stare holds it in within 16 blocks, until it comes within `strike-range` (done; the fight comes next) or `come-ms` 12000 pass (failed `:unprovoked`, and the planner blacklists it). Done at once when it is gone. Controls are a look only: the bot stands while the enderman comes to it, over ground the bot need not route.

## Against the model
The model's enderman (`mob/kinds`) is neutral until stared at (`sim/provoke`, from the look the client sends) or hit, then walks at the bot and hits for 7 a second. The model had no armour, so an enderman beat every sword before the 10-health break-off; `sim/armoured` now takes the worn pieces' share of every mob hit, arrow and blast (`mob/absorbed`, CombatRules: armour counts for points − damage / (2 + toughness / 4), at least a fifth of the points and at most 20, 4% a point; `mob/armour-points` from `ArmorMaterials`). An enderman's 7 is 5.74 on iron helmet and chestplate and 1.89 on all four diamond pieces. The sim test hunts one fixture enderman in diamond with an iron sword, eleven pearls given and a seed whose first mob roll drops the twelfth (`pearl-seed`).

## Limits
- The fight is in the open, as the issue asks. Seven a hit, one a second, against forty health: an iron sword needs seven hits, so even in iron the fight costs most of the bot's health; the gym rows say how it goes on a real server.
- No emergency escape into water yet (the Nether has none; water poured there evaporates).
- No exploring: with no enderman within 32 the plan waits and fails `:no-enderman`.

## See also
- [[survival]] - the fight, the armour and the sword this hunt uses.
- [[sim-mobs]] - the model's enderman, its stare and its loot.
- [[entities]] - the mob model the hunt reads.
- [[collect-intent]] - the collect that picks the pearl up.
