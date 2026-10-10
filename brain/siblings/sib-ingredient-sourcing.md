---
title: Ingredient sourcing in the siblings
description: Where a craft's ingredients must come from and which recipe to pick: ruststeve picked the bamboo stick recipe and stalled three bots, and its grid-wide search picked a plank back out of the grid and minted buttons.
type: reference
tags: [siblings, ruststeve, crafting, recipes]
aliases: [bamboo stick, first recipe, find_ingredient_slot, mixed plank species, recipe choice]
status: verified
lastUpdated: 2026-10-09
verifiedAgainst: ruststeve bc575e3
sourceRefs:
  - "ruststeve:CHANGES.md#Item 270 is **bamboo**: `craft_item` took `recipes.into_iter().next()`, and in this dark-oak region that was the bamboo→stick variant."
  - "ruststeve:src/bot/crafting.rs#fn find_ingredient_slot(window: &Window, ingredient: &RecipeItem) -> Option<usize> {"
  - "ruststeve:src/bot/crafting.rs#/// result or grid slots (cycle 6): searching the whole window found the plank just placed in the grid once"
  - src/clojurecraft/recipe.clj#defn clicks
  - src/clojurecraft/recipe.clj#defn- resolve-want
related:
  - "[[siblings/_moc|Siblings]]"
  - "[[recipe-clicks]]"
  - "[[recipe-graph]]"
  - "[[sib-craft-result-take]]"
---

# Ingredient sourcing in the siblings

Two ruststeve failures that are about choosing, not clicking.

## Key files
- ruststeve `CHANGES.md`, surface race index 3 - all three bots sat at `craft_sticks` ×20 with "missing crafting ingredient id=270" while holding 41 planks. Item 270 is bamboo: `craft_item` took the first recipe for `stick`, which was `stick_from_bamboo_item`. Fix: pick the first recipe whose consumed ingredients (tag choices included) the inventory covers.
- ruststeve `src/bot/crafting.rs`, `find_ingredient_slot` - searches only the window's inventory section. Searching the whole window "found the plank just placed in the grid once the cursor stack ran out, picked it back up and moved it to the next grid slot. With 1 oak + 28 cherry planks every stick and table craft made an oak_button."

## What they learned
1. A result has several recipes; pick by what the inventory can pay for, never by order.
2. Ingredients come from the inventory section, never from the grid being filled.
3. A cell-by-cell "any plank" fill mixes stacks and can run one stack dry mid-pattern.

## What it means here
`recipe/clicks` is a pure function of the inventory value: one source stack per ingredient group that covers every cell of that group, from window-0 inventory slots only, chosen before any click ([[recipe-clicks]]). `resolve-want` sorts candidate recipes by how much of their needs the inventory already covers, so bamboo sticks are never chosen without bamboo; the recipe test pins "never the bamboo stick" ([[recipe-graph]]).

## Limits
The covering-recipe fix's code in `src/bot_utils.rs` was not opened; the fix is cited from CHANGES.md only.

## See also
- [[sib-craft-result-take]]
