---
title: Dig timeline
description: Settle, choose the tool, START, swings every 350 ms, FINISH at 1.15x the block's own time plus 100 ms, then a confirm stage that waits for the server's word (its block update is the break; its ack with the block still standing is a refusal); all as absolute times compared to :time/now.
type: reference
tags: [bot, plan, dig]
aliases: [dig intent, player-action, swing cadence, finish-delay, :confirm, :rejected, :needs-tool, :tool-broke]
status: verified
lastUpdated: 2026-10-14
verifiedAgainst: HEAD
sourceRefs:
  - src/clojurecraft/intent.clj#def finish-margin
  - src/clojurecraft/intent.clj#def swing-every
  - src/clojurecraft/intent.clj#def settle-deadline
  - src/clojurecraft/intent.clj#defn open?
  - test/clojurecraft/plan_test.clj#a-dig-that-never-settles-starts-by-the-deadline
  - src/clojurecraft/intent.clj#def confirm-ticks
  - src/clojurecraft/intent.clj#defn dig-time
  - src/clojurecraft/intent.clj#defn finish-delay
  - src/clojurecraft/intent.clj#defn slowdown
  - src/clojurecraft/game.clj#defn clocked
  - test/clojurecraft/plan_test.clj#a-slow-server-stretches-the-dig
  - src/clojurecraft/intent.clj#defn choose-tool
  - src/clojurecraft/intent.clj#defn stowed-tool
  - test/clojurecraft/sim_test.clj#a-pickaxe-in-the-main-inventory-is-put-in-hand
  - src/clojurecraft/intent.clj#defn penalties
  - src/clojurecraft/intent.clj#defn still?
  - src/clojurecraft/intent.clj#defn exposes-fluid?
  - src/clojurecraft/intent.clj#defn fluid-over-falling?
  - test/clojurecraft/sim_test.clj#a-tunnel-never-cuts-under-gravel-under-water
  - src/clojurecraft/intent.clj#defmethod run :dig
  - src/clojurecraft/terrain.clj#defn predict
  - src/clojurecraft/terrain.clj#defn forget-stale
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

The `:dig` intent has three stages. `:settle`: look at the block, refuse at once if it is gone (`open?`: air, water or lava; anything else, a wall, slab or dripstone a build's clear picks or a plant in a bucket's ray, is dug like rock, since sim race 38665691522 world R3/43, issue #91, failed a cast :target-gone on one), out of reach, a tiered block no held tool of its kind reaches (`:needs-tool`), obsidian, or a block with water or lava in any of its six neighbours, or resting on gravel or sand stacked over it (`fluid-over-falling?`: the stack would fall into the cut and the fluid follow; gym batch iron-a4 run 1 tunnelled under a riverbed and the river came down after the gravel), unless the intent says `:intent/exposes-ok?` (`:unsafe`, [[water-escape]]); require three consecutive still ticks (horizontal velocity under 0.05, on ground) and 150 ms since the stage began (`settle-ms`; 500 until gym batch solid-b1), or `settle-deadline` (2 s) since it began without them: the server asks for no stillness, and a bot that never reads as still (race 38477665442 bot Clj_r12 stood on a moss carpet the physics has no shape for, the server putting it back on the carpet every tick; Clj_r19 had fallen through a guessed air) waited in `:settle` for the rest of its run (`a-dig-that-never-settles-starts-by-the-deadline`); then choose the tool (`choose-tool`), send `set-carried-item` for its hotbar slot (0 for the hand), `player-action` status `:start-destroy` (0, START; `packet/value :dig`) with the face nearest the eye (`face-toward`, named as `packet/value :face` numbers it), and a `swing`. `:digging`: swing every 350 ms; at `finish-at` (START + `finish-delay` of the block's own time with that tool) send `player-action` status 2 (FINISH) with the next sequence number. `:confirm`: up to `confirm-ticks` (60, 3 s) for the server's word: the target no longer in its START state (the server's `block-update`, air or what fell into the cell) ends the dig; the `block-changed-ack` for the FINISH sequence with the block still standing is waited on (a loaded server acks first and breaks seconds later by its own clock, vanilla's delayed destroy, issue #64), and the block still standing when the ticks run out fails it `:rejected`, whether the server put the block back or said nothing more; silence for the whole 60 ticks trusts the overlay (the target set to air locally as a guess, `terrain/predict`: the cell in `:world/unconfirmed` until the server's block update) and ends it.

## Key files
- `intent.clj`, `dig-time` - `dig/ms` for the block, the held item and the penalties in force; 3000 (a log by hand) for a block the tables do not know ([[hardness-and-tools]]).
- `intent.clj`, `penalties` - `{:off-ground? :eyes-in-water?}` from `:player/on-ground?` and `:player/eye-in-water?`; each divides the speed by five ([[hardness-and-tools]]).
- `intent.clj`, `finish-delay` - `ms * 1.15 * slowdown + 100`: 3550 for a log by hand, 1442 for stone with a wooden pickaxe, at 20 server ticks a second ([[early-finish-aborts]]).
- `intent.clj`, `slowdown` - how much longer than 50 ms the server's ticks take now (`:server/tick-ms`, at least 1). The server counts a dig's progress in its own ticks, so a loaded server breaks the block later by the clock: FINISH and the `:confirm` window (`confirm-ticks` times it) stretch by it. `game/clocked` reads `:server/tick-ms` from the set-time packets' spacing (one every 20 server ticks, its fields undecoded), halfway to each new reading. Race 38665691517 (issues #88, #144, #64: 10 of runner 1's 33 bots) ran its server at 130 ms a tick; the bot finished digs by its own clock and the server left the blocks standing, the same cell three times, and the staircases failed `:rejected` (`a-slow-server-stretches-the-dig`).
- `intent.clj`, `choose-tool` - `dig/best-tool` over the hotbar; `[0 nil]` for the hand; `:needs-tool` when the block needs a tier no held tool of its kind reaches.
- `intent.clj`, `swing-every` 350 ms, `confirm-ticks` 60.
- `intent.clj`, `still?` - horizontal-only stillness, on the ground or afloat in a liquid; see [[resting-vertical-velocity]].
- `intent.clj`, `run :dig` - starts the stage clock; `dig-stage` is the stages, one method each (`:settle`, `:digging`, `:confirm`), as `craft/stage` is the craft's.

## Gotchas
- A tool that vanishes from its slot mid-dig (it broke) fails the dig `:tool-broke`; the next dig chooses again.
- The server sends the breaker a `block-update` (state 0) then `block-changed-ack`, in that order (observed live on 26.1.2; the vanilla client's own prediction reverts at the ack unless an update came first), so the ack alone is the server's word that it did not break the block: a FINISH it found early (its own tick count behind the bot's clock under lag) and queued behind another one still pending, which it then drops, or a refusal it answers with the block put back ([[early-finish-aborts]]). Until race 38477665442 the ack alone ended the dig and the target was overlaid as air at FINISH: on runner 9, with 25 bots on one server, the acks came 200-300 ms late and the breaks later or never, the stairs believed their cells open, walked into rock the server still had there, and were put back by it ten times a second until the step timed out, `:stair-not-taken` three times (issues #59, #66; `a-restored-block-after-finish-is-a-refused-break`, `a-stair-the-server-did-not-cut-is-dug-again`). The staircase digs a rejected cell again ([[stairs-down]]). A loaded server acks first and breaks seconds later (delayed destroy); the one guess left, the silence overlay, stays unconfirmed until its update: a placement waits for it ([[place-intent]]), and a guess the server never answers is dropped after `terrain/unconfirmed-ms` (`forget-stale`, on the tick): race 38477665442 bot Clj_r19's physics fell through a guessed air the server kept as stone, teleported back every tick, and its next dig waited to be still for the rest of the run.
- Only hotbar slots are considered: moving a tool from the main inventory is window-click work the dig does not do. The executors that start digs do it first: `intent/stowed-tool` finds a tool only the main inventory holds, and the staircase (`stairs/dig-next`) and the mine act start an `:equip` intent ([[place-intent]]) before the dig. With the hotbar full of what mining picked up, a crafted pickaxe lands in the main inventory: gym batch iron-a3 runs 1 and 3 held one there and stopped `:no-pickaxe` (`a-pickaxe-in-the-main-inventory-is-put-in-hand`, `ore-is-mined-with-a-pickaxe-from-the-main-inventory`).
- The property over every breakable block and every tool holds FINISH never early, no START for an unreachable tier, and consecutive sequences.

## See also
- [[hardness-and-tools]] - where the times come from.
- [[early-finish-aborts]] - why the margin exists.
