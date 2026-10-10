---
title: Memory sightings
description: What the bot remembers after a chunk unloads, how observations supersede, and how the wood goal picks a trunk from memory.
type: reference
tags: [bot, world, memory]
aliases: [sightings, block memory, remembered logs, nearest-log]
status: verified
lastUpdated: 2026-10-10
verifiedAgainst: 095578b
sourceRefs:
  - src/clojurecraft/memory.clj#defn remember-column
  - src/clojurecraft/memory.clj#defn observe
  - src/clojurecraft/memory.clj#defn nearest-log
  - src/clojurecraft/memory.clj#defn trunk-bottom?
  - src/clojurecraft/memory.clj#defn history
  - src/clojurecraft/memory.clj#defn latest
  - src/clojurecraft/wood.clj#defn trunk-target
related:
  - "[[bot/world/_moc|World]]"
  - "[[chunk-sections]]"
  - "[[facts-in-datascript]]"
---

# Memory sightings

`:world/facts` is a DataScript value of observation facts `{:sight/pos [x y z] :sight/state id :sight/at ms}`, appended only when what is seen at a position changes and never retracted. Only watched kinds start a record (logs and crafting tables); a position on record is followed through every change, so a dug log becomes air and both facts remain. `remembered` is the latest fact at a position, `latest` the newest per position, `history` every fact at one.

## Key files
- `memory.clj`, `remember-column` - on chunk load, every watched block in the column becomes a sighting.
- `memory.clj`, `observe` - a block update keeps the fact if watched or already on record.
- `memory.clj`, `nearest-log` - nearest trunk bottom within a radius, skipping a blacklist and logs more than 12 blocks above or below the eye.
- `memory.clj`, `trunk-bottom?` - a remembered log with no remembered log below it.
- `wood.clj`, `trunk-target` - of the logs stacked on that bottom, the one nearest the bot's feet height.

## How it works
1. The wood goal searches memory, not chunks, so a tree seen before a chunk unloaded is still a target.
2. After a dig, `game/set-block` records air at the target, so the next search picks the log above it.

## Gotchas
- `seen-at` is `:time/now`, which is 0 until the first tick; sightings made on the first chunk packets carry 0.
- Storage is a DataScript value of observation facts, appended on change and never retracted; `history` returns every observation at a position and `latest` the newest per position. See [[facts-in-datascript]].

## See also
- [[drop-under-the-trunk]] - why the target is feet height, not the bottom.
