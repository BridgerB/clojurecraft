---
title: World
type: moc
tags: [bot, world]
status: verified
lastUpdated: 2026-10-11
verifiedAgainst: HEAD
related:
  - "[[bot/_moc|Bot pillar]]"
---

# World

- [[chunk-sections]] - chunk columns kept as palette plus longs, indexed on demand; what nil means.
- [[memory-sightings]] - what the bot remembers after a chunk unloads (logs, tables, one pool per chunk), and how the wood goal uses it.
- [[land-physics]] - the pure vanilla movement step and the solidity oracle.
- [[fluid-physics]] - the same step in water and lava: the fluid oracle, the eye rule, the impulse that is the only lift, fall distance.
- [[physics-constants]] - every physics constant with its value and the behaviour tests pin.
- [[blocks-tables]] - id-indexed arrays, passable types, what counts as a log.
- [[memory-edge-cases]] - what memory never records, sticky air, the height window, time zero.
- [[entities]] - every entity tracked: velocity, a living one's health and flags from its metadata, deaths; hostiles, nearest, gone.

## See also
- [[bot/_moc|Bot pillar]]
- [[grass-type-is-grass]] - the solidity trap.
