---
title: Dig timeline
description: Settle, START, swings every 350 ms, FINISH at 1.35x the block's dig time plus 200 ms (logs 3000 ms, leaves 300 ms), then the local air overlay; all as absolute times compared to :time/now.
type: reference
tags: [bot, plan, dig]
aliases: [dig intent, player-action, swing cadence, finish-after]
status: verified
lastUpdated: 2026-10-09
verifiedAgainst: 5c7d6c1
sourceRefs:
  - src/clojurecraft/intent.clj#def finish-after
  - src/clojurecraft/intent.clj#def swing-every
  - src/clojurecraft/intent.clj#defn dig-time
  - src/clojurecraft/intent.clj#(and (:player/on-ground? world) (< (abs vx) 0.05) (< (abs vz) 0.05))
  - src/clojurecraft/intent.clj#defmethod run :dig
related:
  - "[[bot/plan/_moc|Plan]]"
  - "[[early-finish-aborts]]"
  - "[[mc-dig-and-pickup]]"
---

# Dig timeline

The `:dig` intent has two stages. `:settle`: look at the block, require three consecutive still ticks (horizontal velocity under 0.05, on ground) and 500 ms since the stage began, then send `set-carried-item 0`, `player-action` status 0 (START) with the face nearest the eye, and a `swing`. `:digging`: swing every 350 ms; at `finish-at` (START + `finish-delay` of the block) send `player-action` status 2 (FINISH) with the next sequence number and overlay the target as air locally.

## Key files
- `intent.clj`, `dig-time` - ms to break by hand: 300 for any `_leaves` block (hardness 0.2, 6 ticks), else `dig-ms` 3000 (a log, hardness 2).
- `intent.clj`, `finish-delay` / `finish-after` - `dig-time * 1.35 + 200`: 4250 ms for a log, 605 ms for leaves.
- `intent.clj`, `swing-every` - 350 ms.
- `intent.clj`, `still?` - horizontal-only stillness; see [[resting-vertical-velocity]].
- `intent.clj`, `run :dig` - the stages.

## Gotchas
- The server **does** send the breaker a `block-update` (state 0) followed by `block-changed-ack` for the FINISH sequence; observed live on 26.1.2 in `data/runs/table.edn` (2026-10-09: `block-update` for the log, then `block-changed-ack {:sequence 2}`). The earlier belief that it does not echo came from ruststeve and is wrong here. The local air overlay in `run :dig` is still useful: it makes the bot independent of the echo's timing, and the echo then writes the same state. Re-check by replaying the recording (`clojure -M:replay data/runs/table.edn`) or grepping it for `block-update` / `block-changed-ack`. The code comment beside `set-block` in `run :dig` still repeats the old belief.
- `dig-time` knows two kinds; real hardness and tools are issue #9 ([[sib-dig-hardness]]).
- In `:settle` the intent fails `:target-gone` when the target is no longer solid (it used to require a log, which made leaves undiggable).

## See also
- [[early-finish-aborts]] - why the margin exists.
