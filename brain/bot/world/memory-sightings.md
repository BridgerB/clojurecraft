---
title: Memory sightings
description: What the bot remembers after a chunk unloads, how observations supersede, and how the wood goal picks a trunk from memory.
type: reference
tags: [bot, world, memory]
aliases: [sightings, block memory, remembered logs, nearest-log]
status: verified
lastUpdated: 2026-10-12
verifiedAgainst: HEAD
sourceRefs:
  - src/clojurecraft/memory.clj#defn remember-column
  - src/clojurecraft/memory.clj#defn observe
  - src/clojurecraft/memory.clj#defn nearest-log
  - src/clojurecraft/memory.clj#defn trunk-bottom?
  - src/clojurecraft/memory.clj#defn history
  - src/clojurecraft/memory.clj#defn pool-of
  - src/clojurecraft/memory.clj#defn remember-pools
  - src/clojurecraft/memory.clj#defn nearest-surface-water
  - "src/clojurecraft/memory.clj#The latest observation at every position on record in the bot's dimension:"
  - src/clojurecraft/wood.clj#defn trunk-target
  - src/clojurecraft/memory.clj#defn latest-entity
  - test/clojurecraft/memory_test.clj#the-same-place-in-two-dimensions-is-two-places
related:
  - "[[bot/world/_moc|World]]"
  - "[[chunk-sections]]"
  - "[[facts-in-datascript]]"
---

# Memory sightings

`:world/facts` is a DataScript value of observation facts `{:sight/pos [x y z] :sight/state id :sight/at ms}`, appended only when what is seen at a position changes and never retracted. Only watched kinds start a record (logs, memory's `placed-kinds`, crafting tables and furnaces, and its `landmark-kinds`, a fortress's bricks, fences, stairs and spawners and a portal's obsidian and portal blocks); a position on record is followed through every change, so a dug log becomes air and both facts remain. `remembered` is the latest fact at a position, `latest` the newest per position, `history` every fact at one. A position is a place in one dimension: each fact says `:sight/dimension` (one without it, written before #11, is the overworld's), pool facts `:pool/dimension`, and every question is asked in the bot's dimension (`dimension/of`), with a Datalog rule (`in-dimension`) in the queries (`the-same-place-in-two-dimensions-is-two-places`).

## Key files
- `memory.clj`, `remember-column` - on chunk load, every watched block in the column becomes a sighting.
- `memory.clj`, `remember-pools` / `pool-of` - on chunk load, one pool fact per fluid kind the column holds: `{:pool/chunk :pool/kind :pool/pos :pool/at}`, the chunk's highest surface source nearest its centre, appended only when it changed. A real forest recording held 600,000 water cells, 65,000 at the surface, in 213 chunks; per-cell facts would be the stone freeze again ([[memory-edge-cases]]), pools are 293 facts at 1.6 ms a chunk.
- `memory.clj`, `nearest-surface-water` - the nearest pool within a radius and no more than 5 under the eye: a pool deeper than that is cave water, the aquifer trap ([[sib-bucket-water-find]]).
- `memory.clj`, `observe` - a block update keeps the fact if watched or already on record.
- `memory.clj`, `nearest-log` - nearest trunk bottom within a radius, skipping a blacklist and logs more than 12 blocks above or below the eye.
- `memory.clj`, `trunk-bottom?` - a remembered log with no remembered log below it.
- `wood.clj`, `trunk-target` - of the logs stacked on that bottom, the one nearest the bot's feet height.

## How it works
1. The wood goal searches memory, not chunks, so a tree seen before a chunk unloaded is still a target.
2. After a dig, `terrain/set-block` records air at the target, so the next search picks the log above it.
3. Where water or lava is beside the body right now is the terrain's question, never memory's: the escape searches the loaded columns ([[water-escape]]); memory's pools answer "where is water to walk to".

## Gotchas
- `seen-at` is `:time/now`, which is 0 until the first tick; sightings made on the first chunk packets carry 0.
- Storage is a DataScript value of observation facts, appended on change and never retracted; `history` returns every observation at a position and `latest` the newest per position. See [[facts-in-datascript]].

## See also
- [[drop-under-the-trunk]] - why the target is feet height, not the bottom.
