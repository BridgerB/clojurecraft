---
title: Dig timeline
description: Settle, START, swings every 350 ms, FINISH at 1.35x plus 200 ms, then the local air overlay; all as absolute times compared to :time/now.
type: reference
tags: [bot, plan, dig]
aliases: [dig intent, player-action, swing cadence, finish-after]
status: verified
lastUpdated: 2026-10-09
verifiedAgainst: 60b624e
sourceRefs:
  - src/clojurecraft/intent.clj#def finish-after
  - src/clojurecraft/intent.clj#def swing-every
  - src/clojurecraft/intent.clj#defn- still?
  - src/clojurecraft/intent.clj#defmethod run :dig
related:
  - "[[bot/plan/_moc|Plan]]"
  - "[[early-finish-aborts]]"
  - "[[mc-dig-and-pickup]]"
---

# Dig timeline

The `:dig` intent has two stages. `:settle`: look at the block, require three consecutive still ticks (horizontal velocity under 0.05, on ground) and 500 ms since the stage began, then send `set-carried-item 0`, `player-action` status 0 (START) with the face nearest the eye, and a `swing`. `:digging`: swing every 350 ms; at `finish-at` send `player-action` status 2 (FINISH) with the next sequence number and overlay the target as air locally.

## Key files
- `intent.clj`, `finish-after` - `dig-ms * 1.35 + 200`; with `dig-ms` 3000 (a log by hand) that is 4250 ms.
- `intent.clj`, `swing-every` - 350 ms.
- `intent.clj`, `still?` - horizontal-only stillness; see [[resting-vertical-velocity]].
- `intent.clj`, `run :dig` - the stages.

## Gotchas
- The server does not echo a block update to the breaker, so the local overlay is the only record that the block is gone until memory is re-observed.
- `dig-ms` is a constant today; real hardness and tools are issue #9.

## See also
- [[early-finish-aborts]] - why the margin exists.
