---
title: Record and replay
description: Every event of a run written as EDN lines, and a replay that folds the bot over the file with no server in seconds.
type: reference
tags: [bot, tooling, record, replay]
aliases: [--record, clojure -M:replay, run.edn, event log]
status: verified
lastUpdated: 2026-10-09
verifiedAgainst: 60b624e
sourceRefs:
  - src/clojurecraft/record.clj#defn tap
  - src/clojurecraft/record.clj#defn replay
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
- `record.clj`, `tap` - the writer; byte arrays print as `#clojurecraft/bytes "base64"`.
- `record.clj`, `replay` - `reduce` with effects cleared after each step.
- `replay.clj`, `-main` - the CLI entry.
- `main.clj`, `apply-event!` - the tap is called before the swap, so the file is exactly what the reducer saw.

## How it works
1. A live wood run produced an 8 MB file (4320 events, 263 chunk packets); it replays in under 4 s with the same intents, the same pickup event and `:ok true`.
2. Because the clock and randomness are event fields, nothing diverges on replay.

## Gotchas
- A recording includes the hold period after RESULT, so counts in the replayed summary are slightly higher than the live RESULT.
- Harness `:go` is an external event and is recorded like any other.

## See also
- [[ci-wood-workflow]]
