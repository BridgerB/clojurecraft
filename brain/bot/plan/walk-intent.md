---
title: Walk intent
description: How :walk plans a route to its target on its first tick and follows it waypoint by waypoint, when a waypoint counts as reached, when the walk plans again (a cell changed, a chunk arrived, 2 s without a waypoint, the route ended short), and when it fails :no-path or :stuck.
type: reference
tags: [bot, plan, intents, walk]
aliases: [:walk, waypoints, follow, waypoint-reached?, route-stale?, :no-path, replans, reach 4.0]
status: verified
lastUpdated: 2026-10-10
verifiedAgainst: b08563f
sourceRefs:
  - src/clojurecraft/intent.clj#defmethod run :walk
  - src/clojurecraft/intent.clj#defn route!
  - src/clojurecraft/intent.clj#defn route-stale?
  - src/clojurecraft/intent.clj#defn waypoint-reached?
  - src/clojurecraft/intent.clj#defn follow
  - src/clojurecraft/intent.clj#defn arrived?
  - src/clojurecraft/intent.clj#def stuck-ticks 40
  - src/clojurecraft/intent.clj#def max-replans 6
  - src/clojurecraft/intent.clj#def reach 4.0
  - test/clojurecraft/plan_test.clj#walks-to-a-far-log
  - test/clojurecraft/plan_test.clj#a-walk-plans-again-when-a-chunk-arrives
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
- `intent.clj`, `route!` - plans from the feet cell toward `{:goal/kind :near :goal/pos target :goal/range 3}` and counts the plan.
- `intent.clj`, `waypoint-reached?` - within `waypoint-reach` (0.35) of the waypoint's centre on the plane, and the feet within half a block of its height in both directions.
- `intent.clj`, `follow` - `toward` the waypoint's centre, jumping when it is above the feet and within `jump-near` (1.3), or when blocked.
- `intent.clj`, `route-stale?` - the four reasons to plan again.
- `intent.clj`, `arrived?` - within reach, and for a log, the feet no more than `pickup-rise` (2) below it.

## How it works
1. Arrived (`arrived?`): set only `:control/look` at the target and finish, so the next intent starts already looking.
2. More than `walk-timeout-ticks` (1200, 60 s) since the walk began: fail `:stuck`.
3. No route yet: plan. A route that is `:none` with no waypoints at all fails `:no-path` at once.
4. The route is stale: the next waypoint can no longer be stood on, a chunk arrived since planning (`:stats/chunks` moved), no waypoint was reached for `stuck-ticks` (40, 2 s), or the route ended short of the target (`:partial` or `:none`) with every waypoint behind. Plan again, unless `max-replans` (6) plans were already made: then fail `:no-path`.
5. The route is walked to its end but the target is still out of reach (a `:found` route ends within 3 of the target; reach is 4): press straight toward the target.
6. The next waypoint is reached: advance.
7. Otherwise `follow` it.

## Gotchas
- A waypoint one block down counts only once the feet are down. With a looser gate the property test caught a bot standing on the lip of a ledge counting the cell below as reached ([[pathfinder]]).
- Time is counted in ticks (`:time/tick`), not ms, unlike dig and craft; a stalled tick rate stretches the 60 s timeout.
- A walk started by the gather chain carries `:intent/for :log`, which is what lets the chain continue it into a dig ([[gather-chain]]).
- A failed walk blacklists its target via the planner (`:no-path` and `:stuck` alike), so the next search picks another trunk ([[goals-and-intents]]).
- The sim scenario with the only log on a plateau behind a pond ends `:stuck` at the bottom of the pond on the old walk and holding the log with no failed intent on this one.
- Water, lava and awkward shapes are the route's business, not the walk's: the walk follows what it is given. The physics still has no fluid model ([[land-physics]]).

## See also
- [[pathfinder]] - how the route is made.
- [[dig-timeline]] - what follows a walk.
