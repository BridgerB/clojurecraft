---
title: Make goals
description: The needs planner - how a target's provides are traced back through producer rows (gather a log, place a table, one generated row per recipe) to the row whose needs are met, with one netted inventory - and the :gather, :place and :craft acts.
type: reference
tags: [bot, crafting, plan, goals]
aliases: [needs planner, make.clj, craft-rows, producers, next-row, resolve-need, provided?, table ladder, decide]
status: verified
lastUpdated: 2026-10-11
verifiedAgainst: HEAD
sourceRefs:
  - src/clojurecraft/make.clj#defn craft-row
  - src/clojurecraft/make.clj#defn resolve-need
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
- `make.clj`, `resolve-need` - one need, by its kind: `resolve-item-need` nets a counted need in the working inventory and tries its producers in `producer-order` (most of their own needs held, then no table needed, then the larger yield); `resolve-block-need` meets `:block/... :near` with a remembered placed block within 24 blocks, else the `:place-table` row. Both try candidates through `first-resolved`.
- `make.clj`, `decide` - a log chain in flight first ([[gather-chain]]), else `plan/act` on the row `next-row` reaches; `:stuck` becomes `{:plan/wait :stuck}`.
- `make.clj`, the acts - `:gather` runs the log chain, `:place` puts the held table down ([[place-intent]]), `:craft` crafts in the 2x2 grid, or for a table recipe: in the open table window, else open the table in reach ([[open-container-intent]]), else walk to it.

## Example: the pickaxe
`:pickaxe` provides `{:item/wooden_pickaxe 1}`. Its producer is the generated row `:craft/wooden_pickaxe` with needs `{:tag/planks 3 :item/stick 2 :block/crafting_table :near}`. Planks come from `:craft/oak_planks` (or the species held), whose need `:tag/oak_logs` comes from `:gather-log`; the table need comes from `:place-table`, whose need `:item/crafting_table` comes from `:craft/crafting_table`. In the sim this runs from one tree to a held pickaxe with no failed attempts and no violations.

## Gotchas
- Provides are resolved in the order written (EDN array maps keep it), so `:kit` reserves planks for the table before the sticks.
- The bamboo stick and other dead ends lose because nothing provides their needs (`:stuck` for that branch), not because they are filtered out.
- The planner property in `make_test` (400 generated inventories) checks that every chosen intent is executable; it was mutation-checked against a planner that places without a table.

## See also
- [[one-planner]] - why the older recipe-only walk was removed.
- [[goals-and-intents]]
