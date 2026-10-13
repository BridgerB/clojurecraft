---
title: Chunk sections
description: Chunk columns kept as palette plus packed longs and indexed on demand; the 26.1 fluid-count field; what nil from block-at means.
type: reference
tags: [bot, world, chunks]
aliases: [chunk decode, paletted container, block-at, section]
status: verified
lastUpdated: 2026-10-12
verifiedAgainst: HEAD
sourceRefs:
  - src/clojurecraft/chunk.clj#defn decode
  - src/clojurecraft/chunk.clj#defn section-get
  - src/clojurecraft/chunk.clj#defn block-at
  - src/clojurecraft/chunk.clj#defn section-may-contain?
  - src/clojurecraft/game.clj#defn load-chunk
  - src/clojurecraft/chunk.clj#defn attach
  - src/clojurecraft/dimension.clj#defn min-y-of-sections
  - test/clojurecraft/chunk_test.clj#a-nether-column-is-sixteen-sections-from-y-zero
related:
  - "[[bot/world/_moc|World]]"
  - "[[memory-sightings]]"
  - "[[mc-dimensions]]"
---

# Chunk sections

A column is `{:sections [s0 ..] :chunk/min-y y}`: as many sections as the server sent (24 from y -64 in the overworld, 16 from y 0 in the Nether and the End); a section is the paletted container straight off the wire: `{:single id}` or `{:bits n :palette [...] :longs long[]}` (palette nil when direct). Nothing is expanded to 4096 entries; `section-get` indexes the bits on demand.

## Key files
- `chunk.clj`, `decode` - every section the bytes hold, and the column's lowest y from `dimension/min-y-of-sections` (`dimensions.edn`, generated from the jar); each reads i16 block count, i16 fluid count (new in 26.1), the block container, the biome container.
- `chunk.clj`, `section-get` - `valuesPerLong = 64 / bits`, entries never straddle longs.
- `chunk.clj`, `block-at` - nil when the chunk is not loaded or y is outside the column.
- `chunk.clj`, `section-may-contain?` - palette check that lets scans skip sections.
- `game.clj`, `load-chunk` - stores the column, drops overlay entries in that chunk, records sightings; a decode error is logged, never thrown.

## How it works
1. bits 0: one varint, no longs. 1..8: varint palette length, palette, longs. Above 8: direct global ids (15 bits for blocks).
2. There is no long-count prefix on the wire in 26.x; the count is `ceil(4096 / (64 / bits))`.
3. Index inside a section is `(y << 8) | (z << 4) | x`.
4. Where: `chunk/attach` decodes on conn's reader thread and puts the column on the packet as `:chunk/column`; `game/load-chunk` uses it, or decodes the wire bytes itself when it is absent. Both paths give the same column, so live runs and replays agree.

## Gotchas
- `nil` from `block-at` means unknown, not air; physics treats unknown as solid so the bot never falls out of the world.
- A column says its own height by its size, so the reader thread decodes without knowing the dimension; `block-at` and `find-blocks` read `:chunk/min-y` (a column without it is an overworld one). ruststeve read Nether columns as overworld ones and saw brick 64 blocks off ([[mc-dimensions]]).

## See also
- [[memory-sightings]] - what survives when a column unloads.
