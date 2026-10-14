---
title: Make goals
description: The needs planner - how a target's provides are traced back through producer rows (gather a log, place a table, one generated row per recipe and per furnace recipe) to the row whose needs are met, with one netted inventory, fuel as a need and no cycles - and the :gather, :place, :craft and :smelt acts.
type: reference
tags: [bot, crafting, plan, goals]
aliases: [needs planner, make.clj, craft-rows, producers, next-row, resolve-need, provided?, table ladder, decide]
status: verified
lastUpdated: 2026-10-14
verifiedAgainst: HEAD
sourceRefs:
  - src/clojurecraft/make.clj#defn craft-row
  - src/clojurecraft/make.clj#defn cycle?
  - src/clojurecraft/make.clj#defn smelt-row
  - src/clojurecraft/make.clj#defn resolve-fuel-need
  - src/clojurecraft/make.clj#def fuel-sources
  - test/clojurecraft/make_test.clj#the-planner-never-smelts-without-the-fuel-for-it
  - test/clojurecraft/make_test.clj#the-iron-kit-is-planned-in-order
  - src/clojurecraft/make.clj#defn at-container
  - test/clojurecraft/make_test.clj#a-need-already-being-resolved-is-a-dead-end
  - src/clojurecraft/make.clj#defn resolve-need
  - src/clojurecraft/make.clj#defn craft-now
  - src/clojurecraft/make.clj#defn resolve-spares
  - test/clojurecraft/make_test.clj#a-pickaxe-for-the-coal-is-made-from-what-is-carried
  - test/clojurecraft/make_test.clj#a-pickaxe-for-the-gravel-is-made-from-what-is-carried
  - test/clojurecraft/make_test.clj#the-table-goes-along-to-the-water
  - src/clojurecraft/make.clj#defn spare-worth?
  - test/clojurecraft/make_test.clj#a-shaft-to-the-sky-is-not-a-spare
  - src/clojurecraft/make.clj#defn take-table
  - test/clojurecraft/sim_test.clj#the-table-is-taken-along-when-the-bot-goes-out
  - src/clojurecraft/make.clj#defn fetch?
  - src/clojurecraft/make.clj#defn wood-covers?
  - src/clojurecraft/make.clj#defn resolve-item-need
  - src/clojurecraft/make.clj#defn resolve-block-need
  - src/clojurecraft/make.clj#defn producer-order
  - src/clojurecraft/make.clj#defn next-row
  - src/clojurecraft/make.clj#defn decide
  - src/clojurecraft/make.clj#defmethod plan/done-by :provided?
  - src/clojurecraft/make.clj#defmethod plan/act :craft
  - test/clojurecraft/make_test.clj#goals-are-data
  - test/clojurecraft/sim_test.clj#places-a-table-and-crafts-a-wooden-pickaxe
related:
  - "[[bot/crafting/_moc|Crafting]]"
  - "[[goals-and-intents]]"
  - "[[goals-in-play-from-go]]"
---

# Make goals

`make.clj` gives the goal table its meaning. A target is met when its provides are held (`done-by :provided?`). When it is not, `next-row` resolves each provide in order against one working copy of the inventory: a need already held is consumed from the copy, so two needs never count the same item; an unmet need is looked up among producer rows, and the first producer whose own needs resolve is the row to act on.

## Key files
- `make.clj`, `craft-row` - a recipe as a goal row: ingredients become needs (named `:tag/planks`, `:item/stick`, or the item set when no tag matches), the result becomes what it provides, and a recipe that does not fit the 2x2 grid also needs `{:block/crafting_table :near}`. All 1,030 recipes become rows (`craft-rows`).
- `make.clj`, `smelt-row` - a furnace recipe as a goal row: its input, then `{:fuel/ticks t}` (the recipe's cook ticks, times the runs like any need), then `{:block/furnace :near}` as needs, its result as what it provides, act `:smelt` (`smelt-rows`, one per `smelting.edn` row, after the craft rows in `producers`). The fuel comes after the input, so the input is never counted as its own fuel.
- `make.clj`, `resolve-fuel-need` - a `:fuel/ticks n` need: `burn` takes fuel worth n ticks out of the working inventory in the smelt's own burn order (coal, planks, sticks; whole items), and what it cannot cover is planned from `fuel-sources`: coal first (mined beside the iron), then planks from logs; planks first when the logs held, but one kept back for a new pickaxe's sticks or table (`log-reserve`), make enough of them (`wood-covers?`), since the twelfth live run held two logs, eight planks of fire, and tunnelled thirteen minutes for coal. Charcoal is never made: it burns wood to save wood. `the-planner-never-smelts-without-the-fuel-for-it` (400 generated inventories) holds that a smelt row is only reached when the fuel burns 200 ticks per ingot asked; with the fuel need taken off the row it fails, shrunk to one raw iron and nothing to burn.
- `make.clj`, `resolve-need` - one need, by its kind: `resolve-item-need` nets a counted need in the working inventory and tries its producers in `producer-order` (most of their own needs held, then no table needed, then the larger yield); `resolve-block-need` meets `:block/... :near` with a remembered placed block within 24 blocks, else the `:place-table` row. Both try candidates through `first-resolved`; a craft row asked for several runs that would first send the bot to gather or mine, but can run once from what is held, is crafted now (`craft-now`), so held logs become planks before more are fetched (not smelts, which are batched on purpose). Each item need carries the path of item sets being resolved above it: past `max-depth` (12) of them, or when the need is already on the path (`cycle?`: an ingot from a block made of ingots, from nuggets cut from an ingot), it is `:stuck` rather than searched deeper. The recipes alone hold that iron cycle: `next-row` toward four ingots with nothing held took 0.84 ms a call searching it to depth eight, 0.13 ms with the cut (measured 2026-10-11 with the bound at 8, 200 calls after warm-up). The smelt rows add more ways round (ingots from raw iron or iron ore, nuggets from smelted iron tools): toward four ingots holding two raw iron, 20.8 ms a call without the cut, 0.15 ms with it.
- `make.clj`, `resolve-spares` - a row's `:goal/spares` (one more of a held tool: the fill row's second pickaxe, `:mine-iron`'s second stone pickaxe, `:mine-coal`'s second pickaxe: the ingots' coal is dug at depth after the iron, and race 38477665442 bot Clj_r20 dug it with its last pickaxe beside the sticks, table and cobblestone for another, issue #36; and `:dig-gravel`'s, since the gravel is tunnelled for at y 40 after the water, and bot Clj_r19 of the same race wore both pickaxes out there with two sticks and 245 cobblestone in hand, issue #67, `a-pickaxe-for-the-gravel-is-made-from-what-is-carried`) are resolved after its holds, as the held count plus the spare. The producer is acted on when `spare-worth?`: not `:stuck`, never a `:mine`, and a `:gather` only when `gather-next` returns an intent that starts a log (a walk to a trunk, or stairs up from underground). A walk up the shaft already dug (`:intent/for :opening`) is not one: it reaches the sky, and from that mouth the trunk may still have no route. Full run f277605 held one stone pickaxe and spent five hours walking out for the spare and cutting back down (`a-shaft-to-the-sky-is-not-a-spare`). A gather that would wait `:no-log` is no need either, which is the open sky with no tree remembered. Gym batch iron-a7 runs 1-3 held a working pickaxe deep in the mine, no wood, and a fill row holding two: each waited `:no-log` until the plan failed. Full run 068485c kept mining on its last stone pickaxe until it broke at y 16. Full run 4528031 crafted the bucket at y 17 on its last pickaxe: the spare's gather was a new stair, and the pickaxe broke at y 49.
- `make.clj`, `decide` - a log chain in flight first ([[gather-chain]]), then a table pick-up in flight (dig, then collect, `:intent/for :take-table`), else `plan/act` on the row `next-row` reaches; `:stuck` becomes `{:plan/wait :stuck}`. Before a row that goes out (`fetch?`: a gather, a mine, or a scoop), `take-table` digs the placed crafting table within reach when none is held, so the table goes along: gym batch iron-a7 run 1 crafted three tables on its way down, a log each, and had no wood left for a pickaxe. A scoop goes out as far as a mine: race 38477665442 bots Clj_r19, Clj_r23 and Clj_r06 crafted the bucket at the furnace down the mine, left the table there and climbed out for the water, and none could make the pickaxe the climb or the gravel tunnels wore out from the sticks and cobblestone it carried (`the-table-goes-along-to-the-water`, issues #39 and #67).
- `make.clj`, the acts - `:gather` runs the log chain, `:place` puts the held table or furnace down ([[place-intent]]), `:craft` crafts in the 2x2 grid, or for a table recipe goes through `at-container`: in the open table window, else open the table in reach ([[open-container-intent]]), else walk to it; `:smelt` sizes a batch from the held input and fuel and goes through `at-container` to the furnace ([[smelt-intent]]).

## The depth bound
The iron kit's chain from nothing is nine item needs above a log: water bucket, bucket, ingot, raw iron, stone pickaxe (held by `:mine-iron`), cobblestone, wooden pickaxe (held by `:mine-stone`), stick, plank. At a bound of 8 the planner said `:stuck` holding three planks toward a water bucket (the executability property found it, shrunk to exactly that); at 12, `next-row` toward the kit from nothing reaches `:gather-log` in 0.14 ms, and with three planks toward a water bucket in 0.24 ms (measured 2026-10-11). `the-iron-kit-is-planned-in-order` pins the rows the kit takes from each stage's inventory. The kit provides the flint and steel before the water bucket, so the fourth ingot and the flint are got while the bot is still down the mine and the climb out ends at the water: with the bucket first, gym batch iron-a4 run 3 filled it at the surface and went all the way down again for one ingot.

## Example: the pickaxe
`:pickaxe` provides `{:item/wooden_pickaxe 1}`. Its producer is the generated row `:craft/wooden_pickaxe` with needs `{:tag/planks 3 :item/stick 2 :block/crafting_table :near}`. Planks come from `:craft/oak_planks` (or the species held), whose need `:tag/oak_logs` comes from `:gather-log`; the table need comes from `:place-table`, whose need `:item/crafting_table` comes from `:craft/crafting_table`. In the sim this runs from one tree to a held pickaxe with no failed attempts and no violations.

## Gotchas
- Provides are resolved in the order written (EDN array maps keep it), so `:kit` reserves planks for the table before the sticks.
- A need with no tag (not one item, and no tag names its set) is keyed by the item set itself; `block-need?` tells such a key from a `:block/` one.
- The bamboo stick and other dead ends lose because nothing provides their needs (`:stuck` for that branch), not because they are filtered out.
- The planner property in `make_test` (400 generated inventories) checks that every chosen intent is executable; it was mutation-checked against a planner that places without a table.

## See also
- [[one-planner]] - why the older recipe-only walk was removed.
- [[goals-and-intents]]
