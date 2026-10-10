---
title: Grid in its own attribute
description: Why window-0 slots 0-4 live in :window/grid instead of being merged into :player/inventory, and what that buys the goal-completion checks.
type: decision
tags: [bot, decision, crafting, world]
aliases: [window/grid not inventory, grid not counted, wood-lock prevention]
status: verified
lastUpdated: 2026-10-09
verifiedAgainst: 5c7d6c1
sourceRefs:
  - src/clojurecraft/inventory.clj#defn set-window-0-slot
  - src/clojurecraft/inventory.clj#How many of an item (by id) the player holds; the crafting grid is not the inventory.
  - test/clojurecraft/game_test.clj#grid items are in the grid, not double-counted
related:
  - "[[bot/decisions/_moc|Decisions]]"
  - "[[window-zero-model]]"
  - "[[sib-wood-lock]]"
---

# Grid in its own attribute

## The choice
Window-0 slots 0-4 are kept verbatim in `:window/grid`; `:player/inventory` holds only slots the player keeps (hotbar, main, armour, offhand). Inventory counts (`inventory/item-count`, `recipe/counts`) read only `:player/inventory`.

## What was rejected
- Dropping slots 0-4 (the first version: `container->player-slot` returned nil and the packet was ignored). Items in the grid then vanished from the world, which is exactly steve's wood-lock: a result or ingredient stranded in the grid was invisible, completion checks flickered, and two steps oscillated forever ([[sib-wood-lock]]).
- Counting grid items as inventory. Then a plank laid into the grid still counts as held, the recipe graph thinks it can afford the next craft, and the grid's contents (which the server may convert into a result) are double-booked.

## What would change the answer
Nothing for the separation itself. If death or a window close is ever observed to return grid items to the inventory without a slot update, the reducer for that packet must move them; `:window/grid` still stays its own attribute.

## See also
- [[window-zero-model]] - the four window attributes.
