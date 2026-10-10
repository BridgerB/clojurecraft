---
title: Telemetry watch
description: How --telemetry records every change of the world from a watch on the atom - a pure diff of successive values, offered to a bounded channel and written on its own thread - so observing a run can never slow or change it.
type: reference
tags: [bot, runtime, telemetry, watch]
aliases: [--telemetry, watch.clj, telemetry!, changes, add-watch, telemetry/changed, telemetry/patched, dashboard]
status: verified
lastUpdated: 2026-10-10
verifiedAgainst: 8d06614
sourceRefs:
  - src/clojurecraft/watch.clj#defn changes
  - src/clojurecraft/watch.clj#defn telemetry!
  - src/clojurecraft/watch.clj#def ignored
  - src/clojurecraft/main.clj#watcher (some->> telemetry (watch/telemetry! world*))
  - test/clojurecraft/watch_test.clj#telemetry-watches-a-whole-run-without-touching-it
  - docs/hickey.md#Telemetry becomes a watcher that diffs successive values and writes facts.
related:
  - "[[bot/runtime/_moc|Runtime]]"
  - "[[main-loop]]"
  - "[[record-replay]]"
  - "[[information-model]]"
---

# Telemetry watch

`--telemetry path` adds a watch to the world atom. Each time the loop swaps in a new value, the watch sees the old and new values whole (never a torn state), computes what changed, and offers that line to a bounded channel; a thread of its own writes the lines as EDN. Nothing in `game`, `plan` or `intent` mentions it, which is the essay's test for an observer.

## How it works
1. `watch/changes old new` is pure: plain attributes that differ go under `:telemetry/changed` (nil when one disappeared); an attribute that is a map before and after goes under `:telemetry/patched` with only the entries that changed. `ignored` skips the clock (changes every tick), the effects (drained by the loop), and big values with their own record (chunks, facts, the block overlay).
2. `line` adds `:telemetry/at` and `:telemetry/tick`.
3. `telemetry!` offers each line with `a/offer!` to a channel of 1024: when the writer falls behind it drops and counts instead of blocking the loop. `main` closes it at the end and logs "telemetry dropped n lines".

## Measured
A live wood run (about 6 s of play) wrote 4,416 lines, 386 KB, dropped 0, and the run's recording still replayed `:identical`. Before map patching the same run wrote 2.5 MB, almost all of it `:stats/unknown` rewritten whole on every unhandled packet.

## Gotchas
- Telemetry is a view, not an input: it is not recorded and a replay does not need it. The recording ([[record-replay]]) is the source of truth; telemetry is for reading a run without folding it.
- A watched run reaches the same world as an unwatched one; `watch_test` asserts it over a sim run.
- A dashboard is the same move: another watch plus a render of the snapshot.

## See also
- [[main-loop]] - the swap the watch observes.
