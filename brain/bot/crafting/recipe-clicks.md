---
title: Recipe clicks
description: How recipe/clicks turns one craft into window-0 clicks from the inventory value: one source stack per ingredient, a right-click per cell, the remainder put back; nil when it cannot.
type: reference
tags: [bot, crafting, clicks]
aliases: [clicks, click plan, laying a recipe, placement, player->container-slot]
status: verified
lastUpdated: 2026-10-09
verifiedAgainst: 5c7d6c1
sourceRefs:
  - src/clojurecraft/recipe.clj#defn clicks
  - src/clojurecraft/recipe.clj#defn placement
  - src/clojurecraft/recipe.clj#defn player->container-slot
  - test/clojurecraft/recipe_test.clj#clicks-lay-one-craft
  - test/clojurecraft/recipe_test.clj#clicks-then-match-round-trip
related:
  - "[[bot/crafting/_moc|Crafting]]"
  - "[[craft-intent]]"
  - "[[one-stack-per-ingredient]]"
  - "[[one-item-per-cell]]"
  - "[[mc-container-click]]"
---

# Recipe clicks

`(recipe/clicks inventory r size)` (or `(clicks inventory r size slot-of)`) returns the clicks that lay **one** craft of recipe `r` into the size×size grid of window 0 or, with a table's `slot-of`, of an open crafting table, as `[{:click/slot s :click/button b :click/mode m} ...]`, or nil when the inventory cannot cover it. It is a pure function of the `:player/inventory` value; [[craft-intent]] sends the clicks one per round trip.

## Key files
- `recipe.clj`, `placement` - `{grid-slot ingredient-set}`: a shaped recipe at the top-left (`1 + row*size + col`), a shapeless one in slots 1..n.
- `recipe.clj`, `clicks` - groups cells by ingredient set, picks a source stack per group, emits the clicks.
- `recipe.clj`, `player->container-slot` - the default `slot-of`, player slot to window-0 slot: hotbar 0-8 → 36-44, main 9-35 unchanged, offhand 40 → 45, armour not offered. A table view passes its own map ([[window-views]]).

## How it works
For each ingredient group (cells sharing one set):
1. Source: the lowest-numbered inventory slot whose item is in the set and whose count, minus what earlier groups already took from it, covers every cell in the group.
2. Left-click the source (`button 0 mode 0`): the whole stack goes to the cursor.
3. Right-click each cell in slot order (`button 1 mode 0`): one item per cell.
4. If anything is left on the cursor, left-click the source again to put it back.

Sticks from 8 oak planks in hotbar slot 0: `36 b0`, `1 b1`, `3 b1`, `36 b0`. A stack used up exactly needs no put-back (crafting table from 4 planks is 5 clicks).

## Gotchas
- One stack per ingredient: 1 oak + 4 birch planks skips the short oak stack and takes birch; 3 planks total returns nil even if spread over stacks. See [[one-stack-per-ingredient]].
- The click plan is computed once, in `:settle`, from the inventory as it was then; the plan assumes nothing else moves while it runs.
- The round-trip property (`clicks-then-match-round-trip`, 300 cases over every 2x2-fitting recipe) proves the right-clicked cells are exactly `placement`'s cells and that `match` on them gives the recipe's result.

## See also
- [[recipe-match]] - why the laid grid crafts what was intended.
- [[one-item-per-cell]] - why one item per cell and a shift-click take.
