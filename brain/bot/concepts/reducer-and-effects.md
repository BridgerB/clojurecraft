---
title: Reducer and effects
description: How an event becomes a new world plus effects, where the clock and randomness enter, and why the phase has one source of truth.
type: explanation
tags: [bot, concepts, reducer, effects]
aliases: [step function, event loop, pure reducer, effects as data]
status: verified
lastUpdated: 2026-10-09
verifiedAgainst: 60b624e
sourceRefs:
  - src/clojurecraft/game.clj#defn step
  - src/clojurecraft/game.clj#defn emit
  - src/clojurecraft/game.clj#defn compose
  - src/clojurecraft/main.clj#defn run-loop
related:
  - "[[bot/concepts/_moc|Concepts]]"
  - "[[world-value]]"
  - "[[randomness-on-the-tick]]"
---

# Reducer and effects

`(step world event)` returns the next world. Events are maps: `{:event/kind :start}`, `{:event/kind :packet :event/packet p}`, `{:event/kind :tick :event/now ms :event/rand r}`, `{:event/kind :go}`, `{:event/kind :closed :event/reason s}`. Nothing inside a reducer reads a clock or calls `rand`; both arrive on the tick event, which is what makes a run replayable.

## Key files
- `game.clj`, `step` - dispatches on `:event/kind` through the `on-event` multimethod.
- `game.clj`, `emit` - queues `{:effect/kind :send ...}` and advances `:bot/phase` through `packet/transitions`; the only place the phase changes.
- `game.clj`, `compose` - folds several reducers left to right; `main/step` is `game/step` then `plan/step`.
- `main.clj`, `run-loop` - `alts!!` over the socket, external events and a 50 ms timer; builds the event, calls `apply-event!`.

## How it works
1. The loop builds one event and swaps the atom with `step`.
2. The reducer appends effects to `:bot/effects`; `emit` is the packet case, `say` the log case.
3. The loop reads the effects, clears them in the atom, and performs each (`a/>!!` to the socket, or stderr).
4. Packet handling is `on-packet`, a multimethod on `[phase name]`; unknown packets are counted under `:stats/unknown`.

## Gotchas
- A handler that needs to send must go through `emit`, or the phase bookkeeping is skipped.
- `compose` passes the same event to every reducer; the planner sees packets too and ignores them.

## See also
- [[goals-and-intents]] - the second reducer.
- [[record-replay]] - the payoff.
