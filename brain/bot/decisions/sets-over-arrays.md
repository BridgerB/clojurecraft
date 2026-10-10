---
title: Sets over arrays
description: Why block predicates are plain sets of state ids and not primitive boolean arrays, with the measurement over a recorded run, and the rule for type hints that came with it.
type: decision
tags: [bot, decision, performance, style]
aliases: [boolean arrays, solid-flags, primitive hints, type hints, profiler, reflection]
status: verified
lastUpdated: 2026-10-10
verifiedAgainst: 8caa7f1
sourceRefs:
  - src/clojurecraft/blocks.clj#defn states-where
  - "docs/hickey.md#type hints and primitive arrays only where a profiler pointed"
related:
  - "[[bot/decisions/_moc|Decisions]]"
  - "[[blocks-tables]]"
---

# Sets over arrays

## The choice
`solid?`, `log?` and `leaves?` are `contains?` on sets of state ids, and `row` is a map lookup. Primitive type hints (`^long`, `^double`) are gone from `blocks`, `physics`, `inventory` and `window`. Type hints stay only on Java interop, where they remove reflection: after this change no namespace emits a reflection warning (checked with `*warn-on-reflection*` over every namespace).

## What was rejected
Boolean and object arrays indexed by state id, with `^long` hints, which the first version used for "the physics hot path". `docs/hickey.md` allows type hints and primitive arrays "only where a profiler pointed", and none had. Measured on 2026-10-10 by replaying one recorded live wood run (7,661 events, 213 chunks, which exercises chunk memory scans and per-tick physics) after a warm-up, five times each:

| Lookup | Runs (ms) | Median |
|---|---|---|
| boolean arrays | 166 169 250 157 192 | 169 |
| sets | 155 209 142 166 101 | 155 |

No measurable difference, so the plainer value wins.

## What would change the answer
A profile of a long race showing block predicates on the hot path. Then arrays come back, with that profile cited here.

## See also
- [[blocks-tables]]
