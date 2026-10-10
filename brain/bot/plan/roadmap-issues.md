---
title: Roadmap issues
description: Which GitHub issue number (#2-#11) is which roadmap step and which docs/issues draft holds its research, because the draft file numbers and the issue numbers differ.
type: reference
tags: [bot, plan, roadmap, index]
aliases: [issue numbers, docs/issues, roadmap map, which issue is water]
status: verified
lastUpdated: 2026-10-09
verifiedAgainst: 5c7d6c1
sourceRefs:
  - "docs/issues/01-crafting-as-data.md#Crafting as data: planks, sticks and a crafting table from the 2x2 grid, then the recipe graph"
  - "docs/issues/02-place-block-and-3x3-crafting.md#Place the crafting table, open it, craft a wooden pickaxe; then recipe-driven goals"
  - "docs/issues/06-water-and-lava-survival.md#Water and lava survival: vanilla water physics, a priority-zero escape-water goal"
  - "docs/issues/09-nether-and-fortress.md#Enter the Nether and reach a fortress: a dimension-aware world"
related:
  - "[[bot/plan/_moc|Plan]]"
  - "[[sib-steve-steps-to-goals]]"
---

# Roadmap issues

The ten drafts in `docs/issues/` were filed as GitHub issues #2-#11, but not in file order. Notes in this brain say "issue #N" for the GitHub number; sibling notes often cite the draft file instead. Read on 2026-10-09 with `gh issue list` and the drafts' titles.

| GitHub | Draft | Step |
|---|---|---|
| #2 | `01-crafting-as-data.md` | planks, sticks, table in the 2x2 grid; the recipe graph (landed: [[craft-intent]], [[make-goals]]) |
| #3 | `03-gym-on-github-runners.md` | the gym: one job per goal run, RCON prerequisites, judged by RESULT plus RCON |
| #4 | `04-pathfinder.md` | A* over the chunk value, waypoints for the walk intent |
| #5 | `06-water-and-lava-survival.md` | water physics, priority-zero escape, liquid memory, fall damage, respawn |
| #6 | `07-furnace-iron-bucket-flint-and-steel.md` | furnace, coal, iron, buckets, flint and steel |
| #7 | `02-place-block-and-3x3-crafting.md` | place and open the table, 3x3 crafting, wooden pickaxe |
| #8 | `08-portal-cast.md` | obsidian cast, the frame as a build plan, light, enter |
| #9 | `05-dig-with-tools-stone-pickaxe.md` | hardness, tools, stairs-down, stone pickaxe |
| #10 | `10-blazes-pearls-stronghold-end.md` | blazes, pearls, eyes, stronghold, End, dragon |
| #11 | `09-nether-and-fortress.md` | dimension-aware world, respawn, Nether memory, fortress search |
| #17 | (none) | multi-bot race: each bot its own atom and loop, one bounded channel for claimed landing cells; after #6 or #8 |

## Gotchas
- The GitHub numbers are an external fact (not anchored in the repo); the draft titles are. Re-check with `gh issue list --state all` if a number looks wrong.

## Problem statements
Each roadmap stage also has a problem statement in `resources/clojurecraft/problems.edn` (the problem, the information it needs, the risks, done in world terms, the last recorded failure, sources), distilled from these drafts and the sibling notes; [[add-a-goal]] says to write it before the goal.

## See also
- [[sib-steve-steps-to-goals]] - steve's 31 steps mapped onto these.
