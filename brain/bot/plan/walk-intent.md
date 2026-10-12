---
title: Walk intent
description: How :walk plans a route to its target on its first tick and follows it waypoint by waypoint, when a waypoint counts as reached, when the walk plans again (a cell changed, a chunk arrived, 2 s without a waypoint, the route ended short), and when it fails :no-path or :stuck.
type: reference
tags: [bot, plan, intents, walk]
aliases: [:walk, waypoints, follow, waypoint-reached?, route-stale?, :no-path, replans, reach 4.0]
status: verified
lastUpdated: 2026-10-12
verifiedAgainst: HEAD
sourceRefs:
  - src/clojurecraft/intent.clj#defmethod run :walk
  - src/clojurecraft/intent.clj#defn route!
  - src/clojurecraft/intent.clj#defn route-stale?
  - src/clojurecraft/intent.clj#defn waypoint-reached?
  - src/clojurecraft/intent.clj#defn follow
  - src/clojurecraft/intent.clj#defn arrived?
  - src/clojurecraft/intent.clj#defn opening
  - src/clojurecraft/intent.clj#defn goal-of
  - src/clojurecraft/intent.clj#def stuck-ticks 40
  - src/clojurecraft/intent.clj#def max-replans 6
  - src/clojurecraft/intent.clj#def reach 4.0
  - test/clojurecraft/plan_test.clj#walks-to-a-far-log
  - test/clojurecraft/plan_test.clj#a-walk-plans-again-only-when-its-route-breaks
  - test/clojurecraft/plan_test.clj#a-walk-with-no-way-on-fails-no-path
  - test/clojurecraft/sim_test.clj#a-log-across-a-cliff-and-a-pond-is-reached-by-a-route
related:
  - "[[bot/plan/_moc|Plan]]"
  - "[[pathfinder]]"
  - "[[land-physics]]"
  - "[[goals-and-intents]]"
---

# Walk intent

`{:intent/kind :walk :intent/target [x y z]}` walks to within `reach` (4.0, eye to block centre) of the target. On its first tick it plans a route ([[pathfinder]]) and then follows it one waypoint at a time; the straight-line steering with random detours it replaced is gone.

## Key files
- `intent.clj`, `run :walk` - the whole intent. Its bookkeeping lives on the intent map: `:intent/waypoints`, `:intent/route` (how the plan ended), `:intent/at` (the next waypoint's index), `:intent/replans`, `:intent/planned-chunks`, `:intent/best-tick` (the tick a waypoint was last reached), `:intent/started`.
- `intent.clj`, `walk-goal` and `route!` - plans from the feet cell toward `{:goal/kind :near :goal/pos target :goal/range 3}`, or, for a walk to a block mined from beside (`:intent/pickup?`, [[mine-act]]), `:goal/kind :pickup` (within `pickup-range` on the plane, the block at the feet's height or up to `pickup-rise` above; with `:intent/step-down?`, `:goal/step-down?` too, a block up beside it), and counts the plan. A walk up to the open sky (`:intent/for :opening`, `goal-of`) is planned toward path's `:sky` instead, and is over when the feet are under the sky (`arrived?`), not when the mouth is merely in reach.
- `intent.clj`, `waypoint-reached?` - within `waypoint-reach` (0.35) of the waypoint's centre on the plane, and the feet within half a block of its height in both directions.
- `intent.clj`, `follow` - `toward` the waypoint's centre, jumping when it is above the feet and within `jump-near` (1.3), or when blocked.
- `intent.clj`, `route-stale?` - the three reasons to plan again.
- `intent.clj`, `arrived?` - within reach, and for a log the feet no more than `pickup-rise` (2) below it, for a block mined from beside (`:intent/pickup?`) the walk-goal itself (`path/goal-done?`): neither above it nor more than `pickup-rise` below it, and within `pickup-range` (octile 1.5: a neighbour, diagonals too) of its cell, or with `:intent/step-down?` a straight neighbour a block up: where the drop comes to rest inside the pickup box; for water to scoop (`:intent/for :scoop`), right beside the source with the feet level with the cell over it or a block lower ([[scoop-intent]]). Within reach alone, a walk to ore in a tunnel's wall ended round the corner, and the collect walked straight at the drop into the wall until it failed `:not-picked-up`.

## How it works
1. Arrived (`arrived?`): set only `:control/look` at the target and finish, so the next intent starts already looking.
2. More than `walk-timeout-ticks` (1200, 60 s) since the walk began: fail `:stuck`.
3. No route yet: plan. A route that is `:none` with no waypoints at all fails `:no-path` at once.
4. The route is stale: a waypoint still ahead can no longer be stood on, no waypoint was reached for `stuck-ticks` (40, 2 s), or the route ended short of the target (`:partial` or `:none`) with every waypoint behind. Plan again, unless `max-replans` (6) plans were already made: then fail `:no-path`. A chunk arriving is not by itself a reason: gym batch cobble-a1 run 7 saw six arrive in the first seconds after landing, each spending a plan on the same route, and the walk failed `:no-path` without a step taken.
5. The route is walked to its end but the target is still out of reach (a `:found` route ends within 3 of the target; reach is 4): press straight toward the target.
6. The next waypoint is reached: advance.
7. Otherwise `follow` it.

## Gotchas
- A waypoint one block down counts only once the feet are down. With a looser gate the property test caught a bot standing on the lip of a ledge counting the cell below as reached ([[pathfinder]]).
- Time is counted in ticks (`:time/tick`), not ms, unlike dig and craft; a stalled tick rate stretches the 60 s timeout.
- A walk started by the gather chain carries `:intent/for :log`, which is what lets the chain continue it into a dig ([[gather-chain]]).
- A failed walk blacklists its target via the planner (`:no-path` and `:stuck` alike), so the next search picks another trunk ([[goals-and-intents]]).
- The sim scenario with the only log on a plateau behind a pond ends `:stuck` at the bottom of the pond on the old walk and holding the log with no failed intent on this one.
- Water, lava and awkward shapes are the route's business, not the walk's: the walk follows what it is given. A walk that ends in water anyway is preempted by the escape ([[water-escape]]).

## See also
- [[pathfinder]] - how the route is made.
- [[dig-timeline]] - what follows a walk.
