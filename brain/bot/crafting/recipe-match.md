---
title: Recipe match
description: The server's rule for what a crafting grid makes, as recipe/match implements it: shaped at any offset and mirrored, shapeless by set assignment, first match in table order.
type: reference
tags: [bot, crafting, recipes, sim]
aliases: [match, what does the grid craft, shaped match, shapeless match, mirror]
status: verified
lastUpdated: 2026-10-09
verifiedAgainst: 5c7d6c1
sourceRefs:
  - src/clojurecraft/recipe.clj#defn- shaped-match?
  - src/clojurecraft/recipe.clj#defn- shapeless-match?
  - src/clojurecraft/recipe.clj#defn match
  - src/clojurecraft/recipe.clj#defn fits?
  - test/clojurecraft/recipe_test.clj#match-is-the-servers-rule
related:
  - "[[bot/crafting/_moc|Crafting]]"
  - "[[recipe-table]]"
  - "[[junk-crafts]]"
  - "[[sim-window-clicks]]"
---

# Recipe match

`(recipe/match grid size)` returns the recipe a grid crafts, or nil. `grid` is `{slot item-name}` for slots `1..size²` in reading order (slot 0 is the result and never part of the grid); size 2 is the inventory grid, size 3 a crafting table. The bot does not use `match` to decide anything; the sim uses it to compute slot 0 ([[sim-window-clicks]]), and tests use it to prove that the clicks lay a real recipe.

## Key files
- `recipe.clj`, `match` - drops nil cells, then the first recipe in table order that `fits?` the grid size and matches.
- `recipe.clj`, `shaped-match?` - the occupied cells' bounding box must equal the pattern's `[height width]`; then every pattern cell is compared at that offset, plain or mirrored left-right.
- `recipe.clj`, `shapeless-match?` - item count equals ingredient count, then a backtracking assignment of each item to a distinct ingredient set that contains it.
- `recipe.clj`, `fits?` - shaped: width and height within size; shapeless: ingredient count within size².

## How it works
1. Shaped recipes are position-independent: `{1 :oak_planks 3 :birch_planks}` and `{2 :oak_planks 4 :oak_planks}` are both `:stick` (any column, mixed species allowed because the key is a set).
2. A shaped pattern also matches its horizontal mirror, as vanilla does.
3. Shapeless recipes match anywhere in the grid: one `:oak_log` in slot 4 is `:oak_planks`.
4. Order is the table's (sorted by `:recipe/id`); the first hit wins.

## Gotchas
- A grid that is "almost" a recipe is often a different recipe: two planks side by side are `:oak_pressure_plate`, one plank alone is `:oak_button`. See [[junk-crafts]].
- `match` is the server's rule as far as tested; it is not vanilla's code. Special recipes (`crafting_special_*`, e.g. map cloning, firework stars) are not in the table, so `match` returns nil for them where vanilla would not.

## Limits
Verified by the tests named above; behaviour for ambiguous grids that two table recipes both match was not checked against a live server.

## See also
- [[recipe-clicks]] - laying a recipe into the grid.
