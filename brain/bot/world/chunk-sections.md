---
title: Chunk sections
description: Chunk columns kept as palette plus packed longs and indexed on demand; the 26.1 fluid-count field; what nil from block-at means.
type: reference
tags: [bot, world, chunks]
aliases: [chunk decode, paletted container, block-at, section]
status: verified
lastUpdated: 2026-10-09
verifiedAgainst: 60b624e
sourceRefs:
  - src/clojurecraft/chunk.clj#defn decode
  - src/clojurecraft/chunk.clj#defn section-get
  - src/clojurecraft/chunk.clj#defn block-at
  - src/clojurecraft/chunk.clj#defn section-may-contain?
  - src/clojurecraft/game.clj#defn load-chunk
related:
  - "[[bot/world/_moc|World]]"
  - "[[memory-sightings]]"
  - "[[mc-dimensions]]"
---

# Chunk sections

A column is `{:sections [s0 .. s23]}`; a section is the paletted container straight off the wire: `{:single id}` or `{:bits n :palette [...] :longs long[]}` (palette nil when direct). Nothing is expanded to 4096 entries; `section-get` indexes the bits on demand.

## Key files
- `chunk.clj`, `decode` - 24 sections for the overworld (`section-count`, `min-y` -64); each reads i16 block count, i16 fluid count (new in 26.1), the block container, the biome container.
- `chunk.clj`, `section-get` - `valuesPerLong = 64 / bits`, entries never straddle longs.
- `chunk.clj`, `block-at` - nil when the chunk is not loaded or y is outside the column.
- `chunk.clj`, `section-may-contain?` - palette check that lets scans skip sections.
- `game.clj`, `load-chunk` - stores the column, drops overlay entries in that chunk, records sightings; a decode error is logged, never thrown.

## How it works
1. bits 0: one varint, no longs. 1..8: varint palette length, palette, longs. Above 8: direct global ids (15 bits for blocks).
2. There is no long-count prefix on the wire in 26.x; the count is `ceil(4096 / (64 / bits))`.
3. Index inside a section is `(y << 8) | (z << 4) | x`.

## Gotchas
- `nil` from `block-at` means unknown, not air; physics treats unknown as solid so the bot never falls out of the world.
- The section count is a constant today; the Nether needs a per-dimension value (see [[mc-dimensions]]).

## See also
- [[memory-sightings]] - what survives when a column unloads.
