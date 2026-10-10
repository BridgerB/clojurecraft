---
title: Dig timeline
description: Settle, choose the tool, START, swings every 350 ms, FINISH at 1.35x the block's own time plus 200 ms, then a confirm stage that waits for the server's ack or its refusal; all as absolute times compared to :time/now.
type: reference
tags: [bot, plan, dig]
aliases: [dig intent, player-action, swing cadence, finish-delay, :confirm, :rejected, :needs-tool, :tool-broke]
status: verified
lastUpdated: 2026-10-10
verifiedAgainst: 56441f5
sourceRefs:
  - src/clojurecraft/intent.clj#def finish-margin
  - src/clojurecraft/intent.clj#def swing-every
  - src/clojurecraft/intent.clj#def confirm-ticks
  - src/clojurecraft/intent.clj#defn dig-time
  - src/clojurecraft/intent.clj#defn finish-delay
  - src/clojurecraft/intent.clj#defn choose-tool
  - src/clojurecraft/intent.clj#defn penalties
  - src/clojurecraft/intent.clj#(and (:player/on-ground? world) (< (abs vx) 0.05) (< (abs vz) 0.05))
  - src/clojurecraft/intent.clj#defmethod run :dig
  - test/clojurecraft/plan_test.clj#stone-is-dug-with-the-pickaxe-in-its-slot
  - test/clojurecraft/plan_test.clj#a-restored-block-after-finish-is-a-refused-break
  - test/clojurecraft/props_test.clj#a-dig-with-any-tool-on-any-block-keeps-the-rules
related:
  - "[[bot/plan/_moc|Plan]]"
  - "[[hardness-and-tools]]"
  - "[[early-finish-aborts]]"
  - "[[mc-dig-and-pickup]]"
---

# Dig timeline

The `:dig` intent has three stages. `:settle`: look at the block, refuse at once if it is gone, out of reach or a tiered block no held tool of its kind reaches (`:needs-tool`); require three consecutive still ticks (horizontal velocity under 0.05, on ground) and 500 ms since the stage began; then choose the tool (`choose-tool`), send `set-carried-item` for its hotbar slot (0 for the hand), `player-action` status 0 (START) with the face nearest the eye, and a `swing`. `:digging`: swing every 350 ms; at `finish-at` (START + `finish-delay` of the block's own time with that tool) send `player-action` status 2 (FINISH) with the next sequence number and overlay the target as air locally. `:confirm`: up to ten ticks for the server's answer; a `block-changed-ack` for the FINISH sequence ends the dig, the block back at its START state fails it `:rejected`, and silence after ten ticks trusts the overlay.

## Key files
- `intent.clj`, `dig-time` - `dig/ms` for the block, the held item and the penalties in force; 3000 (a log by hand) for a block the tables do not know ([[hardness-and-tools]]).
- `intent.clj`, `penalties` - `{:off-ground? ...}` from `:player/on-ground?`; the eyes in water is issue #5.
- `intent.clj`, `finish-delay` - `ms * 1.35 + 200`: 4250 for a log by hand, 1752 for stone with a wooden pickaxe.
- `intent.clj`, `choose-tool` - `dig/best-tool` over the hotbar; `[0 nil]` for the hand; `:needs-tool` when the block needs a tier no held tool of its kind reaches.
- `intent.clj`, `swing-every` 350 ms, `confirm-ticks` 10.
- `intent.clj`, `still?` - horizontal-only stillness; see [[resting-vertical-velocity]].
- `intent.clj`, `run :dig` - the stages.

## Gotchas
- A tool that vanishes from its slot mid-dig (it broke) fails the dig `:tool-broke`; the next dig chooses again.
- The server sends the breaker a `block-update` (state 0) then `block-changed-ack`; the local overlay keeps the bot independent of their timing, and the confirm stage reads the restoring `block-update` the server sends instead when it refused the break ([[early-finish-aborts]]).
- Only hotbar slots are considered: moving a tool from the main inventory is window-click work the dig does not do.
- The property over every breakable block and every tool holds FINISH never early, no START for an unreachable tier, and consecutive sequences.

## See also
- [[hardness-and-tools]] - where the times come from.
- [[early-finish-aborts]] - why the margin exists.
