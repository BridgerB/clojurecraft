---
title: Fortress tunnel
description: How the bot reaches a nether fortress (issue #11) - bricks as passive sightings in the Nether, a tunnel one step at a time where each step is a small plan of ops, and the rule that no step opens a cell beside lava.
type: reference
tags: [bot, plan, nether, fortress]
aliases: [fortress.clj, :tunnel-to, :fortress, step-plan, safe?, at-fortress?]
status: verified
lastUpdated: 2026-10-17
verifiedAgainst: HEAD
sourceRefs:
  - src/clojurecraft/fortress.clj#defn step-plan
  - src/clojurecraft/fortress.clj#defn opens
  - src/clojurecraft/fortress.clj#defn safe?
  - src/clojurecraft/fortress.clj#defn dry?
  - src/clojurecraft/fortress.clj#defn moves
  - src/clojurecraft/fortress.clj#defn next-move
  - test/clojurecraft/fortress_test.clj#a-tunnel-turns-onto-ground-instead-of-pacing-in-the-portal
  - src/clojurecraft/fortress.clj#def brick-states
  - src/clojurecraft/fortress.clj#defn bricks
  - test/clojurecraft/fortress_test.clj#fortress-bricks-use-the-indexed-current-sightings
  - src/clojurecraft/fortress.clj#defmethod intent/run :tunnel-to
  - src/clojurecraft/fortress.clj#defmethod plan/next-intent :fortress
  - src/clojurecraft/fortress.clj#defmethod plan/done-by :at-fortress?
  - src/clojurecraft/fortress.clj#defmethod plan/done-by :nether-access?
  - src/clojurecraft/fortress.clj#defmethod plan/next-intent :return-nether
  - test/clojurecraft/fortress_test.clj#a-reached-nether-entry-does-not-strand-an-unfinished-fortress
  - src/clojurecraft/build.clj#defn work-op
  - src/clojurecraft/plan.clj#defn elsewhere?
  - test/clojurecraft/fortress_test.clj#no-step-opens-a-cell-beside-lava
  - test/clojurecraft/fortress_test.clj#no-step-opens-a-spawner
  - src/clojurecraft/fortress.clj#defn arrived?
  - src/clojurecraft/fortress.clj#def aside
  - src/clojurecraft/fortress.clj#defn off-step?
  - test/clojurecraft/fortress_test.clj#a-step-the-feet-left-is-chosen-again
  - test/clojurecraft/sim_test.clj#the-fortress-from-a-nether-landing-and-its-recording-replays
related:
  - "[[bot/plan/_moc|Plan]]"
  - "[[build-plan]]"
  - "[[memory-sightings]]"
  - "[[mc-dimensions]]"
---

# Fortress tunnel

The `:fortress` target (`goals.edn`, `:goal/dimension "minecraft:the_nether"`) is done when a fortress block on record (`nether_bricks`, its fence or stairs, at y 32 or more, so never one under the lava sea) is within 6 blocks of the feet. Its planner (`plan/next-intent :fortress`) starts a `:tunnel-to` (not stone's `:tunnel`, which digs along a direction for ore) to the nearest one on record, or, with none, 48 blocks along the first axis with a safe first step.

`bricks` asks `memory/positions-now` for `brick-states`, the indexed current sightings remembered by the facts' identity, before filtering the height; it never scans all sightings with `memory/latest`. Issue #328's snapshot, once streamed chunks restored its dug room ([[server-model]]), could tunnel but exhausted the judge's four-minute budget in that full scan on every tick. The indexed query preserves the dimension and recency rules without repeating the whole-memory query.

## A step is a plan of ops
`step-plan feet move` is a vector of [[build-plan]] ops, a pure function of where the step starts and its `[dx dy dz]`: place a floor under the cell stepped into (it may be lava: a placement replaces it), dig the cells the body passes through (`opens`: the two it ends in, plus the head's cell on a step down or the cell over the head on a step up), and stand in the new cell (`[:feet to]`, the plan's goal). `build/next-op` and `build/work-op` work it exactly as a `:build` works the portal, so the dig, place and walk children are the ones the portal uses. The `:tunnel-to` intent chooses a move when a step starts (`:intent/move`, `:intent/from`), works the step until the bot stands in it, counts it, and stops at the target or after 32 steps, so the planner can choose again from what the new chunks showed.

## Never beside lava
`safe?` refuses a step when a cell it opens or its floor is not loaded, a cell it opens is lava or a solid a tunnel never opens (obsidian, bedrock, a spawner: the blaze rods come from it, issue #262), a cell it opens has lava beside it (other than the floor, which is filled first), or the floor is neither solid nor lava a placed block can fill. A passable portal cell needs no dig and may be walked through; its frame remains protected. An air floor is never safe to enter: live run f1-5 stood at a cave's edge, slid off while placing one and fell forty blocks into the lava sea. A cell behind a wall cannot be capped before the wall is dug, and Nether lava flows a block every half second, so instead of capping (ruststeve's `nether_tunnel_step`) the tunnel goes round: `moves` tries the axis with farther to go, up or down first when the target is more than 3 off, then level, then the other axis, sideways, and finally backward.

Issue #286 exposed two false dead ends: R3/10 had a safe retreat behind the feet that `moves` omitted, and the real portal at [256 97 464] was passable but rejected. Merely adding those moves paced between the portal's two cells. `next-move` prefers cells not yet left by this tunnel (`:intent/tunnel-visited`). Before retreating it can floor a level turn onto adjoining ground: the air floor must have immediate placement support and another solid horizontal neighbour besides the floor under the feet, and `stairs/over-fluid?` must find no fluid below it. The step must satisfy the same lava and optional water rules with that floor filled. Controls stop when the step is chosen, and its ordinary floor op must be confirmed before the walk begins; this is a short corner onto ground, not permission to enter an air floor or extend a causeway over lava.

## The planner across dimensions
A row with `:goal/dimension` is chosen only there (`plan/here?`). When no row can be chosen here but one is undone elsewhere (`elsewhere?`), the plan waits `:other-dimension` instead of ending, so `--until fortress` in the overworld does not report done, and a run toward `[:nether :fortress]` works the portal, then the fortress.

After a chained run has reached `:nether`, a death can respawn it in the overworld while Nether targets remain undone (issue #287). The always row `:return-nether` works the existing `:nether` build and its supply planner there. Its `:nether-access?` predicate checks the unfinished Nether targets in play, not merely the historical entry milestone: it does nothing before the first entry, in another dimension, or after those targets are reached. Milestones remain historical; a standalone fortress trial does not acquire a portal-building prerequisite.

## Reach and climb
A `:tunnel-to` is done at its target from `reach` 6 (`arrived?`, from the feet cell's centre), or from `:intent/reach` when the intent gives one; its steps go up or down first only past `climb-past` 3 blocks off, or past `:intent/climb` when given. The blaze rods' tunnel to a spawner goes into the open cell beside it on its floor (reach 0.5, climb 0), or to within 2 of it when none is known ([[blaze-rods]]): from 6 off, gym blaze-fortress-b stood in the wall four over the spawner, and with climb 3 blaze-fortress-c run 3 came up three under its floor, out of reach of every blaze and rod. A target straight over or under the feet used to leave no move at all; `moves` then tries every direction (`aside`).

## A step the feet left
A step's move and the cell it started from are kept on the intent until the step is stood in. When the feet are in none of its cells (`off-step?`: knocked back by a fireball, or moved by a fight or a cover walk between its ticks), or its walk back to the cell it started from fails `:no-path` (`lost-way?`), the step is dropped and chosen again from where the feet are, never failed. Gym blaze-fortress-h run 1 failed its tunnel into the spawner's room `:no-path` twice, and the plan with it (issue #262).

## Gotchas
- The sightings are passive: a brick is remembered when its chunk loads (`memory/landmark-kinds`), in the dimension it was seen in, so no scan and no line of sight.
- The gym row (`fortress`, `:gym/land :nether-fortress`) carves a pocket of netherrack 48 blocks west of `locate structure minecraft:fortress` at y 72 and judges with `execute as {name} at @s if dimension minecraft:the_nether` and a filtered clone that counts bricks within 6.

## In the overworld
The stronghold's portal room (issue #265, [[stronghold]]) is reached by the same tunnel with `:intent/dry? true`: `choose-move` then also wants `dry?`, no opened cell water or beside water, since the way down runs through aquifers; the Nether's steps never ask.
