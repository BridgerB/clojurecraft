---
title: Ender pearls
description: How the bot gets twelve ender pearls (issue #263) - endermen hunted with the sword, never bartered; the loot table, the warped forest and the enderman's stare and teleport rules read from the jar; the :pearls plan (the kill's drop, the fight, a stare from range to provoke, a walk), the :provoke intent, the two goal rows, the sim test and the model's armour.
type: reference
tags: [bot, plan, combat, nether, goals, intents]
aliases: [:pearls, :provoke, :burrow, :lookout, :pearls/shelter, :entity/creepy?, :intent/hold?, :ender-pearls, :ender-pearl, pearls-12, pearls-1, :unprovoked, :no-enderman, stare-from, stare-range]
status: verified
lastUpdated: 2026-10-16
verifiedAgainst: HEAD
sourceRefs:
  - src/clojurecraft/pearls.clj#def hunt-radius 32.0
  - src/clojurecraft/pearls.clj#def stare-from 6.0
  - src/clojurecraft/pearls.clj#def stare-ms 3000
  - src/clojurecraft/pearls.clj#def burrow-depth 3
  - src/clojurecraft/pearls.clj#def burrow-length 2
  - src/clojurecraft/pearls.clj#defn stand?
  - src/clojurecraft/pearls.clj#defn shelter?
  - src/clojurecraft/pearls.clj#defn away
  - src/clojurecraft/pearls.clj#defmethod intent/run :burrow
  - src/clojurecraft/pearls.clj#defmethod intent/run :lookout
  - src/clojurecraft/pearls.clj#def seek-radius 64.0
  - src/clojurecraft/combat.clj#defmethod fight-stage :approach
  - src/clojurecraft/entity.clj#def creepy-index 17
  - src/clojurecraft/mob.clj#defn tall
  - src/clojurecraft/sim.clj#defn anger
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
  - test/clojurecraft/pearls_test.clj#the-provoke-stares-at-the-head-until-the-server-says-it-has-a-target
  - test/clojurecraft/pearls_test.clj#the-burrow-end-is-out-of-an-endermans-reach
  - test/clojurecraft/pearls_test.clj#the-hunt-digs-a-burrow-provokes-from-its-mouth-and-fights-from-its-end
  - test/clojurecraft/combat_test.clj#a-fight-that-holds-its-ground-never-walks
  - test/clojurecraft/mob_test.clj#an-enderman-stays-out-of-a-tunnel-two-high
  - test/clojurecraft/pearls_test.clj#a-kill-is-picked-up-where-it-fell-a-kill-with-no-drop-is-left
  - test/clojurecraft/pearls_test.clj#twelve-pearls-held-the-stage-is-done
  - test/clojurecraft/pearls_test.clj#an-enderman-is-provoked-and-killed-from-a-burrow-against-the-model
  - test/clojurecraft/mob_test.clj#armour-takes-its-share-of-a-mobs-hit
related:
  - "[[bot/plan/_moc|Plan]]"
  - "[[survival]]"
  - "[[sim-mobs]]"
  - "[[entities]]"
  - "[[collect-intent]]"
  - "[[gym-on-runners]]"
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
1. **The kill's drop.** `fresh-kill`: the last intent a `:fight` for `:pearls`, done, with `:intent/target-pos`, its target gone (dead or removed; a fight broken off under 10 health is done too, with its enderman alive, and is no kill). A pearl lying round where it was last seen (`drop-of`: `drops/nearest`, a drop holding a pearl or not yet saying what it holds) is picked up by #262's `:collect-drops`; the block an enderman carried drops beside its pearl and is left, since a drop now says what it holds.
2. **The drop still to come.** While the body still lies there (`body?`: the entity still tracked, dead), `{:plan/wait :drop}`: the server drops the loot as it dies and removes the body a second later.
3. **The hunt** (`hunt`), over the nearest of `endermen` (alive, not blacklisted, within `hunt-radius` 32) and the nearest of them that is coming (`entity/creepy?`: the server's creepy flag, metadata index 17, set while it has a target):
   - out of the burrow (`shelter`: `:pearls/shelter` within `shelter-reach` 16 and still a `shelter?`) with one coming, one within `strike-range` 4.5, or under 10 health: a walk back to its end (`:intent/for :shelter`: the walk ends in that very cell, as a build's stand does). Batch pearls-263b: one bot met an enderman in the open after a provoke that ended by its coming near, and another backed away hurt; both died;
   - in it, the one coming, or one at hand, is fought where the bot stands: `:fight` with `:intent/hold?` (combat.clj: it never walks, never presses after the target and never breaks off; the approach stands facing it until it comes within 3, at most 20 s);
   - with no burrow, one at hand is fought, or backed away from under 10 health (the open fight, the fallback);
   - no burrow and a pickaxe held (`pickaxe?`): `:burrow`, heading `away` from the nearest enderman, so the stairs open toward it;
   - in the burrow with none coming: a walk to its mouth (`:pearls/mouth`) to look round;
   - one not yet coming and `provokable?` (its head within `stare-range` 24 of the eye and no nearer than `stare-from` 6, with a clear line): `:provoke`. Never one already coming: its provoke ends at once, and batch pearls-263b's bot, its pickaxe lost to a death, provoked one 26 times in a row;
   - one coming and no burrow to meet it in: fought;
   - else a walk to the first of the three nearest within `seek-radius` 64 a route reaches; else a `:lookout`: standing for `lookout-ms` 5 s, the eyes level and turning, then done. Waiting as an intent, not a planner wait (whose 400 ticks fail the plan): batch pearls-263c's warped forest landings failed `:no-enderman` and `:no-route` 40 s in, before any enderman had come. With no enderman at all the burrow is still dug first, heading east.

## The burrow
Gym batch pearls-263a (plan run 38892130785) put three live endermen round the bot in iron helmet and chestplate: five trials, five deaths. A replay of one (probe 38893413043) showed the hits landing at 5.74 a second, the sword's sweep pulling a second enderman in, and a break-off with nowhere to go. A sword needs seven hits against forty health; the open fight loses. The game says where it cannot lose: a mob's melee is its box grown 0.83 sideways and not at all up or down (`Mob.getAttackBoundingBox`, `isWithinMeleeAttackRange`, probe 38893918055), and an enderman needs three free cells to stand. `shelter?` is that rule over the terrain: the bot's own two cells open, and no cell an enderman could stand in (`stand?`: a floor, three open cells) in the nine columns round it, at the heights whose body would overlap the bot's (feet two under to one over).

`:burrow` (`{:intent/kind :burrow :intent/dir [dx dz]}`) cuts one with the executors already there: `:stairs-down` `burrow-depth` 3 (each stair three cells high, refusing water, lava and drops), then `:tunnel` `burrow-length` 2 along the heading the stairs ended on (two high, the ground over it from the third stair down), each a child run through `stone/link`. Done when the feet stand in a `shelter?`, kept as `:pearls/shelter` with `:pearls/mouth` where it began; failed `:no-shelter` otherwise. An enderman follows down the stairs and stops at the last one, two cells from the bot: out of its reach, inside the sword's (1.5).

## The :provoke intent
`{:intent/kind :provoke :intent/target eid}`: the look on its head (`mob/head`) until the server says it has a target (`entity/creepy?`) or it comes nearer than `stare-from`, then done with the look moved to its legs at once (stared at within 16 an angry one stands frozen, within 4 it teleports away); failed `:unprovoked` after `stare-ms` 3000 with neither, and the planner blacklists it. Done at once when it is gone. The plan then takes the bot back to the burrow's end to meet it.

## Against the model
The model's enderman (`mob/kinds`) is neutral until stared at (`sim/provoke`, from the look the client sends) or hit, then walks at the bot and hits for 7 a second. The model had no armour, so an enderman beat every sword before the 10-health break-off; `sim/armoured` now takes the worn pieces' share of every mob hit, arrow and blast (`mob/absorbed`, CombatRules: armour counts for points − damage / (2 + toughness / 4), at least a fifth of the points and at most 20, 4% a point; `mob/armour-points` from `ArmorMaterials`). An enderman's 7 is 5.74 on iron helmet and chestplate and 1.89 on all four diamond pieces. The model's walkers stand where their box fits (`mob/tall`: an enderman three cells, every other walker two as before), so its enderman stops at the burrow's tunnel as the server's does, and `sim/anger` sends an enderman's creepy flag when it turns. The sim test hunts one fixture enderman with the chained run's kit (an iron sword and pickaxe, the iron helmet and chestplate, eleven pearls) and a seed whose first mob roll drops the twelfth (`pearl-seed`): it digs the burrow, provokes the enderman from the mouth, kills it from the end and picks the pearl up.

## Gym rows
`pearls-1` (five NoAI endermen in the sealed Nether room, a pearl held), `pearls-1-live` (three live ones ten blocks off in a stone arena 25 square at midnight, `pearl-arena`, so they are provoked on purpose and the burrow is cut in stone; a pearl held and health left) and `pearls-12` (the warped forest nearest the landing's Nether coordinates, twelve held), all at Normal with an iron sword and pickaxe and the iron helmet and chestplate: [[gym-on-runners]].

## Limits
- The burrow needs ground it can be cut in: three stairs of rock with no water or lava beside them. Where it cannot be dug the hunt fights in the open, which loses.
- The enderman must come down the stairs: the server's pathfinder takes it to the nearest place it fits, the last stair. One that stays at the top is out of reach: the held fight fails `:unreachable` after 20 s and the enderman is blacklisted.
- No emergency escape into water yet (the Nether has none; water poured there evaporates).
- Gym batch pearls-263b (plan run 38895776486), with the burrow: `pearls-1-live` passed 3 of 5 where the open fight had died five times in five. One death came from a zombie the unlit midnight arena spawned, which the `:defend` row backed away from turn about with the hunt; the other from an enderman met in the open. The arena is lit now, and an enderman at hand, one coming or a hurt bot sends it back to its burrow.
- No exploring beyond `seek-radius`: with no enderman tracked within 64 the bot stands and looks round until one comes, or the run's time ends.

## See also
- [[survival]] - the fight, the armour and the sword this hunt uses.
- [[sim-mobs]] - the model's enderman, its stare and its loot.
- [[entities]] - the mob model the hunt reads.
- [[collect-intent]] - the collect that picks the pearl up.
