---
title: Stairs down
description: How :stairs-down (and :stairs-up) cuts a staircase toward a height one stair at a time, which cells each stair opens and exposes, the refusal rules (water, lava, unloaded, a drop), the floor laid from carried cobblestone where a stair has none (a cave), the quarter turns and :boxed, the child :dig and :place intents, and why a stair counts only when the feet went down and stand centred in its cell.
type: reference
tags: [bot, plan, dig, mining, safety]
aliases: [:stairs-down, :stairs-up, up-cells, under-sky?, descend, climb out, child intent, :boxed, stair-cells, safe?, intents composing intents, lay, floor-laying, fillers, cast staircase, cave lip, creep-speed, onto]
status: verified
lastUpdated: 2026-10-14
verifiedAgainst: HEAD
sourceRefs:
  - src/clojurecraft/stairs.clj#defn stair-cells
  - src/clojurecraft/stairs.clj#defn exposed
  - src/clojurecraft/stairs.clj#defn safe?
  - src/clojurecraft/stairs.clj#defn clear-way?
  - src/clojurecraft/stairs.clj#def fillers
  - src/clojurecraft/stairs.clj#defn filler
  - src/clojurecraft/stairs.clj#defn floor-laying
  - src/clojurecraft/stairs.clj#defn lay
  - src/clojurecraft/stairs.clj#defmethod stairs-stage :lay
  - src/clojurecraft/stairs.clj#defn refuse
  - src/clojurecraft/stairs.clj#defn onto
  - src/clojurecraft/stairs.clj#def creep-speed
  - src/clojurecraft/place.clj#defn support
  - src/clojurecraft/intent.clj#defn centred?
  - test/clojurecraft/sim_test.clj#a-cave-under-the-stairs-is-cast-through-from-carried-cobblestone
  - src/clojurecraft/plan.clj#defn fail-intent
  - test/clojurecraft/stone_test.clj#a-boxed-descent-is-not-started-again-from-the-same-cell
  - src/clojurecraft/intent.clj#defn run-child
  - src/clojurecraft/intent.clj#defn feet
  - src/clojurecraft/stairs.clj#def max-turns
  - src/clojurecraft/stairs.clj#defmethod intent/run :stairs-down
  - src/clojurecraft/stairs.clj#defmulti stairs-stage
  - src/clojurecraft/stairs.clj#defn airborne?
  - test/clojurecraft/sim_test.clj#a-staircase-started-in-the-air-waits-for-the-ground
  - test/clojurecraft/sim_test.clj#a-stone-hill-is-descended-five-stairs-with-a-pickaxe
  - test/clojurecraft/sim_test.clj#boxed-in-by-lava-on-every-side-fails-boxed
  - test/clojurecraft/sim_test.clj#water-beside-a-stair-cell-turns-the-stairs
  - src/clojurecraft/stairs.clj#def refusals
  - src/clojurecraft/stairs.clj#def max-rejections
  - src/clojurecraft/stairs.clj#defn rejected
  - test/clojurecraft/sim_test.clj#a-stair-the-server-did-not-cut-is-dug-again
  - src/clojurecraft/stairs.clj#defn step-aside
  - src/clojurecraft/stairs.clj#defn shore-distance
  - src/clojurecraft/stairs.clj#defn dry-top?
  - test/clojurecraft/sim_test.clj#stairs-up-under-a-lake-step-toward-the-shore
  - src/clojurecraft/stairs.clj#defn level-safe?
  - src/clojurecraft/stairs.clj#defn level-clear?
  - src/clojurecraft/stairs.clj#defn too-hard?
  - src/clojurecraft/stairs.clj#defn level-step-limit
  - test/clojurecraft/sim_test.clj#a-tunnel-goes-round-ore-its-pickaxe-cannot-drop
  - test/clojurecraft/sim_test.clj#water-under-every-stair-is-stepped-around
  - src/clojurecraft/stairs.clj#defn fell?
  - src/clojurecraft/stairs.clj#defn up-cells
  - src/clojurecraft/stairs.clj#defn up-safe?
  - src/clojurecraft/stairs.clj#defn climb-to
  - src/clojurecraft/stairs.clj#def shapes
  - src/clojurecraft/stairs.clj#defn taken?
  - src/clojurecraft/terrain.clj#defn under-sky?
  - test/clojurecraft/sim_test.clj#stairs-up-cut-a-way-out-of-the-rock
  - test/clojurecraft/sim_test.clj#gravel-over-the-head-is-never-dug-under
  - src/clojurecraft/stairs.clj#defn tunnel-plan
  - src/clojurecraft/stairs.clj#defn tool-missing
  - src/clojurecraft/stairs.clj#defn by-hand?
  - test/clojurecraft/sim_test.clj#stairs-up-with-no-pickaxe-are-cut-by-hand
  - test/clojurecraft/sim_test.clj#a-tunnel-with-no-pickaxe-stops-for-one
  - test/clojurecraft/sim_test.clj#snow-in-the-stair-is-cleared-by-hand
  - test/clojurecraft/sim_test.clj#a-tunnel-goes-round-what-refuses-it
  - test/clojurecraft/sim_test.clj#stairs-up-walk-out-from-under-water
  - src/clojurecraft/stairs.clj#defn out?
  - test/clojurecraft/sim_test.clj#stairs-up-end-on-the-ground-not-in-a-shaft
  - test/clojurecraft/sim_test.clj#gravel-that-refills-a-stair-is-dug-again
related:
  - "[[bot/plan/_moc|Plan]]"
  - "[[dig-timeline]]"
  - "[[sib-dig-down-safety]]"
  - "[[goals-and-intents]]"
---

# Stairs down

`{:intent/kind :stairs-down :intent/to-y n}` (optionally `:intent/dir [dx dz]`) cuts a staircase in its heading until the feet are at or below `to-y`. It never digs the block under the feet: a blind drop is how the siblings' bots died ([[sib-dig-down-safety]]).

## Key files
- `stairs.clj`, `stair-cells` - the three cells a stair ahead opens: head-up, head, and the new feet one block down.
- `stairs.clj`, `exposed` - what the stair exposes to the body: those three, the floor under the new feet, and every horizontal neighbour of the three.
- `stairs.clj`, `clear-way?` - none of the opened cells water, lava or unloaded; no lava in any exposed cell. A new feet cell in `:plan/boxed-at` (where a staircase died `:boxed`) is refused. `safe?` is that and a floor under the new feet.
- `stairs.clj`, `lay` - where the floor is missing and the way is clear: the cells to lay it from a carried filler (`floor-laying`: the floor cell when a solid block borders it to place against, `place/support`; else a holder under the block under the feet first, placed against that block's underside, then the floor against the holder), when `filler` finds enough of one (`fillers`: cobblestone, the stones that drop themselves, dirt; never gravel or sand). Only `:stairs-down` lays (`shapes`): a stair up's floor would be the cell the bot stands in.
- `stairs.clj`, `onto` - the controls that walk onto the new feet cell; `creep-speed` 0.06.
- `intent.clj`, `feet` - y a touch below the position, since physics jitter dips it under the integer.
- `intent.clj`, `run-child` - the child `:dig` (`intent/child-dig`) stands in as `:plan/intent` for one tick of the dig executor, then returns to `:intent/child`: intents composing intents, with one rule for breaking blocks ([[dig-timeline]]).
- `stairs.clj`, `max-turns` 4, `step-timeout-ticks` 200.

## How it works
Each stage is a method of `stairs-stage`; `run :stairs-down` only checks whether the feet are down far enough.
1. `:plan`: with the body in the air, neither on the ground nor in a liquid (`airborne?`), wait: round falling feet every cell is air and every heading would be refused for the drop (race 38477665442, landing 2361,111,2361, issue #65: a walk to stone stepped off a cliff and the staircase the mine started on the way down was `:boxed` three times in the four seconds of the fall; `a-staircase-started-in-the-air-waits-for-the-ground`). Else, with the current heading (`+x` first), compute the stair; `safe?` → `:open`; else, the way clear but the floor missing and a filler carried (`lay`) → `:lay`; else turn a quarter and count. Four refusals → `step-aside`: a level step (`level-cells`: head and feet one block along, same height) that `level-safe?` allows (a stair's rules, and no opened cell bordering a fluid, which the child dig would refuse), then `:plan` again from there with the turns counted afresh. `level-safe?` also refuses a cell too hard for the held tools (`too-hard?`: a tool of its kind held, none that drops it), which the child dig would refuse `:needs-tool`: gym batch iron-a9 run 4's tunnel at y 2 met such ore and failed three times in a moment (`a-tunnel-goes-round-ore-its-pickaxe-cannot-drop`). The first takes the first heading allowed; each later one keeps the last one's way (`:intent/level-dir`) while it is allowed and never goes straight back, so the staircase walks out from under what refuses it. No heading allows one, or `level-step-limit` is spent (`max-level-steps`, 32, and for a climb as many more as the landing is far on the plane: gym batch iron-a9 run 1 climbed under the sea 155 blocks from its landing and was `:boxed` after 32 steps three times): fail `:boxed`. When each step took the first heading allowed, live run 9 (cutting stairs up under water at y 51) stepped round a square of four cells until it failed `:boxed` three times (`stairs-up-walk-out-from-under-water`).
2. `:lay`: lay the floor's cells (`:intent/lay`, the first not yet solid at a time), each a child `:place` of the filler at a given cell (`:intent/cell`, as a build places; [[place-intent]]), then `:open` (or `:level` for a level step). A placement the server refused, or no filler left, refuses the stair like any cell that cannot be cut (`refuse`: turn, or `step-aside` when boxed in). `step-aside` takes a level step that can be laid when none is `level-safe?` (`level-clear?` and `lay`), and `deeper` in the mine act sends stairs down where one can be cut or cast ([[mine-act]]).
3. `:open`: while any of the three cells is solid, run a child dig of the topmost. A child that finds its cell already open (`:target-gone`, `fell?`) is as good as done: gravel or sand fell out of it. A child refused for one of `refusals` turns the stair like an unsafe plan: `:needs-tool` (ore the pickaxe cannot drop) or `:unsafe` (a cell beside water or lava, which the dig executor will not open, since it would let the fluid in). A child the server did not do (`:rejected`: its ack came with the block still standing, [[dig-timeline]]) leaves the cell to open, so it is dug again (`rejected`, counted in `:intent/rejections`; `max-rejections`, 3, fail the staircase `:rejected`): race 38477665442 runner 9 (issues #59, #66), a lagging server with 25 bots dropped early FINISHes queued behind one another, and the staircase, believing the ack, walked into rock the server still had there until the step timed out (`a-stair-the-server-did-not-cut-is-dug-again`). Any other failure fails the stair with its reason.
4. `:level`: dig the level step's cells with child digs, then walk until the feet are in its feet cell; 10 s from the step's start without that fails `:stair-not-taken`.
5. `:step`: walk onto the new feet cell (`onto`); done with the stair when the feet are half a block below where it began, which is the only proof the server broke what the bot asked for, and centred in the cell (`taken?`, `intent/centred?`). Going down where nothing beyond the cell stops the body (the cell past it is not solid: a cave, a floor laid over it all there is to land on), the walk creeps: forward only on the ground and under `creep-speed`, so the body leaves the edge slowly and lands on the cell; into rock the wall ahead stops it, and going up the jump needs the push. A stair cell solid again (gravel fell into it) goes back to `:open`; 10 s without either fails `:stair-not-taken`.
6. Done when the feet are at or below `to-y`.

## Stairs up
`{:intent/kind :stairs-up :intent/to-y n}` is the same staircase climbed (`shapes`: each kind's cells, rule and rise). A stair up opens `up-cells`: the cell over the head, which the jump needs, then the new head and the new feet one block up and ahead; the block ahead at the feet's height is the step. It is cut under `up-safe?`: `safe?`'s rules, and no gravel or sand over the cell above the head, which would fall through it onto the bot (`gravel-over-the-head-is-never-dug-under`: every heading refused, a level step out from under it). `:step` walks into the step, which jumps; `taken?` going up also needs the feet on the ground, since halfway up a jump the feet are already half a block higher and the next stair was once planned from the air. A climb the planner starts (the scoop's way out, the gather's) aims `climb-stairs` (8) up at a time (`climb-to`), as a descent cuts `stone/stair-depth`, so the planner looks again between lengths and makes the spare pickaxe while one still digs: race 38477665442 bots Clj_r23 and Clj_r06 climbed 26 and 53 stairs in one intent and wore both pickaxes out (issue #39). Done standing at `to-y`, or out of the ground (`out?`): under the open sky (`terrain/under-sky?`: nothing but air, plants or leaves over the feet to the top of the world) with a level step it can walk onto with nothing to dig, itself under the sky. The open sky alone is a pit: live run 11's stairs up stopped under the sky two below the ground, in a shaft one wide, and no route led out to water (`stairs-up-end-on-the-ground-not-in-a-shaft`). The first level step of a climb takes the heading nearest the shore (`shore-distance`: the first column within `shore-reach`, 48, whose top block is solid rather than a fluid, `dry-top?`), and with no shore in sight the one toward the landing, which is land, since what refuses a climb is most often a lake or the sea overhead: gym batch iron-a7 run 1 climbed to y 44 under a lake (stone, sand, then water to y 63 over the head), stepped 32 east under it and failed `:boxed` (`stairs-up-under-a-lake-step-toward-the-shore`). The scoop's `climb` cuts these when no route leads back to the landing ([[scoop-intent]]).

## Tunnels
`{:intent/kind :tunnel :intent/dir d :intent/length n}` is level steps alone: `tunnel-plan` takes the next along its heading when `level-safe?`, else a step to one side, keeping the heading, so the tunnel goes round what refused it; the side is the first of the two allowed and then the only one (`:intent/side`), so it never steps back and forth; `:boxed` when neither the heading nor that side allows a step. It never turns back down itself: when it turned a quarter at each refusal, live run 8 (refused east at y 16) tunnelled west beside its own tunnel and gym batch iron-a2 run 4 walked its tunnel back and forth for eight minutes (`a-tunnel-goes-round-what-refuses-it`). The `:level` stage is the staircase's own. Done after n steps. The mine act cuts one at a row's depth ([[mine-act]]).

## Gotchas
- Keys that are over (`:intent/child`, `:intent/cells`) are removed, never nil: the world spec refuses a nil vector.
- Water beside a stair is allowed, lava is not: the siblings found refusing water wedged descents in wet biomes. A cell that itself borders water is still never dug (the dig's `exposes-fluid?`): the stair turns instead. Before it turned, a live iron-kit descent (local landing 2360,2360) met an aquifer at y 36 and failed `:unsafe` three times in a moment, the planner restarting the same refused stair (`water-beside-a-stair-cell-turns-the-stairs`).
- **A lake under every stair is stepped around.** The fifth live iron-kit run (local landing 3024,2360) stood at y 52 over an underground lake filling y 48-51: every stair's new feet had water under or beside them, and the staircase failed `:boxed` three times in a moment. A level step leads along until a stair is safe; `boxed-in-by-lava-on-every-side-fails-boxed` now needs lava beside the feet too. Full run ab3945e reached a furnace and a stone pickaxe, then stood at y 32 where every stair and every further level step was refused; two more staircases from that cell boxed in under a second. `fail-intent` blacklists the cell, the mine does not start another staircase there (`a-boxed-descent-is-not-started-again-from-the-same-cell`), and `safe?` will not step back onto it.
- **A cave under the stairs is cast through.** Race 38477665442 runner 8 (issue #37, 29 real bots and 1,335 sim runs a round `[:ingots :boxed]`): five of its eight bots that stopped `:boxed` stood on the lip of a cave their staircase had broken into, 7 to 284 cobblestone in hand, every stair refused for the drop under it; and from the lip nothing else could be cut (`stairs` refuses a cell in `:plan/boxed-at`, no tunnel, no deeper stair, no retreat), so the mine waited `:boxed` out. The floor is now laid first (`lay`): `a-cave-under-the-stairs-is-cast-through-from-carried-cobblestone` casts five stairs down from one block of stone over a cave, ten blocks laid and nothing dug, and with nothing to lay from is `:boxed` as before. Two more of the eight stood in aquifers (water beside every stair and level step), which laying does not address.
- **A stair ends centred, or it is a shaft.** Clj_r18's recording (the same race) showed each stair's feet in the column before the stair's, standing on the edge of the cell's floor: the body had landed past the cell's middle, and the next stair, planned from the column it stood in, dug the floor it stood on. The staircase went straight down a shaft one block wide, which no route led back up. `taken?` now needs the feet centred in the new cell, and `onto` walks them there; pushed all the way, the model left the lip at 0.11 blocks a tick, 0.19 in the air, and landed at the far edge of the laid floor, then fell off it, which is why the walk creeps where nothing stops the body.
- **A broken pickaxe stops the staircase, going down.** Rock that drops only to a pickaxe with none held at all is never dug by hand going down: the stairs or tunnel fail `:no-pickaxe` (`tool-missing`) and the planner makes another. A stair up is cut by hand (`by-hand?`): the tool is made up top, so the way out is worth 7500 ms a block. Race 38477665442 Clj_r21 (issue #40): the pickaxe broke at y 60 in the iron mine, the stairs up stopped `:no-pickaxe`, the sticks for another were in the trees, and twelve walks to them failed `:no-path` (`stairs-up-with-no-pickaxe-are-cut-by-hand`). Batch iron-a2 runs went on by hand when the pickaxe broke, 7500 ms a stone for nothing, until a step timed out. A level step's 10 s is the walk's alone: it starts again after each dig. Only the pickaxe counts: a block that drops only to another tool is dug by hand in its own time, since the staircase wants the cell open, not the drop. Race 38477665442 (issues #42, #72): every bot landed in snow, on runners 2, 6 and 10, failed `:no-shovel` on its first stair, a snow block among its cells and a wooden pickaxe in hand (`snow-in-the-stair-is-cleared-by-hand`). `too-hard?` likewise asks `blocks/needs-tier`, the tier rule, not whether the block needs its tool.
- **Gravel falls into the stair.** The seventh live iron-kit run (local landing 2360,3000) cut its staircase through gravel at y 37-40: the gravel over the cells it opened fell into them, the walk onto the stair failed `:stair-not-taken`, then two digs found their cell already emptied and failed `:target-gone`, ending the plan. A cell refilled is dug again, a cell emptied counts as open, and the step goes back to digging; the server model drops gravel the way the game does ([[server-model]]), and `gravel-that-refills-a-stair-is-dug-again` cuts two stairs into a gravel cliff (it fails with the old stages). A falling block that lands on the bot's head is not handled.

## See also
- [[dig-timeline]] - the child.
- [[sib-dig-down-safety]] - the rules' origin.
