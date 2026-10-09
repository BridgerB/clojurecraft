---
title: Resting vertical velocity
description: Symptom: a settle check that waits for zero velocity never fires. Cause: vanilla keeps vy at -0.0784 while standing.
type: reference
tags: [bot, gotcha, physics]
aliases: [vy never zero, settle never fires, stillness check]
status: verified
lastUpdated: 2026-10-09
verifiedAgainst: 60b624e
sourceRefs:
  - src/clojurecraft/physics.clj#defn step
  - src/clojurecraft/intent.clj#defn- still?
  - test/clojurecraft/physics_test.clj#vanilla keeps a resting vy of -0.0784
related:
  - "[[bot/gotchas/_moc|Gotchas]]"
  - "[[land-physics]]"
  - "[[dig-timeline]]"
---

# Resting vertical velocity

Symptom: the bot reaches a tree, looks at the log, and never starts digging; the plan times out in `:settle`.

## Root cause
Each tick applies gravity and drag after the move: `vy = (vy - 0.08) * 0.98`. On the ground the sweep clips vy to zero, then gravity makes it -0.0784 again, every tick, forever. A stillness check over all three velocity components can never pass.

## Fix
`still?` checks horizontal components only plus `:player/on-ground?`. The physics test pins the resting value.

## See also
- [[dig-timeline]]
