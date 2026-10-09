---
title: Dimensions
description: Column heights per dimension in 26.1.2 - overworld min_y -64 height 384 (24 sections); Nether min_y 0 height 256 (16 sections) logical 128 with a ceiling; End min_y 0 height 256 - read from the jar's dimension_type JSON.
type: reference
tags: [game, dimensions, nether, end]
aliases: [nether height, section count, dimension_type, min_y, logical_height, coordinate_scale]
status: draft
lastUpdated: 2026-10-09
verifiedAgainst: 26.1.2
sourceRefs:
  - src/clojurecraft/chunk.clj#def section-count 24
  - src/clojurecraft/chunk.clj#def min-y -64
related:
  - "[[game/mechanics/_moc|Mechanics]]"
  - "[[chunk-sections]]"
  - "[[sib-dimension-change]]"
---

# Dimensions

Read on 2026-10-09 from `data/minecraft/dimension_type/{overworld,the_nether,the_end}.json` inside the 26.1.2 inner server jar.

## Facts
| Dimension | `min_y` | `height` | sections | `logical_height` | `has_ceiling` | `coordinate_scale` | other |
|---|---|---|---|---|---|---|---|
| overworld | -64 | 384 | 24 | 384 | false | 1.0 | skylight |
| the_nether | 0 | 256 | 16 | 128 | true | 8.0 | no skylight, fixed time, `water_evaporates`, `fast_lava`, beds explode |
| the_end | 0 | 256 | 16 | 256 | false | 1.0 | `has_ender_dragon_fight` true, fixed time, beds explode |

- Sections per column = `height / 16`; a chunk packet in the Nether carries 16 sections, and `chunk/decode` with its constant 24 would read past them.
- The Nether column is 256 tall but its `logical_height` is 128 (vanilla uses that to bound portal placement and teleports; not checked here), so blocks above y 127 still arrive in chunk packets.
- `coordinate_scale` 8.0 is the overworld-to-Nether distance ratio.
- The overworld numbers are also confirmed by decoding live chunks with exactly 24 sections (`section-count`, `min-y`).

## Limits
Draft only because the jar is not a repo file: the overworld row is anchored, the Nether and End rows are not. They were read by us, not taken from research. Re-check: `unzip -p data/local-server/server-26.1.2.jar META-INF/versions/26.1.2/server-26.1.2.jar > inner.jar && unzip -p inner.jar data/minecraft/dimension_type/the_nether.json`. Promote when datagen emits a `dimensions.edn` from the jar (issue #11) and a test decodes a Nether column.

## See also
- [[chunk-sections]] - where the constant lives.
- [[sib-dimension-change]] - how both siblings broke on this.
