# A real pathfinder as a pure function: A* over the chunk value, waypoints consumed by the walk intent

## Summary

`:walk` today is a straight line with a jump reflex. `intent/toward` (`src/clojurecraft/intent.clj:39-47`) sets yaw at the target and jumps whenever `:player/horizontal-collision?` is true; `run :walk` (`intent.clj:57-85`) adds a 40-tick stuck detector, a random ±70° detour (at most 4), and a 1200-tick deadline, then fails with `:stuck`. That is enough for a tree on a flat clearing and nothing else: a one-wide trench, a two-block ledge, a pond between the bot and the trunk, or a cliff all end in `:stuck` and a blacklisted log. Every next goal (a cave entrance, water for a bucket, walking away from lava) needs the bot to choose a route, not a heading.

This issue adds a `clojurecraft.path` namespace: `(plan world from goal opts)` is a pure function over the world value that returns `{:path/waypoints [[x y z] ...] :path/cost n :path/status :found|:partial|:none}`. The walk intent consumes waypoints with the controls it already has. Digging through blocks, placing scaffolding and swimming are explicitly deferred.

## Why now / what the siblings learned

Both siblings ported the same A* (typecraft `path/`, ruststeve `src/path/`, "Port of typecraft's `path/movements.ts`") and both paid for the parts the plan below leaves out.

- Movement rules worth copying verbatim. typecraft `movements.ts:234-263` (forward: floor physical, body and head clear, cost 1), `:265-301` (jump up: cost 2, needs the cell two above the current node clear), `:303-335` (drop: scan down from `dy=-2` to `maxDropDown+1`, cost `1 + drop*0.5`, "never fall into lava"), `:337-397` (diagonal at `SQRT2`, only when at least one of the two corner columns is clear). Defaults in `pathfinder.ts:22-29`: `maxDropDown: 4`, `reachDistance: 0.5`, `stuckTimeout: 3500`.
- Goals and heuristic: `goals.ts:13-18` octile on XZ plus `|dy|`; `createGoalNear` (`:31-51`, 3D range), `createGoalXZ` (`:91-99`), `createGoalInvert` (`:162-168`, "path AWAY from the target").
- Following: `pathfinder.ts:194-197` gates waypoint arrival on having climbed ("Require the feet to actually be at/above a waypoint that is above us"); `:318-329` look, forward, jump when `wp.y > p.y + 0.5`. ruststeve tightened it: `bot/mod.rs:2109` reaches only when `dx*dx+dz*dz <= 0.49 && dy <= 0.6`, `:2144` jumps only when `near` (`< 1.6`) because "Jumping while far from the step just bounces in open air", `:2169` re-paths after 40 stuck physics ticks.
- Re-planning: `pathfinder.ts:559-581` drops the path when a block update lands within 1 block XZ / 2 Y of any waypoint. ruststeve `CHANGES.md:959` adds a per-tick live check of the next waypoint because "A* plans on a world that can be seconds old; lava flows".
- Liquids are the lesson. `movements.rs:152-160` `lava_around_body` and `:166-175` refuse any cell with lava in, beside or under it after bots dropped into "a scooped-out hole ringed by lava" (`CHANGES.md:374-375`); `CHANGES.md:1004` found `move_diagonal` "had no lava rule at all". `movements.rs:235-243` refuses water two deep when entering from dry ground: "pathing across lakes is how cycle-1 race bots drowned gathering wood". steve `LOOP.md:76`: ocean cells are "dead, bots drown".
- Budgets. typecraft slices the search into 40 ms per physics tick (`astar.ts:102-106`), returning the lowest-h node on `partial`/`timeout` (`:145`). ruststeve first ran one synchronous 2 s search, which froze its tick loop and the breath watchdog for about 4 s (`CHANGES.md:746-747`), then sliced it, then found the sliced budget was wall-clock so A* "searched ~0.9 s of its 2 s" (`CHANGES.md:1182`, `astar.rs:32-39`). "target-sync + `ASTAR_SYNC=1`" (`CHANGES.md:1338-1356`) is the tree plus an env flag restoring the one-shot search; it won the portal gym 11/17 vs 6/16 and `water_wall_pool` 4/10 vs 1/10, and `bot/mod.rs:1953-1956` now says "ASTAR_SYNC won and is the only planner". The lesson for us: a budget in expansions, not milliseconds, is both deterministic (replay-safe) and immune to this whole class of bug.
- Raw walks are last-mile only: steve `walkToXZ` (`bot-utils.ts:308-337`) is a 2 s forward press; ruststeve made `walk_to_xz` refuse targets beyond 3.5 blocks because "longer moves belong to the lava-aware pathfinder" (`CHANGES.md:580`). steve's `navigateTo` (`tasks/gather-wood/main.ts:288-380`) wraps the pathfinder in a distance-scaled timeout, a 4 s stuck check and a staircase fallback for trees on ledges.
- Shapes. steve's `ci/typecraft-data.tar.zst` `blockCollisionShapes.json` has 326 real shapes (slab `[0,0,0,1,0.5,1]`, fence `1.5` tall, carpet `0.0625`, farmland `0.9375`). ruststeve's `data/blockCollisionShapes.json` is degenerate: 2 shapes, every block full or empty. The vanilla `--reports` our `datagen.clj` reads carry no shapes at all.

## Design

Nothing here reads a clock or the atom. `path` depends on `game/block-at`, `blocks`, and `physics` constants only.

**Cells.** `(classify world [x y z])` → `:solid | :clear | :water | :lava | :awkward | :unknown`. `:unknown` is `block-at` nil (`game.clj:71-74`). `:clear` is a passable type that is not a liquid. Note `blocks/passable-types` (`blocks.clj:14-17`) contains `:liquid`, so `solid?` alone would let the bot walk into lava; the pathfinder must split liquids out. `:awkward` is the conservative answer to non-full-cube blocks: definition types `:slab :stair :fence :wall :fence_gate :trapdoor :door :iron_bars :stained_glass_pane :chain :lantern :snow_layer :farmland :dirt_path :cactus :magma :powder_snow :sweet_berry_bush :bed :candle :skull :flower_pot` are neither floor nor clear, so routes go around them. This matches `physics.clj:2-3` ("Full-cube collision only") rather than pretending to know a shape physics cannot simulate. A later `shapes.edn` generated from steve's data can relax it; that is an accretion, not a change.

**Nodes.** A node is the feet cell `[x y z]` (ints); standing there means floor at `y-1`, body `y` and head `y+1` clear. Equality and hashing are free on vectors.

**Moves as data.** `path/moves` is a vector of maps; `neighbors` interprets it:

```clojure
{:move/id :walk :move/to [1 0 0] :move/cost 1.0
 :move/needs [[:floor [1 -1 0]] [:clear [1 0 0]] [:clear [1 1 0]]]}
{:move/id :jump-up :move/to [1 1 0] :move/cost 2.0
 :move/needs [[:clear [0 2 0]] [:floor [1 0 0]] [:clear [1 1 0]] [:clear [1 2 0]]]}
{:move/id :diagonal :move/to [1 0 1] :move/cost 1.414
 :move/needs [[:floor [1 -1 1]] [:clear [1 0 1]] [:clear [1 1 1]] [:any-column-clear [[1 0 0] [0 0 1]]]]}
{:move/id :drop :move/to [1 :fall 0] :move/cost [1.0 :per-block 0.5] :move/max-fall 3}
```

The four cardinals and four diagonals are generated by rotating the templates. `:floor` means `:solid`; `:clear` means `:clear` and no `:lava` in the four horizontal neighbours or below (ruststeve's rule). `:drop` scans down to `max-fall` 3 (vanilla damage starts above 3) and refuses a `:water`, `:lava`, `:awkward` or `:unknown` landing. No parkour, no `:dig`, no `:place`, no `:swim`: the physics has no fluid model (`physics.clj:3`), so water is a wall, never a floor.

**Goals as data.** `{:goal/kind :near :goal/pos [x y z] :goal/range 3}`, `:block`, `:xz` (any y), and `:away {:goal/pos p :goal/range r}` (done when farther than r). `goal-done?` and `heuristic` are multimethods on `:goal/kind`; the heuristic is octile XZ plus `|dy|` (0 for `:xz`, negated for `:away`).

**Search.** Plain A* with a sorted-set open list keyed `[f g node]` and maps for g/parent. `opts`: `{:path/max-nodes 6000 :path/max-cost nil}`. Running out of nodes returns `:partial` with the path to the lowest-h node (typecraft's `bestNode`); an empty open set returns `:none` with the same best path. The budget is in expansions so a recording replays identically on any machine. 6000 expansions over persistent maps should sit near 20-40 ms; the tick loop tolerates that, and the first checkpoint measures it.

**Unknown chunks and sightings.** `:unknown` cells are never floor and never clear, so paths stop at the loaded frontier and come back `:partial`, which is exactly when the walker should move and re-plan as chunks arrive. `:world/sightings` holds only logs today (`memory.clj:13`), so the planner reads `block-at` (overlay then chunk). When the goal itself is in an unloaded chunk, `:partial` toward it is the right answer.

**The walk intent.** `:walk` gains `:intent/waypoints`, `:intent/at` (index) and `:intent/replans`. On the first tick with no waypoints it calls `plan` with `{:goal/kind :near :goal/pos target :goal/range 3}` (reach stays the existing `intent/reach` check). Each tick: `wp` = waypoint at `:intent/at`; it is reached when horizontal distance to `[wx+0.5 wz+0.5]` ≤ 0.35 and feet `y ≥ wy - 0.5`; then advance. Controls reuse `toward` with `:control/jump?` = `wy > feet + 0.5` and horizontal distance < 1.3; `physics/step` already enforces the 10-tick jump cooldown. No sprint (physics has none).

**Re-plan triggers** (all pure, all from the world value): the current waypoint's cell or floor is no longer what the plan assumed; a `level-chunk-with-light` or `block-update` since planning touched a cell within 1 XZ / 2 Y of any remaining waypoint (cheap: compare `:stats/chunks` and a `:world/blocks` count kept on the intent); no waypoint advance for 40 ticks (`intent/stuck-ticks`); the last waypoint reached with status `:partial`. Each re-plan increments `:intent/replans`; more than 6, or `:none` from the start, fails with `:no-path`, which `plan/fail-intent` already blacklists.

**Specs** (`spec.clj`): `:path/waypoints` (`coll-of ::block-pos :kind vector?`), `:path/cost double?`, `:path/status #{:found :partial :none}`, `::goal` (`:goal/kind :goal/pos`, opt `:goal/range`), `:intent/waypoints`, `:intent/at int?`, `:intent/replans int?`; `fdef path/plan` and `path/classify`.

**Fixtures** in `test/clojurecraft/path_test.clj` built with `world/column` (`test/clojurecraft/world.clj`, stone below y 64, one column):

- cliff: floor raised to y 70 for x ≤ 7, 64 beyond; a path from `[2 70 2]` to `[13 64 2]` must use a 3-step stair carved at x 8..10, never the 6-block drop.
- one-wide gap: a 2-deep trench along x = 6; with no parkour the only route is the ramp at z = 14, or `:none` when the ramp is removed.
- lake: water (`86`) over x 4..10, z 0..15 with one dry strip at z 0; the path takes the strip; closed strip → `:none`, and no waypoint is ever a water cell.
- hill: 1-block steps up to y 68 then down; waypoints climb via `:jump-up` and descend via `:drop`.
- low ceiling: a 1-high tunnel is the only gap in a wall → `:none`.

## Steps

1. `path/classify` and the awkward-type set; tests over `blocks` ids for stone, water, lava, oak_slab, short_grass, air, nil.
2. `path/moves` and `neighbors`; tests enumerate neighbours on a flat floor (8), at a wall (jump-up), at an edge (drop), beside lava (none in that direction).
3. `path/plan` with the four goal kinds, node budget, `:found/:partial/:none`; the five fixtures pass; `(time (plan ...))` on the lake fixture is recorded in the issue thread.
4. Traversability property (below) with test.check over generated terrains.
5. `:walk` consumes waypoints; `plan_test.clj` `walks-to-a-far-log` keeps passing; new sim test with a cliff fixture.
6. Re-plan triggers and `:no-path` failure; a test that loads a second chunk mid-walk and sees one re-plan.
7. Live: `clojure -M:run ... --record data/runs/path-1.edn` on a hilly landing; replay matches the RESULT line.

## Acceptance criteria

- Unit: all five fixture worlds return the expected status and the expected route shape; no returned waypoint is `:water`, `:lava`, `:awkward` or `:unknown`; consecutive waypoints differ by one move from `path/moves`.
- Property: for 200 generated columns (random heightmap with steps of -3..+1 and random water pools), when `plan` returns `:found`, folding `physics/step` with the follow controller over the fixture's `solid?` reaches every waypoint in ≤ 80 ticks each and ends inside the goal. `plan` is deterministic: equal inputs, equal output.
- Sim: `sim_test` variant with the log across a cliff and a pond completes `:wood` with zero `:stuck` failures.
- Live: ten `--until wood` runs on the local 25571 server from varied `harness` landings with ≥ 8 `:ok true`, each recorded and replayable; the previous straight-line baseline is run on the same landings for comparison.
- `spec.clj` instruments `path/plan` in tests; `clojure -M:test` green.

## References

- `upstream/clojurecraft/src/clojurecraft/intent.clj:12-15,39-47,57-85`; `physics.clj:2-3,64-96`; `game.clj:71-81`; `blocks.clj:14-32,51-54`; `memory.clj:13`; `test/clojurecraft/world.clj`
- `/Users/bridger/Developer/mc/upstream/steve/src/lib/typecraft/path/movements.ts:87-101,145-146,234-397`; `goals.ts:13-18,31-51,91-99,162-168`; `astar.ts:102-106,145`; `pathfinder.ts:22-29,194-197,318-341,559-581`
- `steve/src/lib/steve/lib/bot-utils.ts:198-262,308-337`; `steve/src/lib/steve/tasks/gather-wood/main.ts:288-380`; `steve/LOOP.md:74-81`; `steve/ci/typecraft-data.tar.zst` (`blockCollisionShapes.json`)
- `/Users/bridger/Developer/mc/upstream/ruststeve/src/path/movements.rs:152-175,226-257,346-407`; `astar.rs:32-39,334-337`; `bot/mod.rs:1953-1960,1987,2109,2144,2169`; `CHANGES.md:79,257,374-375,562,580,746-747,959,1004,1182,1338-1356`

## Open questions

- Node budget per tick: one synchronous `plan` per re-plan (ruststeve's final decision) versus carrying the open set in the intent across ticks. The first is simpler and replay-safe; measure it in step 3 before deciding.
- Should leaves be `:solid`? typecraft treats them as physical; `blocks/solid?` already does, which makes a canopy a wall and a tree trunk approachable only from the ground. Fine for wood, worth revisiting for jungles.
- Where does a dig-reach goal live? typecraft's `createGoalLookAtBlock` raycasts; ours can stay `:near 3` until the dig intent reports `:out-of-reach` often enough to matter.
- A `shapes.edn` from steve's data would turn `:awkward` slabs and stairs into real floors, but physics would need matching partial-box collision first; both or neither.
- Away-from-danger goals need a danger source; today nothing records lava or water sightings in `memory/watched?`. That is its own issue.
