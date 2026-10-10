---
title: Memory edge cases
description: The corners of the sightings store - what is never recorded, what is recorded as air forever, the 12-block height window, time zero, and blacklist interplay.
type: reference
tags: [bot, world, memory, gotcha]
aliases: [sightings edge cases, watched?, seen-at 0, forgotten logs, height window]
status: verified
lastUpdated: 2026-10-09
verifiedAgainst: 5c7d6c1
sourceRefs:
  - src/clojurecraft/memory.clj#defn watched?
  - src/clojurecraft/memory.clj#defn observe
  - src/clojurecraft/memory.clj#defn nearest-log
  - src/clojurecraft/game.clj#defn- load-chunk
  - test/clojurecraft/game_test.clj#sightings remember logs and their later states
related:
  - "[[bot/world/_moc|World]]"
  - "[[memory-sightings]]"
  - "[[map-memory-not-datalog]]"
---

# Memory edge cases

The mechanism is in [[memory-sightings]]; this note lists the edges.

## Edges
- **Only logs and crafting tables are watched.** `watched?` is `blocks/log?` or `crafting-table?`; `nearest` finds the closest remembered state matching any predicate (used for tables). Ores, water and lava seen in a chunk are not remembered; a block update to a non-log position not already on record is dropped.
- **Air is sticky.** Once a log position is on record, every later update there is kept, including air; nothing removes a position. A re-grown or re-placed log is picked up by the next update or chunk load.
- **A chunk load overwrites only logs.** `remember-column` records the logs it finds and does not record air for positions on record that are no longer logs in the new data. A log broken by someone else while the chunk was unloaded stays a log in memory until a block update or the bot's own dig says otherwise.
- **Chunk load clears the overlay, not memory.** `load-chunk` drops `:world/blocks` entries inside the chunk (the fresh column is authoritative) but sightings stay.
- **Height window.** `nearest-log` skips trunk bottoms more than 12 blocks above or below the eye, and anything beyond the radius (48 from `wood/search-radius`).
- **Time zero.** `:block/seen-at` is `:time/now`, which is 0 until the first tick; sightings from the first chunk batch carry 0.
- **Blacklist.** `nearest-log` removes blacklisted positions before testing `trunk-bottom?`, so a blacklisted bottom does not make the log above it a bottom; that whole trunk is skipped.

## Limits
No eviction: the store grows with every log ever seen. Fine for one goal; a two-hour race is the point where [[map-memory-not-datalog]] is revisited.

## See also
- [[memory-sightings]] - the mechanism.
