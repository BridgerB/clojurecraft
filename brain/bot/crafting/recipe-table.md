---
title: Recipe table
description: What recipes.edn and item-tags.edn hold, how a recipe map is shaped (shaped vs shapeless, ingredient sets), and the lookups recipe.clj builds over them.
type: reference
tags: [bot, crafting, recipes, data]
aliases: [recipes.edn, item-tags.edn, recipe map, by-result, by-id, ingredient set]
status: verified
lastUpdated: 2026-10-09
verifiedAgainst: 5c7d6c1
sourceRefs:
  - src/clojurecraft/recipe.clj#def recipes
  - src/clojurecraft/recipe.clj#def by-result
  - src/clojurecraft/recipe.clj#def raw?
  - src/clojurecraft/recipe.clj#defn needs
  - resources/clojurecraft/recipes.edn#{:id :stick, :result :stick, :count 4, :kind :shaped
  - test/clojurecraft/recipe_test.clj#the-table-is-vanilla
related:
  - "[[bot/crafting/_moc|Crafting]]"
  - "[[mc-recipes-in-jar]]"
  - "[[datagen]]"
  - "[[recipe-graph]]"
---

# Recipe table

Crafting is data: `recipe/recipes` is the vanilla crafting table read from `resources/clojurecraft/recipes.edn`, every recipe a namespaced map, and every function in `recipe.clj` is pure over it. The table holds only `crafting_shaped` and `crafting_shapeless` recipes; furnace, smithing and stonecutter recipes are not in it.

## Key files
- `recipe.clj`, `recipes`, `tags`, `by-result`, `by-id` - the table, the resolved item tags, and two indexes (`group-by :recipe/result`, `:recipe/id` to recipe).
- `recipe.clj`, `raw?` - an ingredient set is raw (gathered, never crafted) when every item in it is in the `:logs` tag.
- `recipe.clj`, `needs` - `{ingredient-set count}` consumed by one craft.
- `recipes.edn` - generated; see [[datagen]] and [[mc-recipes-in-jar]].
- `recipe_test.clj`, `the-table-is-vanilla` - pins the count (1030) and the exact `:stick` map.

## Shape of a recipe
- Common: `:recipe/id` (the JSON file stem, e.g. `:stick_from_bamboo_item`), `:recipe/result` (item name keyword), `:recipe/count` (default 1), `:recipe/kind` (`:shaped` or `:shapeless`).
- Shaped: `:recipe/pattern` (vector of row strings, space = empty), `:recipe/key` (`{"#" #{item ...}}`), `:recipe/width`, `:recipe/height`.
- Shapeless: `:recipe/ingredients`, a vector of sets, one per item consumed.
- Every ingredient is a **set of item-name keywords**: tags (`#minecraft:planks`) are resolved at datagen time, so `:stick`'s key is the twelve plank species, and `:oak_planks` takes `#{:oak_log :oak_wood :stripped_oak_log :stripped_oak_wood}`.

## Gotchas
- Two recipes can make the same result: `:stick` (4 from two planks) and `:stick_from_bamboo_item` (1 from two bamboo). `by-result` returns both; [[recipe-graph]] chooses.
- `item-name` / `item-id` translate between the numeric item ids on the wire and these name keywords via `blocks/items`; inventory maps carry ids, recipes carry names.
- `raw?` is the only place the graph stops recursing: anything not all-logs is assumed craftable, so a missing non-log raw (cobblestone, iron) currently makes the graph return `:stuck` rather than `:gather`.

## Limits
Covers the crafting table only. Smelting recipes and fuel are issue #6 and absent from this table.

## See also
- [[recipe-match]] - what a grid crafts.
- [[recipe-graph]] - from wants to the next action.
