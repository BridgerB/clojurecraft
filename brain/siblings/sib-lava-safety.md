---
title: ruststeve's lava footing rule
description: The pure check ruststeve runs before standing or stepping near lava (footing, body columns, a 3x3 ring, side drops), and the rule that mining never digs obsidian because it is the portal frame.
type: reference
tags: [siblings, ruststeve, lava, survival, portal]
aliases: [lava_unsafe_here, lava veto, never dig obsidian, lava footing]
status: verified
lastUpdated: 2026-10-09
verifiedAgainst: ruststeve bc575e3
sourceRefs:
  - ruststeve:src/tasks/lava_move.rs#pub(crate) fn lava_unsafe_here(bot: &Bot, allow_side: Option<(i32, i32)>) -> Option<String> {
  - ruststeve:src/tasks/mining.rs#NEVER dig obsidian — it's the nether-portal frame.
related:
  - "[[siblings/_moc|Siblings]]"
  - "[[sib-portal-mold]]"
  - "[[sib-water-traps]]"
---

# ruststeve's lava footing rule

Lava deaths at the portal site came from standing where a block below or beside was lava. ruststeve answers with one pure predicate that returns the reason a cell is unsafe, or none.

## Key files
- ruststeve `src/tasks/lava_move.rs`, `lava_unsafe_here` - the veto.
- ruststeve `src/tasks/mining.rs` - the descent cell check that refuses obsidian.

## How the veto works
Given the bot's feet y `fy` and column `(cx, cz)`, a position is unsafe when:
1. **Footing**: the floor at `fy-1` is lava, or is non-solid (air or water) with lava 2 to 5 below. Water or air over no lava is not this rule's hazard; vetoing it stalled descents over water.
2. **Body columns**: any of the four corners of the 0.6-wide body (offsets ±0.299) has lava at `fy-1`, `fy` or `fy+1`.
3. **Body ring**: any cell of the 3x3 around the column has lava at `fy` or `fy+1`.
4. **Side drops**: a cardinal neighbour (except an allowed side) is open air with no solid floor and lava 1 to 4 below it.

The result is a string reason (`footing ...`, `lava in a body column ...`), so a refusal is logged with its cause.

## Never dig obsidian
The descent helper refuses any cell holding obsidian: `descend_to_y` had dug through a finished frame block, the frame check read 9/10, and the whole cast restarted forever.

## What it means here
A pure `(lava-unsafe? world pos)` over block lookups is exactly our shape: physics already treats unknown blocks as solid; this adds a hazard oracle the walk and dig intents consult, and `obsidian` joins a never-break set the dig intent checks before START.

## Limits
Callers and the `allow_side` use (stepping off toward a planned side) were not traced.

## See also
- [[sib-portal-mold]] - the cast this protects.
- [[sib-water-traps]] - the water half of survival.
