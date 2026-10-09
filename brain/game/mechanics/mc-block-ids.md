---
title: Block state ids
description: The 26.1.2 state ids the code hardcodes or derives: air 0, cave_air 15293, water 86-101, lava 102-117, logs 136-162, grass_block 8-9.
type: reference
tags: [game, blocks, ids]
aliases: [state ids, block ids, log ids, water ids]
status: verified
lastUpdated: 2026-10-09
verifiedAgainst: 26.1.2
sourceRefs:
  - resources/clojurecraft/blocks.edn#[:oak_log :rotated_pillar 136 138]
  - resources/clojurecraft/blocks.edn#[:water :liquid 86 101]
  - resources/clojurecraft/blocks.edn#[:oak_leaves :tinted_particle_leaves 252 279]
  - src/clojurecraft/blocks.clj#defn log?
related:
  - "[[game/mechanics/_moc|Mechanics]]"
  - "[[chunk-sections]]"
---

# Block state ids

Generated from the vanilla `--reports` into `blocks.edn` as `[name type min-state max-state]` rows; `blocks.clj` builds lookup arrays from them.

## Facts
- air 0, cave_air 15293; stone 1; grass_block 8-9 (type `grass`); dirt 10.
- water 86-101, lava 102-117 (type `liquid`).
- logs: oak 136-138, spruce 139-141, birch 142-144, jungle 145-147, acacia 148-150, cherry 151-153, dark_oak 154-156, pale_oak 157-159, mangrove 160-162. `log?` is any name ending in `_log` (this includes stripped logs).
- oak_leaves 252-279 (solid for collision).
- items: oak_log 134; entity type item 71.

## Gotchas
- Ids change with every game version; the pin is the game version, and `scripts/datagen.sh` regenerates the tables.

## See also
- [[grass-type-is-grass]]
