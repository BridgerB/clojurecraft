---
title: One item per cell, shift-click take
description: Why a craft right-clicks exactly one item into each grid cell, crafts once, and takes the result with a single verified shift-click instead of filling cells with stacks or taking onto the cursor.
type: decision
tags: [bot, decision, crafting]
aliases: [one craft per intent, shift-click result, mode 1 take, no make-all]
status: verified
lastUpdated: 2026-10-09
verifiedAgainst: 5c7d6c1
sourceRefs:
  - src/clojurecraft/recipe.clj#right-click one item into each cell, put the remainder back
  - src/clojurecraft/craft.clj#defmethod stage :verify
  - "docs/issues/01-crafting-as-data.md#Rule: the take is a shift-click (mode 1) so the cursor is never loaded; the sim asserts the cursor is empty whenever container-close is emitted."
related:
  - "[[bot/decisions/_moc|Decisions]]"
  - "[[recipe-clicks]]"
  - "[[craft-intent]]"
---

# One item per cell, shift-click take

## The choice
One `:craft` intent is one craft: one item per cell, then `:verify` shift-clicks slot 0 (mode 1) once the server shows exactly the expected result. Four sticks from planks are one intent; a goal that needs more crafts gets more intents from the graph.

## What was rejected
- Stacks in the cells plus a shift-click "make all": crafts as many as the grid allows, consuming planks the next want (the table) needed, and makes the outcome depend on stack sizes rather than on the plan.
- Taking the result onto the cursor (mode 0): leaves a loaded cursor that must be put down, and closing a window with a loaded cursor drops the items on the ground (14 planks lost in typecraft, per issue 01's research). A shift-click moves the result straight into the inventory.

## What would change the answer
Bulk crafting (dozens of sticks or torches) where one intent per craft costs too many round trips; then a counted "make n" variant beside this one, with the graph telling it n.

## Gotchas
In the sim, a shift-click on slot 0 loops while the grid still matches, so it is one craft only because each cell holds exactly one item ([[sim-window-clicks]]).

## See also
- [[junk-crafts]] - why slot 0 is verified first.
