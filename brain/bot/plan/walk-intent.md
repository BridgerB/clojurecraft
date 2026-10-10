---
title: Walk intent
description: How :walk steers toward a block until within reach, how it detects being stuck (no 0.25-block progress for 2 s), how detours use :event/rand, and when it fails.
type: reference
tags: [bot, plan, intents, walk]
aliases: [:walk, stuck detection, detour, walk-timeout, reach 4.0]
status: verified
lastUpdated: 2026-10-09
verifiedAgainst: 5c7d6c1
sourceRefs:
  - src/clojurecraft/intent.clj#defmethod run :walk
  - src/clojurecraft/intent.clj#def stuck-ticks 40
  - src/clojurecraft/intent.clj#def reach 4.0
  - src/clojurecraft/intent.clj#defn toward
  - test/clojurecraft/plan_test.clj#walks-to-a-far-log
related:
  - "[[bot/plan/_moc|Plan]]"
  - "[[randomness-on-the-tick]]"
  - "[[land-physics]]"
  - "[[goals-and-intents]]"
---

# Walk intent

`{:intent/kind :walk :intent/target [x y z]}` walks straight at the target block's centre until the eye is within `reach` (4.0) of it. There is no pathfinder: steering is "face the target, hold forward, jump when blocked", with random detours when progress stops.

## Key files
- `intent.clj`, `run :walk` - the whole intent; its bookkeeping lives on the intent map (`:intent/started`, `:intent/best-dist`, `:intent/best-tick`, `:intent/detours`, `:intent/detour-until`, `:intent/detour-yaw`).
- `intent.clj`, `toward` - controls: `look-at` from the eye, yaw plus an offset, `:control/forward? true`, `:control/jump?` = last tick's horizontal collision.
- `intent.clj`, constants - `reach` 4.0, `walk-timeout-ticks` 1200 (60 s), `stuck-ticks` 40 (2 s), `detour-ticks` 20 (1 s), `max-detours` 4.

## How it works
1. Distance is eye to block centre. Progress means beating the best distance so far by more than 0.25; the tick of the last progress is `best-tick`.
2. Within reach: set only `:control/look` at the centre (so the next intent, `:dig`, starts already looking) and finish.
3. Failure `:stuck`: more than 1200 ticks since start, or 4 detours used.
4. Stuck (no progress for over 40 ticks) and not already detouring: start a 20-tick detour at yaw offset -70 or +70 degrees, chosen by `(< rand 0.5)` from the tick's `:event/rand`, with jump held; count the detour and reset `best-tick`.
5. Otherwise walk toward the centre, applying the detour offset while one is active.

## Gotchas
- Time is counted in ticks (`:time/tick`), not ms, unlike dig and craft; a stalled tick rate stretches the 60 s timeout.
- A walk started by the gather chain carries `:intent/for :log`, which is what lets the chain continue it into a dig ([[gather-chain]]).
- A failed walk blacklists its target via the planner, so the next search picks another trunk ([[goals-and-intents]]).
- No water, lava, cliff or hole awareness: liquids are passable in [[land-physics]]. Pathfinding is issue #4 ([[sib-pathfinder-movements]]).

## See also
- [[randomness-on-the-tick]] - why the detour reads `:event/rand`.
- [[dig-timeline]] - what follows a walk.
