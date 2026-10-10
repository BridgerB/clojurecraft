---
title: Taking the craft result
description: Why the result slot is clicked only after verifying it: a stray plank turns a take into buttons (typecraft minted 25), grid must be swept before slot 0, shift-click takes the maximum, and closing with a loaded cursor drops it.
type: reference
tags: [siblings, steve, typecraft, crafting, gotcha]
aliases: [junk buttons, GRID FIRST result slot LAST, verify the result, cursor_stuck, oak_button]
status: verified
lastUpdated: 2026-10-09
verifiedAgainst: steve c28028b, ruststeve bc575e3
sourceRefs:
  - "steve:src/lib/typecraft/bot/crafting.ts#// VERIFY the result is the recipe's item before taking it. A right-click"
  - "steve:src/lib/typecraft/bot/crafting.ts#// Clear any items left in the crafting grid back to inventory. GRID FIRST,"
  - "steve:src/lib/typecraft/bot/crafting.ts#// Closing a window while an item is on the cursor DROPS it on the ground."
  - "steve:src/lib/typecraft/bot/crafting.ts#// Forget the client's idea of the result slot before placing: the"
  - "steve:src/lib/steve/lib/bot-utils.ts#// The RESULT slot: never shift-click it. Shift-click on a craft output crafts"
  - "src/clojurecraft/craft.clj#defmethod stage :verify"
related:
  - "[[siblings/_moc|Siblings]]"
  - "[[junk-crafts]]"
  - "[[craft-intent]]"
  - "[[sib-wood-lock]]"
---

# Taking the craft result

Symptom (typecraft/steve races): buttons, pressure plates, doors in the inventory and the planks gone; "25 birch buttons, 25 planks gone" in one craft.

## Key files
- typecraft `bot/crafting.ts`, the verify block - a right-click that did not land leaves one plank in the grid, whose result is a button or pressure plate; taking it blindly wasted the wood. It now compares slot 0 with the recipe's result, puts the grid back and throws `Wrong craft result` or `No craft result`.
- same file, the sweep - "GRID FIRST, result slot LAST: taking slot 0 while a stray plank still sits in the grid *crafts* that plank into a button".
- same file, `window.slots[0] = null` before placing - a cached result from the previous craft was trusted by the take step.
- same file, the `finally` - closing a window with an item on the cursor drops it (14 planks lost); park it in the inventory first.
- steve `bot-utils.ts`, `reclaimCraftingGrid` - never shift-click the result: a shift-click crafts the maximum the grid allows, and a drifted grid minted 6 buttons from 6 planks in one click.

## What they learned
1. Slot 0 is a live preview of whatever the server's grid forms, not a stored item; clicking it is a craft.
2. Only take after the server says slot 0 holds exactly the expected item; empty the grid before touching slot 0 when reclaiming.
3. A loaded cursor is lost on close.

## What it means here
`:verify` clicks slot 0 only when `(:window/grid 0)` equals `{:item result :count makes}` and fails `:wrong-result` otherwise ([[craft-intent]]); the recipe tests pin the junk recipes ([[junk-crafts]]). We do shift-click the verified result, which is safe only because [[recipe-clicks]] lays exactly one item per cell, so the maximum is one craft. `:settle` puts a loaded cursor down before anything else.

## Limits
Race identifiers in the quoted comments were not cross-checked against steve's logs.

## See also
- [[sib-ingredient-sourcing]] - how the stray plank gets there.
