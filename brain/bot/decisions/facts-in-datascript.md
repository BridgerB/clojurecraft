---
title: Facts in DataScript
description: Why the bot's memory is an append-only set of observation facts in a DataScript value queried with Datalog, why it replaced a plain map, and what it costs.
type: decision
tags: [bot, decision, memory]
aliases: [why DataScript, Datalog memory, map-memory-not-datalog, facts with time, sightings store]
status: verified
lastUpdated: 2026-10-10
verifiedAgainst: 9c46e4b
sourceRefs:
  - src/clojurecraft/memory.clj#(def schema
  - src/clojurecraft/memory.clj#defn observation
  - deps.edn#datascript/datascript {:mvn/version "1.8.1"}
  - docs/hickey.md#The version that wins the race is an append-only set of
related:
  - "[[bot/decisions/_moc|Decisions]]"
  - "[[memory-sightings]]"
---

# Facts in DataScript

## The choice
`:world/facts` is a DataScript database value. Each observation is a fact `{:sight/pos :sight/state :sight/at}`, appended only when what is seen at a position differs from the last fact there, never retracted. Questions are Datalog over that value (positions where a kind was ever seen) plus index lookups for "the latest at a position".

## What was rejected
- **The plain map `{pos {:block/state :block/seen-at}}`** that this replaced (the earlier decision, "map memory, not Datalog yet"). It overwrote: a log dug to air left no trace that a log had been there, and every question was a hand-written scan. `docs/hickey.md`, which governs this code, calls for facts with time and a Datalog ("nothing is ever deleted; a block observed as air at a later time simply supersedes, and the history remains"), so the deferral was reversed on 2026-10-10 rather than waiting for the first join.
- **DataScript entity per position, updated in place.** DataScript keeps no history, so updating a position would lose the past exactly like the map; one entity per observation keeps it.

## What it costs
- One dependency (`datascript/datascript`). The unit suite's wall time went from about 9 s to 19 s (the sim enumerations make many observations). Live runs are unaffected: a pickaxe run built 3,779 facts and answered every keep-alive.

## What would change the answer
A query that DataScript cannot answer fast enough on the tick (measure before guessing), or the need for "as of" queries across a whole race, which would argue for storing the transaction time explicitly on every fact (it is already there as `:sight/at`).

## See also
- [[memory-sightings]]
