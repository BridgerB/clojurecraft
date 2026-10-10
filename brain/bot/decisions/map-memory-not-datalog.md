---
title: Map memory, not Datalog yet
description: Why sightings live in a plain map inside one namespace, and the condition under which DataScript replaces it.
type: decision
tags: [bot, decision, memory]
aliases: [why no DataScript, Datalog later, sightings as a map]
status: verified
lastUpdated: 2026-10-09
verifiedAgainst: 60b624e
sourceRefs:
  - src/clojurecraft/memory.clj#Storage is a plain map; a
  - deps.edn#org.clojure/core.async
related:
  - "[[bot/decisions/_moc|Decisions]]"
  - "[[memory-sightings]]"
---

# Map memory, not Datalog yet

## The choice
`:world/sightings` is `{[x y z] {:block/state :block/seen-at}}` and every query over it is a function in `memory.clj`.

## What was rejected
DataScript (in-memory Datalog) now. The only queries so far are positional (nearest log of a kind), which Datalog does not index and a `reduce` answers; adding a dependency and a schema for that bought nothing measurable. The namespace boundary keeps the swap mechanical: callers use `remember-column`, `observe`, `nearest-log`, never the map shape.

## What would change the answer
The first query that joins across kinds or entities (ore near water near a furnace; which goal last touched this position), expected around issue #6. Note also that DataScript has no history; "as of" queries would need a sighting-per-observation layout either way.

## See also
- [[memory-sightings]]
