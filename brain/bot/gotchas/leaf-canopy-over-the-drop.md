---
title: Leaf canopy over the drop
description: Symptom - the log breaks, the drop lands, and collect times out three times while the bot jumps in a corner. Cause - a low oak canopy (leaves one block above ground) between the bot and the drop blocks a 1.8-tall player.
type: reference
tags: [bot, gotcha, wood, collect, leaves]
aliases: [low canopy, leaves block the drop, collect stuck in corner, not-picked-up three times]
status: verified
lastUpdated: 2026-10-10
verifiedAgainst: 5c7d6c1
sourceRefs:
  - src/clojurecraft/intent.clj#defn step?
  - test/clojurecraft/sim_test.clj#a-drop-on-a-ledge-under-leaves-is-reached
  - src/clojurecraft/intent.clj#defn blocker
  - test/clojurecraft/sim_test.clj#the first CI failure: oak leaves one block above the ground between the bot and the trunk;
  - src/clojurecraft/intent.clj#Leaves: hardness 0.2 → 6 ticks.
related:
  - "[[bot/gotchas/_moc|Gotchas]]"
  - "[[collect-intent]]"
  - "[[gather-chain]]"
  - "[[drop-under-the-trunk]]"
---

# Leaf canopy over the drop

Symptom (first CI failure of the crafting branch): `intent :collect failed: :not-picked-up` three times and the plan fails, while the bot jumps against something next to the trunk. Diagnosed by replaying the run's recording.

## Root cause
Oak leaves one block above the ground stood between the bot and the trunk. The drop landed under them. The player is 1.8 tall, so the leaf at head height blocks it even though the ground is clear, and walking (with jump on collision) cannot pass. Leaves are solid in our physics ([[blocks-tables]]).

## Fix
`:collect` notices a stall (horizontal collision, no progress for 1 s), asks `blocker` for a leaf at head or feet height in the direction of the goal, and ends with `:intent/blocked-by`. The gather chain digs that leaf (300 ms by hand: `dig-time` gives leaves hardness 0.2 → 6 ticks) and resumes the collect, up to six leaves per drop ([[gather-chain]]). The dig intent's `:target-gone` test became "no longer solid" rather than "no longer a log" so it can dig leaves.

## The second variant: a ledge under the canopy
Recorded live on 2026-10-10 and diagnosed by folding the recording at the REPL: the drop lay one block up a ledge, with oak leaves one block above head height over the ledge and over the bot. Every jump hit the leaves before rising a full block, so the bot pressed against the step for 11 s, three collects in a row. `blocker` looked only at feet and head height, where there was nothing. Now, when a cell ahead is a one-block step (`step?`: solid with nothing solid on top, so a trunk is a wall, not a step), it also looks one above the head over the step and over the player. `a-drop-on-a-ledge-under-leaves-is-reached` reproduces the geometry in the sim and fails without the over-the-player check.

## See also
- [[drop-under-the-trunk]] - the previous drop-placement failure.
