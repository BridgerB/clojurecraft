---
title: Main loop
description: How main.clj drives the bot - the composed step, run-loop's alts over socket, events and a 50 ms timer, apply-event!'s swap-then-drain, stop conditions, the hold period and exit codes.
type: reference
tags: [bot, runtime, loop, io]
aliases: [run-loop, apply-event!, -main, main/step, tick timer, hold-ms]
status: verified
lastUpdated: 2026-10-10
verifiedAgainst: b9e38a1
sourceRefs:
  - src/clojurecraft/main.clj#defn apply-event!
  - src/clojurecraft/main.clj#defn run-loop
  - src/clojurecraft/main.clj#defn -main
  - src/clojurecraft/main.clj#(game/compose game/step plan/step))
related:
  - "[[bot/runtime/_moc|Runtime]]"
  - "[[reducer-and-effects]]"
  - "[[result-line]]"
  - "[[conn-framing]]"
---

# Main loop

`main.clj` is the one place with the atom, the clock, randomness and effects. `main/step` is `(game/compose game/step plan/step)`; requiring `clojurecraft.wood` and `clojurecraft.make` registers the goals.

## Key files
- `main.clj`, `apply-event!` - the epochal write: tap the event to the recorder (if any), `swap!` the world with `step`, read `:bot/effects`, clear them in the atom, perform each (`:send` → `a/>!!` onto conn's `:out`; `:log` → timestamped stderr line).
- `main.clj`, `run-loop` - `alts!!` over the socket's `:in`, the external `events` channel, and a timeout to the next tick.
- `main.clj`, `-main` - argument parsing, wiring, RESULT, hold, exit.

## How it works
1. `-main` parses `--key value` pairs (host 127.0.0.1, port 25571, name `Clj_wood`, until `wood`, timeout 120 s, hold 0), opens the connection, creates `events` (buffer 16) and the atom from `game/init`, starts the harness thread when `--until` is planned ([[harness-landing]]), and applies the `:start` event carrying the connection (`:start/host :start/port :start/name`).
2. `run-loop`: a packet from `:in` becomes a `:packet` event (nil, the closed channel, becomes `:closed` "socket closed"); anything from `events` is applied as-is (the harness's `:go`); a timeout becomes `{:event/kind :tick :event/now (System/currentTimeMillis) :event/rand (rand)}`. Ticks are scheduled on a fixed 50 ms grid (`next-tick` advances only after a tick), so packet bursts do not delay the clock.
3. It stops when `stop?` holds, or the world has `:bot/closed` or `:bot/disconnected`. `stop?` is: the goal predicate (`plan/done?` for planned goals, `:player/loaded?` for `--until play`), a planned plan that failed, or the wall-clock deadline.
4. `-main` prints `RESULT` ([[result-line]]). If ok and `--hold-ms` is positive, it runs the loop again until the hold expires so an outside judge can read the live player. Then it closes the recorder and socket and exits 0 when ok, else 1.

## Gotchas
- The deadline check reads the wall clock inside `stop?`, outside the reducer; it never influences the world value, so replays are unaffected.
- Effects are drained after every event, so a reducer can rely on `:bot/effects` being empty at the start of a step.
- `replay` (the `clojure -M:replay` entry) folds `main/step` over a recording from a fresh `game/init` named `Clj_replay`.

## See also
- [[record-replay]] - the tap.
