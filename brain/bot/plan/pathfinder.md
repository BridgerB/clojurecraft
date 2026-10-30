---
title: Pathfinder
description: How path/plan finds a route for the feet over the block grid as a pure function (A* with a budget in expansions), what a cell can be, which moves exist and what each needs, how goals are data, and what :found :partial :none mean.
type: reference
tags: [bot, plan, path, intents]
aliases: [path/plan, A*, route, waypoints, :awkward, lava rule, node budget, path.clj]
status: verified
lastUpdated: 2026-10-18
verifiedAgainst: HEAD
sourceRefs:
  - src/clojurecraft/path.clj#def wary-extra
  - test/clojurecraft/path_test.clj#a-short-detour-round-crimson-ground-beats-a-way-through-it
  - src/clojurecraft/path.clj#defn plan
  - src/clojurecraft/path.clj#defn on-ice?
  - test/clojurecraft/path_test.clj#a-bot-on-a-sheet-of-ice-walks-off-it-but-never-onto-it
  - test/clojurecraft/path_test.clj#a-bot-on-the-rim-of-a-hole-in-the-ice-is-on-the-ice
  - src/clojurecraft/path.clj#defn search
  - src/clojurecraft/path.clj#defn classify-id
  - src/clojurecraft/path.clj#defn cells
  - src/clojurecraft/path.clj#def awkward-types
  - test/clojurecraft/plan_test.clj#a-trunk-across-powder-snow-is-walked-to
  - src/clojurecraft/path.clj#def one-layer-snow
  - src/clojurecraft/path.clj#def templates
  - src/clojurecraft/path.clj#defn landing
  - src/clojurecraft/path.clj#defn start-cell
  - src/clojurecraft/path.clj#defn rests
  - test/clojurecraft/path_test.clj#a-box-over-a-ledge-starts-on-the-block-it-rests-on-not-one-across-a-gap
  - test/clojurecraft/path_test.clj#a-route-leaves-occupied-awkward-footing-without-entering-it
  - src/clojurecraft/path.clj#defn lava-near?
  - src/clojurecraft/path.clj#defmulti goal-done?
  - src/clojurecraft/path.clj#defmethod goal-done? :sky
  - src/clojurecraft/stairs.clj#defmethod path/goal-done? :surface
  - src/clojurecraft/stairs.clj#defmethod path/heuristic :surface
  - src/clojurecraft/path.clj#def default-max-nodes 6000
  - src/clojurecraft/path.clj#defn trap-factor
  - test/clojurecraft/path_test.clj#a-cliff-is-descended-by-its-stair-never-by-the-six-block-drop
  - test/clojurecraft/path_test.clj#a-found-route-is-walked-by-the-physics-and-ends-in-the-goal
  - test/clojurecraft/path_test.clj#a-snowfield-is-crossed-through-its-one-layer-snow
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
- `path.clj`, `on-ice?` / `cells` - ice is `:awkward` (the physics does not slide), so a route never steps onto a sheet from land; but of a world whose bot stands on one (`on-ice?`: ice in one of the two blocks under the feet, the second for a floor laid on it, passed to `plan-on` as `ice?` since it builds its own world from the chunks) the sheet is floor, so a bot a swim out put on a frozen lake can walk off it. Issue #71, race 39088700340 bot Clj_r02 (`a-bot-on-a-sheet-of-ice-walks-off-it-but-never-onto-it`, `stone/off-ice`). The feet over a cell with nothing solid in it count as on the sheet too when the block under one of its four neighbours is ice: the body rests on the rim of a hole dug in the sheet, where no route stepped onto it to walk off (issue #105, `a-bot-on-the-rim-of-a-hole-in-the-ice-is-on-the-ice`).
- `path.clj`, `classify-id` / `classify` - what a block state is to a route: `:water`, `:lava`, `:awkward` (a block whose collision shape the game says is neither nothing nor a full cube and stands taller than `flat-shape`, 0.125, read from the game into `shapes.edn`, `blocks/partial-shapes`: a cocoa pod, a slab, a lantern, but not a carpet; or a definition type in `awkward-types`, the hand list kept for full cubes the physics cannot simulate, slime, ice, magma, cobwebs. Gym batch iron-a9 run 5 walked into a cocoa pod on a jungle log the hand list lacked and never moved again), `:solid` (a full cube), `:clear` (passable, not a liquid; one layer of snow is, `one-layer-snow`: the game gives the block's default state no collision shape at all and the physics passes through it, so the feet stand in its cell on the block under it; deeper layers collide and stay `:awkward`), `:unknown` (the chunk is not loaded).
- `path.clj`, `cells` / `cell-key` - one search's view of the cells: a function from a cell to its class that reads each cell through the terrain once and holds the answer under one long key. Every cell predicate below takes a world or its cells; the search and the stone scan make one and pass it down, so a cell asked a hundred times is read once ([[remembered-by-identity]]).
- `path.clj`, `templates` / `moves` - four move templates along +x, rotated to the eight directions: `:walk` (cost 1), `:jump-up` (2), `:diagonal` (√2, both corner columns clear), `:drop` (1 + 0.5 per block fallen, at most `max-fall` 3). Each template lists what cells must be what; `neighbors` interprets them.
- `path.clj`, `floor?` / `clear?` / `standable?` - a floor is `:solid`; a clear cell is `:clear` with no lava in its four horizontal neighbours or under it (`lava-near?`); a node is standable with a floor below and body and head clear.
- `path.clj`, `landing` - where a drop comes to rest: the first floor within `max-fall`, with only clear cells on the way; water, lava, an awkward block or an unloaded chunk under the fall refuse it.
- `path.clj`, `start-cell` - where the search ordinarily begins: the feet cell when it is standable, else the nearest standable cell within one block on the plane and one up or down; `plan` reports it as `:path/from`. Before snapping to a neighbour, `plan-on` prefers a found route from the actual supported, clear feet cell. An occupied awkward floor can be left but remains forbidden as a waypoint; when no route leaves it, the neighbouring-start fallback remains.
- `path.clj`, `goal-done?` / `heuristic` - multimethods on `:goal/kind`: `:near` (within `:goal/range`, default 3), `:block`, `:xz` (any height), `:away` (farther than range), `:pickup` (within range on the plane with the target at the feet's height or up to `:goal/rise` above: where a dug block's drop lands in reach; with `:goal/step-down?`, also a straight neighbour a block up, `step-down-node?`: the block in the floor beside the feet, whose drop lands in the hole it leaves), `:sky` (a standable cell `terrain/under-sky?` accepts). `goal-done?` takes the world, `(goal-done? world goal node)`, so a goal stays data and one that asks about the terrain reads the world it is given. The heuristic for a point is octile on the plane plus the height difference. `:sky` weights the height deficit by `sky-weight` (4), more than a jump costs, so the air already dug is climbed before a mine is filled. That heuristic is not admissible.
- `path.clj`, `search` - A* with a sorted set of `[f g node]` (ties break on the node, so the search is deterministic), g and parent maps, a closed set, and the nearest node seen by the heuristic. Each move's cost is multiplied by `trap-factor`: 10 within two columns of a trap in `:world/traps` younger than ten minutes ([[water-escape]]).

## How it works
The `:surface` goal is registered in `stairs`: `out?` requires usable open ground, not merely the sky over a pit, and its heuristic is the same as `:sky`. Walks out of a mine use it so a climb and a walk agree about where the exit is ([[walk-intent]]).

1. A node is the feet cell `[x y z]`. `from` is `(path/feet-cell pos)`.
2. Expanding a node asks every move whether its needs hold; a drop also needs a landing.
3. `:found` when a node satisfies the goal. `:partial` when `max-nodes` (default 6000) expansions are spent, `:none` when the open set empties; both return the route to the nearest node seen, which is what a walker should follow before planning again as chunks load.
4. `:unknown` cells are never floor and never clear, so a route stops at the loaded frontier; a goal in an unloaded chunk comes back `:none` or `:partial` toward it.
5. On the lake fixture a plan takes 3-13 ms, well inside one 50 ms tick, so the walk plans synchronously in its tick.

Wary ground: entering a cell whose floor or itself is crimson nylium, crimson fungus or roots, or a nether wart block costs `wary-extra` (1.0) more (`wary-cost`, added in the search next to the trap factor), so a short detour round a crimson patch beats a way through it and a patch with no way round is still crossed (gym run 41134469333: both finished jobs died crossing to a warped forest).

## What the tests hold
- Five fixtures: a cliff is descended by its three-step stair, never the six-block drop; a trench is crossed at its ramp and is `:none` without it; a lake is skirted on its dry strip and never waded; a hill is climbed by four jumps and descended by four drops; a one-high gap is `:none`.
- Every route is sound: every waypoint standable, none water, lava, awkward or unknown, consecutive waypoints one move apart.
- A property over 200 generated terrains (heightmap steps of -3..+1, water pools): when the route is `:found`, driving `physics/step` with the walk's own follow controller reaches every waypoint in at most 80 ticks and ends in the goal.

## Gotchas
- **Departure is not entry.** Issue #284's R1/6 snapshot repaired the next tunnel step but stood on soul sand. Snapping its start to gravel beside the feet chose an isolated cell with no route to the repaired step. Searches now depart from the actual supported, clear feet cell; every entered waypoint still needs an ordinary walkable floor, so this does not permit crossing a soul-sand field.
- **A start on a block edge floors into the wrong column.** Gym landing 21280,18720 (batch path-a1 run 5): the fixture teleports to the column's `~0.5`, which on a steep hillside put the feet at x 21281.0 exactly, on the edge of a stone step; `feet-cell` floored into the step, every plan was `:none` from inside solid rock, and three `:no-path` failures in half a second killed the plan. The player's box rests on the cell beside it, which is standable; `start-cell` searches from there. The old straight-line walk never noticed because it never asked where it stood.
- **The neighbour the search starts from must be the one the box rests on.** Issue #284's R1-26-1 (sim race 39718481292) stood at x 401.73 on a one-block ledge: the feet cell's floor was air, the box lay over the east block's edge, and `start-cell` took the first standable neighbour in its scan order, a block across a one-wide gap to the west, from which no route led east. Every walk to the recentring cell failed `:no-path` three times in 400 ms. `plan` now asks `rests` (the cells at the feet's height whose columns the box overlaps, the one under most of it first, only when `from` is the feet cell of `:player/pos`) and `start-cell` tries those before the scan. A walk from a position far from any edge is unchanged.
- Water is a wall, never a floor: the bot can swim ([[fluid-physics]]) but a route never asks it to; getting out is the escape's job ([[water-escape]]).
- The lava rule is applied by `clear?`, so every move kind has it; ruststeve's diagonal lacked one and put a bot over lava ([[sib-pathfinder-movements]]).
- No parkour, no digging through, no scaffolding: a one-wide gap with no ramp is `:none`, by design.
- `:awkward` is conservative: a slab or stair is routed around, not stood on, until the physics can collide with its shape (a `shapes.edn` would be an accretion).
- **A snowfield was unwalkable.** Race 38477665442 (issue #38): 39 bots landed on snowy ground never moved, twelve walks failing `:no-path` in a second. One layer of snow lay on every block round them, `snow_layer` was in `awkward-types` whatever its depth, so neither the feet cell nor any cell beside it was standable, `start-cell` found nothing and every plan was `:none` from nowhere. `one-layer-snow` is `:clear` now; the test `a-snowfield-is-crossed-through-its-one-layer-snow` holds it, with a two-layer field still refused.
- **A powder-snow field was unwalkable.** Sim race 38811417241 runner 2 (issue #87, world R2/20): out of the mine on one cobblestone block among powder snow, a spruce eight blocks off; powder snow was in `awkward-types`, neither floor nor clear, so no route had a single move and the plan waited `:no-log`. A player without leather boots has no collision with it and sinks to the block below (the game's `PowderSnowBlock`), so it is in `blocks/passable-types` (not solid, the physics falls through it) and `:clear` to a route, which goes through it to the floor under it (`a-trunk-across-powder-snow-is-walked-to`). Not modelled: the slowing, and the freezing after seven seconds inside.

## See also
- [[walk-intent]] - the consumer: how the route is followed and when it is planned again.
