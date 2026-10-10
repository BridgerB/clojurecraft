---
title: Record and replay
description: Every event of a run, and the effects each one produced, written as EDN lines; a replay that folds the bot over the file with no server and verifies it asks for exactly the same effects.
type: reference
tags: [bot, tooling, record, replay]
aliases: [--record, clojure -M:replay, run.edn, event log, record/verify, replay/effects identical, determinism check]
status: verified
lastUpdated: 2026-10-10
verifiedAgainst: b9e38a1
sourceRefs:
  - src/clojurecraft/record.clj#defn tap
  - src/clojurecraft/record.clj#defn wire
  - src/clojurecraft/record.clj#defn recorded-result
  - src/clojurecraft/main.clj#defn replayed
  - test/clojurecraft/record_test.clj#a-recording-carries-its-result-and-a-replay-reaches-it
  - src/clojurecraft/record.clj#defn replay
  - src/clojurecraft/record.clj#defn verify
  - src/clojurecraft/record.clj#defn effects
  - src/clojurecraft/game.clj#defn connection
  - test/clojurecraft/record_test.clj#effects-are-recorded-and-verified
  - src/clojurecraft/replay.clj#defn -main
  - src/clojurecraft/main.clj#defn apply-event!
related:
  - "[[bot/tooling/_moc|Tooling]]"
  - "[[reducer-and-effects]]"
  - "[[randomness-on-the-tick]]"
---

# Record and replay

`--record path` makes the loop write every event (`:start`, packets, ticks with their `:event/now` and `:event/rand`, `:go`, `:closed`) as one EDN line each before applying it. `clojure -M:replay path` folds `main/step` over the file from `game/init` and prints a RESULT.

## Key files
- `record.clj`, `tap` - a channel tap, as `docs/hickey.md` describes the recorder: `:write` and `:effects` put one value on a bounded channel (`tap-buffer`, 4096) and a thread of its own prints it, so the loop does no file I/O. A full channel blocks the loop rather than dropping a line, because the recording is the source of truth (telemetry, a view, drops instead). `:close` waits until every line is on disk. Byte arrays print as `#clojurecraft/bytes "base64"`. Events are written through `wire`, which drops `derived` packet keys (`:chunk/column`, decoded on the reader thread): the file keeps the wire bytes and the reducer derives the column again on replay.
- `record.clj`, `replay` - `reduce` with effects cleared after each step.
- `replay.clj`, `-main` - the CLI entry.
- `main.clj`, `apply-event!` - the tap is called before the swap, so the file is exactly what the reducer saw; the channel keeps each event's effects line right after it.

## How it works
- Each event is one EDN line; when applying it produced effects, the next line is `{:record/effects [...]}` (it prints as `#:record{:effects ...}`, so grep for that form). Recordings made before effects were recorded still read; `verify` reports `:record/no-effects` for them.
- `clojure -M:replay` prints RESULT with `:replay/effects :identical` when every event made the reducer ask for exactly the recorded effects, or the first `:record/mismatch` (index, event, recorded, replayed). A live table run recorded 131 effect lines and replayed identical.
- The `:start` event carries the connection (`:start/host :start/port :start/name`), so the replay's starting world needs no outside arguments; before that, replay began from placeholder values and the very first handshake effects differed (which is how the hidden input was found).
1. A live wood run produced an 8 MB file (4320 events, 263 chunk packets); it replays in under 4 s with the same intents, the same pickup event and `:ok true`.
2. Because the clock, randomness and the connection are event fields, nothing diverges on replay, and `verify` proves it per event.

## Gotchas
- A recording ends at RESULT: `main` writes the RESULT into the file (`{:record/result ...}`, read back by `recorded-result`) and closes the tap before any hold, so the hold for an outside judge is not recorded. `main/replayed` judges the fold with the run's own `--until` and the same `ok?` rule, and reports `:replay/result :identical` when it reaches exactly the RESULT the run printed (else the recorded one, to compare). Older recordings without a result report `:not-recorded`.
- Harness `:go` is an external event and is recorded like any other.

## See also
- [[gym-on-runners]]
