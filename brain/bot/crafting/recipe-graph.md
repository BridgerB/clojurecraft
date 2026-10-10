---
title: Recipe graph
description: How recipe/next-action walks from wanted items to the single next thing to do (craft a recipe, gather logs, stuck, or nothing), and how it chooses between recipes.
type: reference
tags: [bot, crafting, planning]
aliases: [next-action, resolve-want, recipe graph, crafting planner, max-depth, :stuck]
status: verified
lastUpdated: 2026-10-09
verifiedAgainst: 5c7d6c1
sourceRefs:
  - src/clojurecraft/recipe.clj#defn next-action
  - src/clojurecraft/recipe.clj#defn resolve-want
  - src/clojurecraft/recipe.clj#def max-depth 6
  - src/clojurecraft/recipe.clj#defn consume
  - test/clojurecraft/recipe_test.clj#the-graph-walks-to-the-next-action
related:
  - "[[bot/crafting/_moc|Crafting]]"
  - "[[make-goals]]"
  - "[[recipe-table]]"
  - "[[any-log-species]]"
---

# Recipe graph

`(recipe/next-action counts wants size)` answers "what do I do now?" for an ordered list of wants (`[[item-name n] ...]`) given `counts` (`{item-name n}`, from `recipe/counts` over the inventory) and a grid size. It returns `{:action :craft :recipe id}`, `{:action :gather :want :logs}`, `:stuck`, or nil when every want is already held. It is called fresh every tick; nothing about the plan is stored.

## Key files
- `recipe.clj`, `next-action` - walks the wants in order, threading `counts` through, and returns the first non-nil action.
- `recipe.clj`, `resolve-want` - `[counts' action]` for "n of any item in set s"; the recursion.
- `recipe.clj`, `consume` - deducts held items (sorted species order) so a later want does not count an item an earlier want already claimed.
- `recipe.clj`, `max-depth` - 6; deeper recursion returns `:stuck`.

## How it works
1. Held enough (`have counts s >= n`): consume them, no action.
2. Otherwise consume what is held and look at the shortfall. If the set is raw (all logs, see [[recipe-table]]): `{:action :gather :want :logs}`.
3. Else candidates are `by-result` for every item in the set, filtered by `fits?` for the grid size, sorted by (most ingredients already held, then largest output count).
4. For each candidate, `crafts = ceil(missing / count)` and each ingredient is resolved recursively for `k * crafts`; the first ingredient that needs an action returns that action (depth first, ingredient order). A candidate whose ingredients all resolve returns `{:action :craft :recipe id}` for itself. A `:stuck` candidate is skipped; if every candidate is stuck, `:stuck`.

## Worked example (the kit, `[[:crafting_table 1] [:stick 4]]`, size 2)
- `{}` → gather logs. `{:oak_log 1}` → craft `:oak_planks`. `{:birch_log 1}` → craft `:birch_planks` (the species held).
- `{:oak_planks 4}` → craft `:crafting_table`. `{:crafting_table 1 :oak_planks 2}` → craft `:stick`.
- `{:crafting_table 1}` → gather logs again: the table is kept (consumed by the first want), sticks need two more planks.

## Gotchas
- Sorting by held ingredients is what keeps `:stick` from choosing `:stick_from_bamboo_item`, and the larger output breaks ties toward it.
- Size matters: with 3 planks and 2 sticks, `[[:wooden_pickaxe 1]]` in size 2 is `:stuck` because the pickaxe recipe does not fit a 2x2 grid; it is not "gather more". That is why `make.clj` asks in size 3.
- Only one action comes back; the planner re-asks after it is done. A long chain (log → planks → table) costs one planner pass per craft, which is the point: a lost item is re-planned for free.
- Non-log raw materials are not modelled, so any want that bottoms out in cobblestone or iron is `:stuck` today.

## Limits
The graph does not know whether a table is available; [[make-goals]] asks it in size 3 and decides per recipe whether a table is needed. Furnace recipes are out of scope (issue #6).

## See also
- [[make-goals]] - the goal that consumes this.
- [[any-log-species]] - why gather is "any log".
