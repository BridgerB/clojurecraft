---
title: Stairs down
description: How :stairs-down (and :stairs-up) cuts a staircase toward a height one stair at a time, which cells each stair opens and exposes, the refusal rules (water, lava, unloaded, a drop), the quarter turns and :boxed, the child :dig intents, and why a stair counts only when the feet went down.
type: reference
tags: [bot, plan, dig, mining, safety]
aliases: [:stairs-down, :stairs-up, up-cells, under-sky?, descend, climb out, child intent, :boxed, stair-cells, safe?, intents composing intents]
status: verified
lastUpdated: 2026-10-11
verifiedAgainst: HEAD
sourceRefs:
  - src/clojurecraft/stairs.clj#defn stair-cells
  - src/clojurecraft/stairs.clj#defn exposed
  - src/clojurecraft/stairs.clj#defn safe?
  - src/clojurecraft/intent.clj#defn run-child
  - src/clojurecraft/intent.clj#defn feet
  - src/clojurecraft/stairs.clj#def max-turns
  - src/clojurecraft/stairs.clj#defmethod intent/run :stairs-down
  - src/clojurecraft/stairs.clj#defmulti stairs-stage
  - test/clojurecraft/sim_test.clj#a-stone-hill-is-descended-five-stairs-with-a-pickaxe
  - test/clojurecraft/sim_test.clj#boxed-in-by-lava-on-every-side-fails-boxed
  - test/clojurecraft/sim_test.clj#water-beside-a-stair-cell-turns-the-stairs
  - src/clojurecraft/stairs.clj#def refusals
  - src/clojurecraft/stairs.clj#defn step-aside
  - src/clojurecraft/stairs.clj#defn level-safe?
  - test/clojurecraft/sim_test.clj#water-under-every-stair-is-stepped-around
  - src/clojurecraft/stairs.clj#defn fell?
  - src/clojurecraft/stairs.clj#defn up-cells
  - src/clojurecraft/stairs.clj#defn up-safe?
  - src/clojurecraft/stairs.clj#def shapes
  - src/clojurecraft/stairs.clj#defn taken?
  - src/clojurecraft/terrain.clj#defn under-sky?
  - test/clojurecraft/sim_test.clj#stairs-up-cut-a-way-out-of-the-rock
  - test/clojurecraft/sim_test.clj#gravel-over-the-head-is-never-dug-under
  - src/clojurecraft/stairs.clj#defn tunnel-plan
  - src/clojurecraft/stairs.clj#defn tool-missing
  - test/clojurecraft/sim_test.clj#a-tunnel-with-no-pickaxe-stops-for-one
  - test/clojurecraft/sim_test.clj#a-tunnel-goes-round-what-refuses-it
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
- `stairs.clj`, `safe?` - none of the opened cells water, lava or unloaded; no lava in any exposed cell; a floor under the new feet.
- `intent.clj`, `feet` - y a touch below the position, since physics jitter dips it under the integer.
- `intent.clj`, `run-child` - the child `:dig` (`intent/child-dig`) stands in as `:plan/intent` for one tick of the dig executor, then returns to `:intent/child`: intents composing intents, with one rule for breaking blocks ([[dig-timeline]]).
- `stairs.clj`, `max-turns` 4, `step-timeout-ticks` 200.

## How it works
Each stage is a method of `stairs-stage`; `run :stairs-down` only checks whether the feet are down far enough.
1. `:plan`: with the current heading (`+x` first), compute the stair; `safe?` → `:open`; else turn a quarter and count. Four refusals → `step-aside`: a level step (`level-cells`: head and feet one block along, same height) toward the first heading `level-safe?` allows (a stair's rules, and no opened cell bordering a fluid, which the child dig would refuse), then `:plan` again from there with the turns counted afresh. No heading allows one, or `max-level-steps` (12) are spent: fail `:boxed`.
2. `:open`: while any of the three cells is solid, run a child dig of the topmost. A child that finds its cell already open (`:target-gone`, `fell?`) is as good as done: gravel or sand fell out of it. A child refused for one of `refusals` turns the stair like an unsafe plan: `:needs-tool` (ore the pickaxe cannot drop) or `:unsafe` (a cell beside water or lava, which the dig executor will not open, since it would let the fluid in). Any other failure fails the stair with its reason.
3. `:level`: dig the level step's cells with child digs, then walk until the feet are in its feet cell; 10 s from the step's start without that fails `:stair-not-taken`.
4. `:step`: walk toward the new feet cell; done with the stair when the feet are half a block below where it began, which is the only proof the server broke what the bot asked for. A stair cell solid again (gravel fell into it) goes back to `:open`; 10 s without either fails `:stair-not-taken`.
5. Done when the feet are at or below `to-y`.

## Stairs up
`{:intent/kind :stairs-up :intent/to-y n}` is the same staircase climbed (`shapes`: each kind's cells, rule and rise). A stair up opens `up-cells`: the cell over the head, which the jump needs, then the new head and the new feet one block up and ahead; the block ahead at the feet's height is the step. It is cut under `up-safe?`: `safe?`'s rules, and no gravel or sand over the cell above the head, which would fall through it onto the bot (`gravel-over-the-head-is-never-dug-under`: every heading refused, a level step out from under it). `:step` walks into the step, which jumps; `taken?` going up also needs the feet on the ground, since halfway up a jump the feet are already half a block higher and the next stair was once planned from the air. Done standing at `to-y`, or under the open sky (`terrain/under-sky?`: nothing but air, plants or leaves over the feet to the top of the world). The scoop's `climb` cuts these when no route leads back to the landing ([[scoop-intent]]).

## Tunnels
`{:intent/kind :tunnel :intent/dir d :intent/length n}` is level steps alone: `tunnel-plan` takes the next along its heading when `level-safe?`, else a step to one side, keeping the heading, so the tunnel goes round what refused it; the side is the first of the two allowed and then the only one (`:intent/side`), so it never steps back and forth; `:boxed` when neither the heading nor that side allows a step. It never turns back down itself: when it turned a quarter at each refusal, live run 8 (refused east at y 16) tunnelled west beside its own tunnel and gym batch iron-a2 run 4 walked its tunnel back and forth for eight minutes (`a-tunnel-goes-round-what-refuses-it`). The `:level` stage is the staircase's own. Done after n steps. The mine act cuts one at a row's depth ([[mine-act]]).

## Gotchas
- Keys that are over (`:intent/child`, `:intent/cells`) are removed, never nil: the world spec refuses a nil vector.
- Water beside a stair is allowed, lava is not: the siblings found refusing water wedged descents in wet biomes. A cell that itself borders water is still never dug (the dig's `exposes-fluid?`): the stair turns instead. Before it turned, a live iron-kit descent (local landing 2360,2360) met an aquifer at y 36 and failed `:unsafe` three times in a moment, the planner restarting the same refused stair (`water-beside-a-stair-cell-turns-the-stairs`).
- **A lake under every stair is stepped around.** The fifth live iron-kit run (local landing 3024,2360) stood at y 52 over an underground lake filling y 48-51: every stair's new feet had water under or beside them, and the staircase failed `:boxed` three times in a moment. A level step leads along until a stair is safe; `boxed-in-by-lava-on-every-side-fails-boxed` now needs lava beside the feet too.
- **A broken pickaxe stops the staircase.** A cell that drops only to a tool with none of its kind in the hotbar is never dug by hand: the stairs or tunnel fail `:no-pickaxe` (`tool-missing`) and the planner makes another. Batch iron-a2 runs went on by hand when the pickaxe broke, 7500 ms a stone for nothing, until a step timed out. A level step's 10 s is the walk's alone: it starts again after each dig.
- **Gravel falls into the stair.** The seventh live iron-kit run (local landing 2360,3000) cut its staircase through gravel at y 37-40: the gravel over the cells it opened fell into them, the walk onto the stair failed `:stair-not-taken`, then two digs found their cell already emptied and failed `:target-gone`, ending the plan. A cell refilled is dug again, a cell emptied counts as open, and the step goes back to digging; the server model drops gravel the way the game does ([[server-model]]), and `gravel-that-refills-a-stair-is-dug-again` cuts two stairs into a gravel cliff (it fails with the old stages). A falling block that lands on the bot's head is not handled.

## See also
- [[dig-timeline]] - the child.
- [[sib-dig-down-safety]] - the rules' origin.
