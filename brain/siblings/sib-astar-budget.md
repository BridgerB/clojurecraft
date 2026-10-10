---
title: A* search budget in ruststeve
description: Why a pathfinder budget should be counted in expansions, not milliseconds: ruststeve's one-shot 2 s A* froze its tick loop for 4 s, the sliced fix measured wall time and searched 0.9 s of 2 s, and the one-shot search (ASTAR_SYNC) won the gyms.
type: decision
tags: [siblings, ruststeve, pathfinding, budget]
aliases: [ASTAR_SYNC, sliced A*, wall-clock budget, LOOP STALL, plan_path]
status: verified
lastUpdated: 2026-10-09
verifiedAgainst: ruststeve bc575e3
sourceRefs:
  - "ruststeve:CHANGES.md#**Breath-alarm latency, mechanism found (race i6 logs):**"
  - "ruststeve:CHANGES.md#**Found: sliced A* budget was wall-clock**"
  - "ruststeve:src/path/astar.rs#/// Search time spent in earlier slices. The total budget counts SEARCH time, not wall time: with"
  - "ruststeve:src/bot/mod.rs#/// One synchronous A* search (cycle 6, decision 9: ASTAR_SYNC won and is the only planner). The sliced"
related:
  - "[[siblings/_moc|Siblings]]"
  - "[[sib-pathfinder-movements]]"
  - "[[randomness-on-the-tick]]"
---

# A* search budget in ruststeve

## The sequence
1. One synchronous A* with a 2000 ms budget, twice per goto, froze ruststeve's tick loop and the breath watchdog inside it for about 4 s (`LOOP STALL 4060 ms`).
2. Fix: 40 ms slices with one tick driven between slices. Its budget was wall-clock, so the 50 ms ticks counted against it and A* searched about 0.9 s of its 2 s; paths the old search found came back as timeouts.
3. A search-time budget (`spent`) was added; then the one-shot search behind `ASTAR_SYNC=1` was measured against the sliced one: portal gym 11/17 vs 8/18, `water_wall_pool` 4/10 vs 1/10. `Bot::plan_path` now says "ASTAR_SYNC won and is the only planner".

## What was rejected (by them, measured)
- A wall-clock slice budget: searched ~45% of the intended work on a loaded 2-CPU box.
- A one-shot search inside the tick loop with no other guard: a 4 s stall of every safety check.

## What it means here
Our reducer cannot block on a search and must not read a clock ([[randomness-on-the-tick]]). A pure `(plan world from goal opts)` with a budget in node expansions is deterministic under replay and immune to both failures; if it must span ticks, its frontier is a value in the intent.

## What would change the answer
A search that cannot finish within one tick's expansion budget even for short goals; then the frontier-in-the-intent version is needed.

## Limits
The slice implementation itself (`Bot::plan_path` before decision 9) is gone from the tree; it is cited from CHANGES.md.

## See also
- [[sib-pathfinder-movements]]
