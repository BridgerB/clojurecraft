---
title: Dragon
description: How the bot breaks the End's crystals and kills the dragon (issue #267) - a pillar beside each spike, a two-block wall between the stand and the crystal's blast, the strike, the pillar dug back down; the perched dragon struck by its box from the ground by the fountain; the win from the server's death event, the dragon's removal with no boss bar, or the credits.
type: reference
tags: [bot, plan, end, dragon, combat, goals]
aliases: [dragon.clj, dragon, :crystals, :dragon, :crystals-gone?, :dragon-dead?, :pillar, :dig-down, :strike, :perch, :world/dragon-dead?, :world/boss-bars, boss-event, game-event, win_game, spike-cages, spike-crystals]
status: verified
lastUpdated: 2026-10-18
verifiedAgainst: HEAD
sourceRefs:
  - src/clojurecraft/dragon.clj#defn approach-along
  - src/clojurecraft/dragon.clj#defn best-approach
  - src/clojurecraft/dragon.clj#defn wall-gap
  - src/clojurecraft/dragon.clj#defn on-pillar?
  - src/clojurecraft/dragon.clj#defn on-spike?
  - src/clojurecraft/dragon.clj#defn pillar-top
  - src/clojurecraft/dragon.clj#defn toward-crystals
  - src/clojurecraft/dragon.clj#defn climb
  - src/clojurecraft/dragon.clj#defmethod intent/run :pillar
  - src/clojurecraft/dragon.clj#defmethod intent/run :dig-down
  - src/clojurecraft/dragon.clj#defn hit
  - src/clojurecraft/dragon.clj#defmethod intent/run :strike
  - src/clojurecraft/dragon.clj#defn perched?
  - src/clojurecraft/dragon.clj#defmethod intent/run :perch
  - src/clojurecraft/dragon.clj#defn fountain-stand
  - src/clojurecraft/dragon.clj#defn toward-dragon
  - src/clojurecraft/dragon.clj#defmethod plan/done-by :crystals-gone?
  - src/clojurecraft/dragon.clj#defmethod plan/done-by :dragon-dead?
  - src/clojurecraft/entity.clj#defn dragon-gone?
  - src/clojurecraft/game.clj#defmethod on-packet [:play :entity-event]
  - src/clojurecraft/game.clj#defmethod on-packet [:play :remove-entities]
  - src/clojurecraft/game.clj#defmethod on-packet [:play :boss-event]
  - src/clojurecraft/game.clj#defmethod on-packet [:play :game-event]
  - src/clojurecraft/combat.clj#defn attack
  - src/clojurecraft/end.clj#def spikes
  - src/clojurecraft/sim.clj#defn blast
  - src/clojurecraft/sim.clj#defn perch
  - "resources/clojurecraft/goals.edn#{:goal/id :crystals"
  - "resources/clojurecraft/goals.edn#{:goal/id :dragon"
  - "resources/clojurecraft/problems.edn#{:problem/stage :dragon"
  - src/clojurecraft/gym.clj#def spike-cages
  - src/clojurecraft/gym.clj#def spike-crystals
  - "src/clojurecraft/gym.clj#{:gym/goal :crystals"
  - "src/clojurecraft/gym.clj#{:gym/goal :dragon"
  - test/clojurecraft/dragon_test.clj#the-approach-stands-three-out-behind-a-wall
  - test/clojurecraft/dragon_test.clj#the-planner-walks-pillars-walls-strikes-and-digs-down
  - test/clojurecraft/dragon_test.clj#the-climb-jumps-and-puts-the-block-in-once-clear
  - test/clojurecraft/dragon_test.clj#the-dragon-is-dead-by-the-servers-word
  - test/clojurecraft/dragon_test.clj#the-dragon-perches-on-the-fountain
  - test/clojurecraft/dragon_test.clj#a-crystal-is-broken-from-behind-a-wall-against-the-model
  - test/clojurecraft/dragon_test.clj#the-perched-dragon-is-killed-against-the-model
related:
  - "[[bot/plan/_moc|Plan]]"
  - "[[end-portal]]"
  - "[[sib-end-and-dragon]]"
---

# Dragon

The last stage (issue #267, split from the roadmap's `:end-and-dragon` into `problems.edn`'s `:dragon`, goals `:crystals` and `:dragon`). It starts where [[end-portal]]'s `:enter-end` ends, in the End. Both siblings went beds-not-bow for the dragon and bows for the crystals; clojurecraft has neither, so the crystals are reached by pillaring and the dragon is struck with the sword while it perches.

## The crystals

A crystal stands on the bedrock over each of the ten obsidian spikes (`end/spikes`: radius 2 to 5, 76 to 103 high). Any hit blows it up: a blast of power 6, about 56 damage three blocks off with a clear line (`mob/blast-damage`), so a bot that strikes in the open dies. The blast's damage scales with how much of the body the explosion sees, and the server computes it before the blast breaks any block, so blocks between are a shield. An attack is judged by reach alone, through any block.

`approach-along` works out, for one axis out from the crystal's column: the stand, the cell `stand-off` (3) out with the feet level with the spike's top (the crystal's height minus one), from which the crystal's 2x2 box is two away; the wall, the two cells `wall-off` (2) out at the feet's and the eye's height, which every line from the blast to the body crosses (along an axis only, so the two cells cover the body); and the pillar, the first column out from the spike's top layer, never nearer than the stand, from its ground up. On a spike of radius 2 the pillar's top is the stand; on a wider one the stand is on the spike's top, walked onto from the pillar. `best-approach` takes the axis whose pillar foot is nearest the feet.

`toward-crystals` reads the world each tick for the crystal nearest the feet: at the stand, a `:place` into the wall's first gap (`wall-gap`; any block counts, a cage's bars too), then the `:strike`; in the pillar's column under the top, the `:pillar`; at the pillar's top short of the stand, the walk to it; else on a spike (`on-spike?`, three obsidian under the feet; the arrival platform is one thick) the walk back to the nearest pillar top (`pillar-top`), on a pillar (`on-pillar?`, two pillar blocks under the feet) the `:dig-down`, on the ground the walk to the pillar's foot. Waits `:no-blocks` (nothing to pillar with), `:no-approach`, `:stranded`. Nothing records progress.

- `:pillar` (`climb`): the block in hand, then a jump from the ground, the feet cell kept as `:intent/under`; once the body has risen `clear-rise` over it (the server refuses a block into a body), a `use-item-on` against the top of the block under, looking straight down; the block shown, the next jump. Refusals counted (`:intent/refusals`), `:stuck` after `block-ms` with no block. Pillar blocks are cobblestone and the like, never end stone (the End's ground, which the way down would dig on into).
- `:dig-down`: a child `:dig` of the block under the feet (its drop wanted or not), the fall waited out, until the feet stand on less than two pillar blocks.
- `:strike`: the best sword in hand (`combat/sword`), then `hit`: the eyes on the nearest point of the target's box, and an attack (`combat/attack`, 650 ms apart) once the look went out a tick before and the box is within `strike-reach` (3, the client's entity reach) of the eye; done when the target is gone, failed `:out-of-reach`.

Not done: the cages round two spikes (radius 2: the stand beside one is outside its bars and would strike through them, unproven; the gym takes them away), the bridge from the End's platform at x 100 over the void to the main island, the exit portal.

## The dragon

`toward-dragon`: while a crystal stands or the feet are up a pillar or spike, the crystals'; a breath cloud or fireball (`area_effect_cloud`, `dragon_fireball`) within `breath-radius` (6: the sitting dragon's flame cloud has radius 5), combat's `:back-off`; away from the fountain's stand (`fountain-stand`, the ground `stand-from` (2) out from the exit portal's pillar at 0 0 along the axis nearest the feet, in the portal's empty basin, none within `breath-radius` of a breath), the standing walk there; there, the `:perch`. The `:perch` faces the dragon and counts the ticks it holds `perched?` (its phase, `:entity/phase` from metadata index 16, one of the sitting phases 5 to 7; with no phase said, as in the model, within 6 of the fountain on the plane and under y 70); after `perch-ticks` (10) it hits the dragon while its box is in reach, walking in toward the fountain (no nearer than `walk-in-to`) while it is not; done when the dragon is gone, a breath is near, or after `perch-hold-ms`.

The stand is beside the pillar because the perched dragon turns its face to the bot: its head is 6.5 out from the pillar and its neck 5.5, and each hurts what it touches whenever the dragon is not in its hurt time (10 damage, about 6 through iron). Batch dragon-a stood five out, under the head: run 1 landed five hits of 2.5 on the body and died in four seconds of the perch. Two out, the head and neck are past the bot.

The dragon is struck by its box, not through `:fight`: its box is 16 by 8 with its feet on the fountain's pillar, four over the portal, and `:fight`'s reach to an aim point 0.9 over the feet is never met from a floor; the server's own test is the box. The body takes a quarter of a hit plus one (the head takes it all; its part's position is the client's to compute, not done), and a perched dragon takes off after 50 damage in one perch, so a kill takes several perches.

## The win

`:world/dragon-dead?`, set in `game.clj` by the server's word: `entity-event` status 3 on the dragon (a sitting dragon dies through the living entity's death), the dragon removed (`remove-entities`) within `entity/dragon-seen-radius` of the bot with no boss bar shown (`entity/dragon-gone?`; `:world/boss-bars` from the new `boss-event` spec, uuid and action only; a summoned dragon has no fight and no bar; one flown out of tracking is not dead), or `game-event` win_game (4), the credits after the exit portal, answered with `client-command` 0 as the vanilla client does.

## Goals

- `:crystals` (priority 26, aside, `--until crystals`, the End only): done `:crystals-gone?`, no crystal shown and the feet off every pillar and spike, once `look-ms` have passed.
- `:dragon` (priority 27, aside, `--until dragon`, the End only): done `:dragon-dead?`. The race's `:until` stays where it is: raising it is the lead's call.

## Gym

Rows `crystals` and `dragon`, both `land! :end` (the main island, ruststeve's End start) at difficulty normal, 25 minutes. `crystals`: `spike-cages` (setup, the island forceloaded) kills every crystal (a crystal lights fire in its own cell each tick) and takes the iron bars and the fire off each spike's top; `spike-crystals` (summons) kills the dragon and the crystals again (the setup's kill misses those whose entities have not loaded: dragon-a's killed five of ten), clears the fire again and summons a crystal on each spike's top through `positioned over world_surface`, `~ ~ ~` (an integer x and z `positioned` names is a block's middle already), on its bedrock as the fight puts them; two stacks of cobblestone, an iron pickaxe and a sword given; judged by `execute in minecraft:the_end unless entity @e[type=minecraft:end_crystal]`. `dragon`: the crystals killed (setup and summons) and a dragon summoned at 0 80 0 with `DragonPhase:0` and 200 health, a sword, iron armour, cobblestone and a pickaxe given; judged by `unless entity @e[type=minecraft:ender_dragon]`. The End's fight starts on the bot's arrival, after the summons: it takes the summoned dragon for its own (its boss bar shown), and in the crystals row it makes a dragon of its own that flies while the bot pillars.

## Proof

- `dragon_test`: the approach on a spike of radius 2 (the pillar's top is the stand) and 4 (the stand on the top); the planner's walk, pillar, wall, strike and way down, and the walk back off a wide spike; the climb's jump, nothing sent while the body is in the cell, the block against the one under once clear; the win from the death event, the removal with no bar, not with a bar, not far off, and the credits answered; the perch by phase and by place; and against the model's End, a crystal on a spike of radius 2 broken from behind the wall with no blast taken and the bot back down, and the perched dragon killed.
- Batch dragon-a (plan run 39152590801, at 03421f2): `crystals` 0/3, every run walking at once for the foot of a pillar on the spike's top (:no-path): the crystals stood on the fire a block over their bedrock and on the corner of their column, so the approach was a block high; `dragon` 0/3: run 1 struck the perched dragon five times (2.5 each, 200 to 187.5) from five out, under its head, and died; runs 2 and 3 waited :no-blocks for crystals the gym's kill missed.
