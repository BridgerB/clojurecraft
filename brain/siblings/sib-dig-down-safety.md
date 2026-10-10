---
title: Digging down safely
description: The rules both siblings converged on for descending: never dig blindly under the feet (steve stairs), dig every cell under the 0.6-wide footprint (ruststeve), refuse liquid and lava cells and deep drops, never dig obsidian, and judge success by having actually fallen.
type: reference
tags: [siblings, dig, mining, lava, water, ruststeve, steve]
aliases: [dig_down, descendStaircase, stairs down, footprint cells, never dig obsidian]
status: verified
lastUpdated: 2026-10-09
verifiedAgainst: steve c28028b, ruststeve bc575e3
sourceRefs:
  - "ruststeve:src/tasks/mining.rs#pub(crate) async fn dig_down(bot: &mut Bot<'_>) -> bool {"
  - "ruststeve:src/tasks/mining.rs#// only floor(x),floor(z) leaves an adjacent sub-cell holding the bot up → it never"
  - "ruststeve:src/tasks/mining.rs#// NEVER dig obsidian — it's the nether-portal frame. descend_to_y (used by the"
  - "steve:src/lib/steve/tasks/mining/main.ts#export const descendStaircase = async ("
  - "steve:src/lib/steve/tasks/mining/main.ts#// Hazard: water in the step floods the 1-wide shaft and drowns us. Reroute"
related:
  - "[[siblings/_moc|Siblings]]"
  - "[[sib-dig-hardness]]"
  - "[[sib-pathfinder-movements]]"
  - "[[land-physics]]"
---

# Digging down safely

## Key files
- ruststeve `src/tasks/mining.rs`, `dig_down` - support is `(p.y - 0.5).floor()` (physics jitter dips y just under the integer); digs every cell under the 0.6-wide box, because digging only `floor(x), floor(z)` "leaves an adjacent sub-cell holding the bot up" and the caller looped forever; refuses a support cell that is liquid or above liquid, or has lava beside it (water beside is allowed: refusing it wedged descents in wet biomes, 217 relocates); never digs obsidian (it dug through its own portal frame and restarted the cast forever); refuses over a 3-deep air column and near the world floor; reports success only if y dropped by 0.5, since digging stone with no tool does not break it and "returning true there spins".
- steve `tasks/mining/main.ts`, `descendStaircase` - a 2-high staircase that "Never digs the block directly under the bot's feet (avoids blind drops)", checks the next step for lava and for water ("water in the step floods the 1-wide shaft and drowns us"), and re-routes when it walls into lava or gets stuck.

## What they learned
1. The player's footprint is up to four cells; a column dig is not a descent.
2. Success is an observed fall, not a sent packet.
3. Liquids are judged per cell opened, with lava stricter than water.

## What it means here
Our AABB is the same 0.6 by 1.8 ([[land-physics]]); `:stairs-down` (issue #9) checks every cell a stair opens or exposes against the world value before any dig, never digs the support, and counts a stair only when `:player/pos` went down ([[stairs-down]]).

## Limits
steve's `digDownVertical` (land before each dig, the 5× off-ground penalty) is cited by docs/issues/05 but was not opened here.

## See also
- [[sib-dig-hardness]]
