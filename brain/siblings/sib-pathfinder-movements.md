---
title: Sibling pathfinder movements
description: The A* move set both siblings use (forward 1, jump-up 2, drop 1 + 0.5 per block up to 4, diagonal at sqrt 2) and the liquid rules ruststeve added after deaths: lava is a wall and lava-adjacent cells are refused, deep water is not entered from dry ground.
type: reference
tags: [siblings, pathfinding, ruststeve, typecraft, lava, water]
aliases: [movements.ts, movements.rs, A* move costs, maxDropDown, lava_around_body, safe_or_break, climb gate]
status: verified
lastUpdated: 2026-10-09
verifiedAgainst: steve c28028b, ruststeve bc575e3
sourceRefs:
  - "steve:src/lib/typecraft/path/movements.ts#/** Forward walk: move into adjacent block at same Y level. */"
  - "steve:src/lib/typecraft/path/movements.ts#/** Jump up: move into adjacent block one Y higher. */"
  - "steve:src/lib/typecraft/path/movements.ts#/** Drop down: walk forward and fall until hitting ground. */"
  - "steve:src/lib/typecraft/path/pathfinder.ts#const DEFAULT_CONFIG: PathfinderConfig = {"
  - "steve:src/lib/typecraft/path/pathfinder.ts#// Vertical arrival gate. A loose `|dy| < 1.5` let a CLIMBING path advance past"
  - "ruststeve:src/path/movements.rs#fn lava_around_body(&self, x: i32, y: i32, z: i32) -> bool {"
  - "ruststeve:src/path/movements.rs#fn safe_or_break(&self, x: i32, y: i32, z: i32, to_break: &mut Vec<(i32, i32, i32)>) -> f64 {"
  - "ruststeve:src/path/movements.rs#// bot can't swim with purpose, and pathing across lakes is how cycle-1 race bots"
  - "ruststeve:CHANGES.md#**Root cause (SDK pathfinder): `move_diagonal` had no lava rule at all.**"
related:
  - "[[siblings/_moc|Siblings]]"
  - "[[walk-intent]]"
  - "[[physics-constants]]"
  - "[[sib-astar-budget]]"
---

# Sibling pathfinder movements

ruststeve's `src/path/` is a port of typecraft's `path/`; both expand A* nodes with the same move set over the block grid, and ruststeve added the liquid rules that its deaths taught.

## Key files
- typecraft `path/movements.ts` - forward: the floor is physical (or liquid), body and head clear or breakable, cost 1 (times `liquidCost` when wet). Jump up: the cell two above the current node clear, the step block physical, cost 2. Drop: from `dy = -2` down to `maxDropDown + 1`, cost `1 + drop * 0.5`; a water landing is allowed at a penalty, "never fall into lava". Diagonal: cost `Math.SQRT2`.
- typecraft `path/pathfinder.ts`, `DEFAULT_CONFIG` - `maxDropDown: 4`, `reachDistance: 0.5`, `stuckTimeout: 3500`, `tickTimeout: 40`. The vertical arrival gate requires the feet to be at or above a waypoint that is above the bot before advancing.
- ruststeve `src/path/movements.rs`, `safe_or_break` - lava is never passable, and a cell with lava beside or under it is refused (-1). `lava_around_body` applies the same test to drop landings after a refill path dropped a bot into a scooped hole ringed by lava (4 deaths in a row).
- the same file, `move_forward` - deep water (floor water and the block under it water) is refused when entering from dry ground, because "pathing across lakes is how cycle-1 race bots drowned gathering wood"; a bot already swimming may still path out.
- ruststeve `CHANGES.md` - `move_diagonal` had no lava rule at all; a diagonal past a lava-floored corner put the bot over lava.

## What they learned
1. Copy the move costs; they work.
2. Every move kind needs the same liquid rules, diagonals and drops included, or the planner finds the one move that lacks them.
3. Arrival must be judged by position after climbing, not by a loose distance.

## What it means here
Our `:walk` is a straight line with a stuck detour ([[walk-intent]]); liquids are passable to our physics ([[physics-constants]]). A future `path` namespace should treat lava rules as a property of every move generator, testable over generated worlds.

## Limits
typecraft's diagonal and parkour/tower/swim moves were not read in full; only the four moves named above.

## See also
- [[sib-astar-budget]] - how long the search may run.
