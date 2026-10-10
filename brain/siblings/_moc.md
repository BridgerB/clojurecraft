---
title: Siblings pillar
type: moc
tags: [pillar, hub, siblings]
status: verified
lastUpdated: 2026-10-09
verifiedAgainst: steve c28028b, ruststeve bc575e3
related:
  - "[[_index]]"
---

# Siblings pillar

Lessons from steve (`/Users/bridger/Developer/mc/upstream/steve`, c28028b) and ruststeve (`/Users/bridger/Developer/mc/upstream/ruststeve`, bc575e3), each note verified by opening its sibling anchors. The deep corpus is the ten roadmap drafts in `docs/issues/` ([[roadmap-issues]]).

## Correspondence
- [[sib-steve-steps-to-goals]] - steve's 31 steps mapped to our goals and intents, or to the issue that will cover them.

## Crafting and windows (#2, #7)
- [[sib-wood-lock]] - grid items invisible to inventory counts made two steps oscillate forever.
- [[sib-stale-window-clicks]] - clicks into a closed table window were ignored (0/6 vs 6/6).
- [[sib-empty-prediction-clicks]] - empty changedSlots and quiet-waits to stay server-authoritative.
- [[sib-craft-result-take]] - verify slot 0 before taking; stray planks mint buttons.
- [[sib-ingredient-sourcing]] - the bamboo stick trap and picking planks back out of the grid.
- [[sib-ghost-block-placement]] - placements judged locally became ghost blocks; let the server decide.

## Moving and digging (#4, #9)
- [[sib-pathfinder-movements]] - the shared A* move set and costs, and the liquid rules.
- [[sib-astar-budget]] - count search budget in expansions, not milliseconds.
- [[sib-dig-stop-timing]] - an early FINISH aborts the break; the 1.35x + 200 ms rule.
- [[sib-dig-hardness]] - the break formula, penalties, tiers; placeholder hardness failed half the digs.
- [[sib-held-slot-drift]] - re-assert and confirm the held slot before digging.
- [[sib-dig-down-safety]] - how to descend without falling into lava or water.

## Water and lava (#5)
- [[sib-water-traps]] - every water failure both bots hit, with where it was fixed.
- [[sib-water-escape]] - escape as a priority-0 step versus a per-tick breath watchdog.
- [[sib-lava-safety]] - the stand-near-lava check and never dig obsidian.

## Iron kit (#6)
- [[sib-smelt-fuel-deadlock]] - planks burned as fuel capped runs at about 3 ingots; furnace-window rules.
- [[sib-bucket-water-find]] - why fill-water stalled with a full kit, and the water-source rules.

## Portal (#8)
- [[sib-portal-mold]] - ruststeve's mold template, aims, scoop timing, no lava under the footprint.
- [[sib-steve-portal-cast]] - steve's frame order, reliableUse, aim checks, lighting, entry.

## Nether and beyond (#11, #10)
- [[sib-dimension-change]] - respawn as a registry index, per-dimension height, stale columns.
- [[sib-775-attack-packet]] - melee is a dedicated attack packet in 775.
- [[sib-blaze-combat]] - real-time swing pacing, defend first, aim height; how a race goal is declared.
- [[sib-end-and-dragon]] - stronghold, End and dragon are stubs in both siblings.

## See also
- [[_index]]
- [[bot/decisions/_moc|Decisions]] - where sibling lessons became our choices.
