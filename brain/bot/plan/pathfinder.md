---
title: Pathfinder
description: How path/plan finds a route for the feet over the block grid as a pure function (A* with a budget in expansions), what a cell can be, which moves exist and what each needs, how goals are data, and what :found :partial :none mean.
type: reference
tags: [bot, plan, path, intents]
aliases: [path/plan, A*, route, waypoints, :awkward, lava rule, node budget, path.clj]
status: verified
lastUpdated: 2026-10-10
verifiedAgainst: 439b9ce
sourceRefs:
  - src/clojurecraft/path.clj#defn plan
  - src/clojurecraft/path.clj#defn search
  - src/clojurecraft/path.clj#defn classify-id
  - src/clojurecraft/path.clj#def awkward-types
  - src/clojurecraft/path.clj#def templates
  - src/clojurecraft/path.clj#defn landing
  - src/clojurecraft/path.clj#defn start-cell
  - src/clojurecraft/path.clj#defn lava-near?
  - src/clojurecraft/path.clj#defmulti goal-done?
  - src/clojurecraft/path.clj#def default-max-nodes 6000
  - test/clojurecraft/path_test.clj#a-cliff-is-descended-by-its-stair-never-by-the-six-block-drop
  - test/clojurecraft/path_test.clj#a-found-route-is-walked-by-the-physics-and-ends-in-the-goal
related:
  - "[[bot/plan/_moc|Plan]]"
  - "[[walk-intent]]"
  - "[[land-physics]]"
  - "[[sib-pathfinder-movements]]"
  - "[[sib-astar-budget]]"
---

# Pathfinder

`(path/plan world from goal opts)` returns `{:path/waypoints [[x y z] ...] :path/cost n :path/status :found|:partial|:none}`: a route for the feet over the block grid, as a pure function of the world value (issue #4). Nothing in it reads a clock or the atom; the budget is in node expansions, so a recording replays to the same route on any machine ([[sib-astar-budget]] is why not milliseconds).

## Key files
- `path.clj`, `classify-id` / `classify` - what a block state is to a route: `:water`, `:lava`, `:awkward` (a definition type in `awkward-types`: slabs, stairs, fences, doors, trapdoors, snow layers, farmland and about seventy more the physics cannot simulate), `:solid` (a full cube), `:clear` (passable, not a liquid), `:unknown` (the chunk is not loaded).
- `path.clj`, `templates` / `moves` - four move templates along +x, rotated to the eight directions: `:walk` (cost 1), `:jump-up` (2), `:diagonal` (√2, both corner columns clear), `:drop` (1 + 0.5 per block fallen, at most `max-fall` 3). Each template lists what cells must be what; `neighbors` interprets them.
- `path.clj`, `floor?` / `clear?` / `standable?` - a floor is `:solid`; a clear cell is `:clear` with no lava in its four horizontal neighbours or under it (`lava-near?`); a node is standable with a floor below and body and head clear.
- `path.clj`, `landing` - where a drop comes to rest: the first floor within `max-fall`, with only clear cells on the way; water, lava, an awkward block or an unloaded chunk under the fall refuse it.
- `path.clj`, `start-cell` - where the search begins: the feet cell when it is standable, else the nearest standable cell within one block on the plane and one up or down; `plan` reports it as `:path/from`.
- `path.clj`, `goal-done?` / `heuristic` - multimethods on `:goal/kind`: `:near` (within `:goal/range`, default 3), `:block`, `:xz` (any height), `:away` (farther than range). The heuristic is octile on the plane plus the height difference.
- `path.clj`, `search` - A* with a sorted set of `[f g node]` (ties break on the node, so the search is deterministic), g and parent maps, a closed set, and the nearest node seen by the heuristic.

## How it works
1. A node is the feet cell `[x y z]`. `from` is `(path/feet-cell pos)`.
2. Expanding a node asks every move whether its needs hold; a drop also needs a landing.
3. `:found` when a node satisfies the goal. `:partial` when `max-nodes` (default 6000) expansions are spent, `:none` when the open set empties; both return the route to the nearest node seen, which is what a walker should follow before planning again as chunks load.
4. `:unknown` cells are never floor and never clear, so a route stops at the loaded frontier; a goal in an unloaded chunk comes back `:none` or `:partial` toward it.
5. On the lake fixture a plan takes 3-13 ms, well inside one 50 ms tick, so the walk plans synchronously in its tick.

## What the tests hold
- Five fixtures: a cliff is descended by its three-step stair, never the six-block drop; a trench is crossed at its ramp and is `:none` without it; a lake is skirted on its dry strip and never waded; a hill is climbed by four jumps and descended by four drops; a one-high gap is `:none`.
- Every route is sound: every waypoint standable, none water, lava, awkward or unknown, consecutive waypoints one move apart.
- A property over 200 generated terrains (heightmap steps of -3..+1, water pools): when the route is `:found`, driving `physics/step` with the walk's own follow controller reaches every waypoint in at most 80 ticks and ends in the goal.

## Gotchas
- **A start on a block edge floors into the wrong column.** Gym landing 21280,18720 (batch path-a1 run 5): the fixture teleports to the column's `~0.5`, which on a steep hillside put the feet at x 21281.0 exactly, on the edge of a stone step; `feet-cell` floored into the step, every plan was `:none` from inside solid rock, and three `:no-path` failures in half a second killed the plan. The player's box rests on the cell beside it, which is standable; `start-cell` searches from there. The old straight-line walk never noticed because it never asked where it stood.
- Water is a wall, never a floor, because the physics has no fluid model ([[land-physics]]); swimming is issue #5.
- The lava rule is applied by `clear?`, so every move kind has it; ruststeve's diagonal lacked one and put a bot over lava ([[sib-pathfinder-movements]]).
- No parkour, no digging through, no scaffolding: a one-wide gap with no ramp is `:none`, by design.
- `:awkward` is conservative: a slab or stair is routed around, not stood on, until the physics can collide with its shape (a `shapes.edn` would be an accretion).

## See also
- [[walk-intent]] - the consumer: how the route is followed and when it is planned again.
