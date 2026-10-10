---
title: One stack per ingredient
description: Why recipe/clicks takes every cell of an ingredient from a single source stack chosen up front, and refuses a craft that would need two partial stacks.
type: decision
tags: [bot, decision, crafting]
aliases: [single source stack, mixed plank species, no stack merging]
status: verified
lastUpdated: 2026-10-09
verifiedAgainst: 5c7d6c1
sourceRefs:
  - src/clojurecraft/recipe.clj#defn clicks
  - test/clojurecraft/recipe_test.clj#one stack per ingredient: a short stack is skipped for one that covers it
  - "docs/issues/01-crafting-as-data.md#Our craft/clicks is a pure function of the inventory value that chooses one source stack per ingredient key up front."
related:
  - "[[bot/decisions/_moc|Decisions]]"
  - "[[recipe-clicks]]"
---

# One stack per ingredient

## The choice
For each ingredient group, `recipe/clicks` picks one inventory stack that covers every cell of the group (after what earlier groups took), picks it up once, right-clicks one item into each cell, and puts the rest back. If no single stack covers the group, `clicks` returns nil and the craft fails `:no-ingredient`.

## What was rejected
Cell-by-cell "find any matching item" fills. Per issue 01's research, steve's and ruststeve's tables needed four of one tag, and a cell-by-cell fill grabbed the plank it had just placed in the grid (mixed species and grid items confused the search). One stack chosen from the inventory value before any click cannot see the grid at all.

## What would change the answer
A want that legitimately needs more than one stack's worth or more than 64 of an item, or inventories fragmented into many short stacks (after deaths or chest work). Then a merge step (collect into one stack) or multi-source groups become necessary; `recipe/clicks` is the one function to change.

## See also
- [[one-item-per-cell]] - the per-cell half of the same plan.
