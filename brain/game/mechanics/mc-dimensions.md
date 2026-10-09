---
title: Dimensions
description: Column heights per dimension: the overworld is 384 tall from y -64 (24 sections); the Nether's chunk height is reported as 256 (16 sections) with logical height 128. Draft until read from the jar by us.
type: reference
tags: [game, dimensions, nether]
aliases: [nether height, section count, dimension_type, min_y]
status: draft
lastUpdated: 2026-10-09
verifiedAgainst: 26.1.2
sourceRefs:
  - src/clojurecraft/chunk.clj#def section-count 24
  - src/clojurecraft/chunk.clj#def min-y -64
related:
  - "[[game/mechanics/_moc|Mechanics]]"
  - "[[chunk-sections]]"
---

# Dimensions

## Facts
- Overworld: `min_y` -64, height 384, 24 sections. Verified by decoding live chunks with exactly 24 sections.
- Nether: a research agent read `dimension_type/the_nether.json` inside the bundled jar (`META-INF/versions/26.1.2/server-26.1.2.jar`) and reported `height` 256 (16 sections) with `logical_height` 128; both siblings broke their chunk parser on the dimension change by assuming the overworld shape. Not yet read by us.

## Limits
Draft: the Nether numbers come from issue #11's research, not from a read in this repo. Promote after `datagen` emits `dimensions.edn` from the jar and a test decodes a Nether column.

## See also
- [[chunk-sections]]
