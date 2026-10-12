---
title: Dig timeline
description: Settle, choose the tool, START, swings every 350 ms, FINISH at 1.15x the block's own time plus 100 ms, then a confirm stage that waits for the server's ack or its refusal; all as absolute times compared to :time/now.
type: reference
tags: [bot, plan, dig]
aliases: [dig intent, player-action, swing cadence, finish-delay, :confirm, :rejected, :needs-tool, :tool-broke]
status: verified
lastUpdated: 2026-10-11
verifiedAgainst: HEAD
sourceRefs:
  - src/clojurecraft/intent.clj#def finish-margin
  - src/clojurecraft/intent.clj#def swing-every
  - src/clojurecraft/intent.clj#def confirm-ticks
  - src/clojurecraft/intent.clj#defn dig-time
  - src/clojurecraft/intent.clj#defn finish-delay
  - src/clojurecraft/intent.clj#defn choose-tool
  - src/clojurecraft/intent.clj#defn stowed-tool
  - test/clojurecraft/sim_test.clj#a-pickaxe-in-the-main-inventory-is-put-in-hand
  - src/clojurecraft/intent.clj#defn penalties
  - src/clojurecraft/intent.clj#defn still?
  - src/clojurecraft/intent.clj#defn exposes-fluid?
  - src/clojurecraft/intent.clj#defn fluid-over-falling?
  - test/clojurecraft/sim_test.clj#a-tunnel-never-cuts-under-gravel-under-water
  - src/clojurecraft/intent.clj#defmethod run :dig
  - src/clojurecraft/intent.clj#defmulti dig-stage
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

The `:dig` intent has three stages. `:settle`: look at the block, refuse at once if it is gone, out of reach, a tiered block no held tool of its kind reaches (`:needs-tool`), obsidian, or a block with water or lava in any of its six neighbours, or resting on gravel or sand stacked over it (`fluid-over-falling?`: the stack would fall into the cut and the fluid follow; gym batch iron-a4 run 1 tunnelled under a riverbed and the river came down after the gravel), unless the intent says `:intent/exposes-ok?` (`:unsafe`, [[water-escape]]); require three consecutive still ticks (horizontal velocity under 0.05, on ground) and 150 ms since the stage began (`settle-ms`; 500 until gym batch solid-b1); then choose the tool (`choose-tool`), send `set-carried-item` for its hotbar slot (0 for the hand), `player-action` status `:start-destroy` (0, START; `packet/value :dig`) with the face nearest the eye (`face-toward`, named as `packet/value :face` numbers it), and a `swing`. `:digging`: swing every 350 ms; at `finish-at` (START + `finish-delay` of the block's own time with that tool) send `player-action` status 2 (FINISH) with the next sequence number and overlay the target as air locally. `:confirm`: up to ten ticks for the server's answer; a `block-changed-ack` for the FINISH sequence ends the dig, the block back at its START state fails it `:rejected`, and silence after ten ticks trusts the overlay.

## Key files
- `intent.clj`, `dig-time` - `dig/ms` for the block, the held item and the penalties in force; 3000 (a log by hand) for a block the tables do not know ([[hardness-and-tools]]).
- `intent.clj`, `penalties` - `{:off-ground? :eyes-in-water?}` from `:player/on-ground?` and `:player/eye-in-water?`; each divides the speed by five ([[hardness-and-tools]]).
- `intent.clj`, `finish-delay` - `ms * 1.15 + 100`: 3550 for a log by hand, 1442 for stone with a wooden pickaxe ([[early-finish-aborts]]).
- `intent.clj`, `choose-tool` - `dig/best-tool` over the hotbar; `[0 nil]` for the hand; `:needs-tool` when the block needs a tier no held tool of its kind reaches.
- `intent.clj`, `swing-every` 350 ms, `confirm-ticks` 10.
- `intent.clj`, `still?` - horizontal-only stillness, on the ground or afloat in a liquid; see [[resting-vertical-velocity]].
- `intent.clj`, `run :dig` - starts the stage clock; `dig-stage` is the stages, one method each (`:settle`, `:digging`, `:confirm`), as `craft/stage` is the craft's.

## Gotchas
- A tool that vanishes from its slot mid-dig (it broke) fails the dig `:tool-broke`; the next dig chooses again.
- The server sends the breaker a `block-update` (state 0) then `block-changed-ack`; the local overlay keeps the bot independent of their timing, and the confirm stage reads the restoring `block-update` the server sends instead when it refused the break ([[early-finish-aborts]]).
- Only hotbar slots are considered: moving a tool from the main inventory is window-click work the dig does not do. The executors that start digs do it first: `intent/stowed-tool` finds a tool only the main inventory holds, and the staircase (`stairs/dig-next`) and the mine act start an `:equip` intent ([[place-intent]]) before the dig. With the hotbar full of what mining picked up, a crafted pickaxe lands in the main inventory: gym batch iron-a3 runs 1 and 3 held one there and stopped `:no-pickaxe` (`a-pickaxe-in-the-main-inventory-is-put-in-hand`, `ore-is-mined-with-a-pickaxe-from-the-main-inventory`).
- The property over every breakable block and every tool holds FINISH never early, no START for an unreachable tier, and consecutive sequences.

## See also
- [[hardness-and-tools]] - where the times come from.
- [[early-finish-aborts]] - why the margin exists.
