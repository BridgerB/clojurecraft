---
title: Water traps both bots hit
description: The list of water failures from steve and ruststeve: no buoyancy in typecraft, head-in-water at the wrong height, roofed lakes, aquifer scoops, momentary-bob false escapes, and the dry-biome water hunt.
type: reference
tags: [siblings, water, gotcha]
aliases: [water walls, escape water lessons, aquifer trap]
status: draft
lastUpdated: 2026-10-09
verifiedAgainst: steve d98387d, ruststeve bc575e3
sourceRefs:
  - steve:src/lib/typecraft/physics/physics.ts#const gravity = 0.08;
related:
  - "[[siblings/_moc|Siblings]]"
  - "[[land-physics]]"
---

# Water traps both bots hit

From the research for issue #5 (file:line citations are in `docs/issues/06-water-and-lava-survival.md`):

- typecraft has no buoyancy and ignores the jump key in water; the only lift is the wall-collision impulse, so the bot escapes by pressing into a bank or digging a notch.
- ruststeve checked head-in-water at feet+1 instead of the eye (y+1.62) and only ran survival between steps.
- A momentary bob above the surface counted as escaped; the bot sank again.
- Roofed lakes and lily-pad lids; an aquifer scoop that filled a bucket from deep cave water and drowned.
- Dry inland biomes where the furthest steve run hung at fill-water with a full iron kit.

## What it means here
Liquids are passable in our physics today; issue #5 adds in-water movement, a priority-zero escape goal, liquid sightings and path costs. Preemption is a planner change.

## Limits
Draft: one anchor verified against typecraft's constants; the rest are research citations.

## See also
- [[land-physics]]
