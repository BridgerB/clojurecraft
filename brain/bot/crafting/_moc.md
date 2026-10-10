---
title: Crafting
type: moc
tags: [bot, crafting]
status: verified
lastUpdated: 2026-10-10
verifiedAgainst: 3452f18
related:
  - "[[bot/_moc|Bot pillar]]"
---

# Crafting

Crafting as data (issues #2 and #7): the recipe table, the graph from wants to the next action, the `:craft` intent in window 0 or a crafting table, placing and opening the table, and the sim's window model.

## Data and planning
- [[recipe-table]] - what recipes.edn holds, the shape of a recipe, the lookups.
- [[recipe-match]] - what a grid crafts: shaped at any offset and mirrored, shapeless by assignment.
- [[recipe-clicks]] - one craft as window-0 clicks from the inventory value, or nil.
- [[make-goals]] - the needs planner: provides traced back through producer rows (gather, place a table, one row per recipe) to the row to act on.

## Executing
- [[craft-intent]] - settle, click, verify, take, in either window; timeouts and failure reasons.
- [[window-views]] - one click layer for window 0 and an open table: view, click, waiting, free-slot.
- [[window-zero-model]] - window 0, the open container, the held slot, and the packets that write them.
- [[place-intent]] - equip, pick a spot, right-click, done only on the server's block update.
- [[open-container-intent]] - right-click within 4.5, done when the window and its contents arrive.

## The sim's window
- [[sim-window-clicks]] - vanilla click rules in the model, slot 0 computed by match.
- [[sim-window-sync]] - how a click is answered: differences only, state id per slot, resync on stale id.
- [[sim-faults]] - lost clicks as an input, violations as a record.
- [[sim-placement]] - use-item-on in the model: open a table, place one, or reject.

## Gotchas
- [[phantom-cursor-stale-window]] - a harmless click times out because the model kept a cursor the server emptied.
- [[junk-crafts]] - leftover planks become a pressure plate or a button.

## See also
- [[bot/decisions/_moc|Decisions]] - manual clicks, predict nothing, one stack, one item per cell, grid attribute, any log, placement judged by the server, spot two away.
- [[mc-window-zero-slots]], [[mc-crafting-table-window]], [[mc-container-click]], [[mc-use-item-on]] - the vanilla side.
- [[sib-wood-lock]] - the sibling failure this design answers.
