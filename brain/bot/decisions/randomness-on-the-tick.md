---
title: Randomness on the tick
description: Why rand is a field of the tick event instead of a call inside an intent, and what it buys.
type: decision
tags: [bot, decision, replay]
aliases: [event/rand, no rand in reducers, deterministic replay]
status: verified
lastUpdated: 2026-10-09
verifiedAgainst: 60b624e
sourceRefs:
  - src/clojurecraft/main.clj#defn run-loop
  - src/clojurecraft/intent.clj#defmethod run :walk
related:
  - "[[bot/decisions/_moc|Decisions]]"
  - "[[record-replay]]"
---

# Randomness on the tick

## The choice
The loop puts `:event/rand` (a double) on every tick; the walk intent reads it to pick a detour direction.

## What was rejected
Calling `rand-nth` inside the intent, as the first version did. It made two runs over the same event log diverge, which silently removed the record-and-replay guarantee; a replay that cannot reproduce a failure is worth nothing when debugging a two-hour race.

## What would change the answer
Nothing; any new source of nondeterminism (time, randomness, environment) must enter as an event field for the same reason.

## See also
- [[record-replay]]
