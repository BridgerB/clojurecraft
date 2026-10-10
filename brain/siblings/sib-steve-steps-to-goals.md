---
title: steve's steps mapped to our goals
description: Boundary note mapping each of steve's 31 priority-ordered steps in steps.ts to the clojurecraft goal or intent that covers it today, or to the roadmap issue draft that will.
type: reference
tags: [siblings, steve, correspondence, plan, goals]
aliases: [steps.ts mapping, steve steps vs goals, which goal is gather_wood, correspondence table]
status: verified
lastUpdated: 2026-10-10
verifiedAgainst: 3452f18 c28028b
sourceRefs:
  - steve:src/lib/steve/steps.ts#export const steps: readonly Step[] = [
  - steve:src/lib/steve/steps.ts#id: "gather_wood",
  - steve:src/lib/steve/steps.ts#id: "craft_sticks",
  - steve:src/lib/steve/steps.ts#id: "kill_dragon",
  - steve:src/lib/steve/steps.ts#export const getNextStep = (
  - src/clojurecraft/plan.clj#def goals
  - src/clojurecraft/make.clj#defmethod plan/next-intent :needs
related:
  - "[[siblings/_moc|Siblings]]"
  - "[[goals-and-intents]]"
  - "[[make-goals]]"
  - "[[recipe-graph]]"
---

# steve's steps mapped to our goals

steve's chain is a vector of step objects (`id`, `priority`, `canExecute`, `isComplete`, `execute`) sorted by priority; `getNextStep` picks the first runnable, incomplete one every cycle. Ours is a goal table (`plan/goals`) whose goals are answered by `goal-done?` and `next-intent`, with intents as values ([[goals-and-intents]]). The shapes match at the level that matters: both re-derive "done" from the world every tick.

## Key files
- steve `src/lib/steve/steps.ts` - `steps` and `getNextStep`.
- `resources/clojurecraft/goals.edn` - targets `:wood` (priority 1, provides a log), `:kit` (priority 2, provides a crafting table and four sticks) and `:pickaxe` (priority 3, a wooden pickaxe via a placed table).
- `src/clojurecraft/make.clj`, `next-intent :needs` - the needs planner picks gather, craft or place for every target ([[make-goals]], [[recipe-graph]]).

## The map
| steve step (priority) | ours today | issue draft |
|---|---|---|
| `escape_water` (0) | none | 06 water and lava |
| `gather_wood` (1) | `:wood` goal; `:walk` → `:dig` → `:collect` via `wood/gather-next` | 01 |
| `craft_planks` (2) | `:kit` → recipe graph → `:craft` (any `*_planks` recipe for the log held) | 01 |
| `craft_crafting_table` (3) | `:kit` want `[:crafting_table 1]` → `:craft` in the 2x2 | 01 |
| `craft_sticks` (4) | `:kit` want `[:stick 4]` (steve keeps 8) | 01 |
| `craft_wooden_pickaxe` (5) | `:pickaxe` goal (priority 3): the graph in size 3, then place, open and craft in a table (the `:place-table` row and the `:craft` act) | 02 |
| `mine_stone` (6), `craft_stone_pickaxe` (7), `craft_stone_sword` (8) | none | 05 |
| `craft_furnace` (9), `mine_coal` (10), `mine_iron` (11), `smelt_iron` (12) | none | 05, 07 |
| `craft_iron_pickaxe` (13), `craft_bucket` (14), `get_water_buckets` (15) | none | 07 |
| `gather_food` (16) | none (servers run peaceful) | not planned |
| `get_flint_and_steel` (17) | none | 07 |
| `build_nether_portal` (18), `enter_nether` (19) | none | 08 |
| `find_fortress` (20), `kill_blazes` (21) | none | 09, 10 |
| `hunt_endermen` (22) through `kill_dragon` (30) | none | 10 |

## Differences worth knowing
- **Thresholds versus wants.** steve's steps carry reserve thresholds tuned in races (`craft_sticks` complete at 8 sticks; `gather_wood` complete at 5 logs, 20 planks, 12 planks with a table, plus fuel clauses). Ours want exact counts and the recipe graph derives the intermediate crafts, so planks are never a goal of their own.
- **Fuel lives in gather_wood.** steve's `gather_wood.isComplete` encodes smelt fuel (coal, planks or logs while raw iron waits) because fuel was never a need ([[sib-smelt-fuel-deadlock]]); a comment there records 25 planks lost to junk buttons.
- **Dimension gates.** `gather_wood` runs only in the overworld and the Nether steps only in the Nether ([[sib-dimension-change]]); our goals have no dimension yet.
- **A lit portal pins the bot.** `getNextStep` returns only `escape_water` or `enter_nether` while a lit overworld portal exists ([[sib-water-escape]]).
- **steve's steps are 31 rows; the progress count excludes `escape_water`.**

## Limits
The issue-draft column is our roadmap, not steve's. Steps 22-30 are summarised as a range; their sibling state is in [[sib-end-and-dragon]].

## See also
- [[goals-and-intents]] - our planner.
- [[sib-end-and-dragon]] - the late steps.
