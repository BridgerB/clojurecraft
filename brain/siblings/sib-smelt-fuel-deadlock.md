---
title: The fuel-at-smelt deadlock
description: Why steve's races capped at about 3 iron ingots (planks burned as furnace fuel starved the wood chain), and the furnace-window rules both siblings learned: coal first, verify every click, reclaim fuel and input, never close with a loaded cursor.
type: reference
tags: [siblings, steve, ruststeve, smelting, furnace, gotcha]
aliases: [3 ingot cap, smelt deadlock, fuel deadlock, furnace window, smeltItems, smelt_iron]
status: verified
lastUpdated: 2026-10-09
verifiedAgainst: steve c28028b, ruststeve bc575e3
sourceRefs:
  - steve:src/lib/steve/tasks/smelt/main.ts#Load fuel if the fuel slot is empty. PREFER coal/charcoal over wood
  - steve:src/lib/steve/tasks/smelt/main.ts#const moveToSlot = async (
  - steve:src/lib/steve/tasks/smelt/main.ts#Cap the in-call wait BELOW the run-loop's ~120s per-step timeout.
  - steve:src/lib/steve/tasks/smelt/main.ts#const fuelBack = await takeSlot(1);
  - steve:src/lib/steve/tasks/smelt/main.ts#Never close while carrying an item — it would be dropped on the ground.
  - ruststeve:src/tasks/smelt.rs#fn win_inv_count(bot: &Bot, name: &str) -> i32 {
related:
  - "[[siblings/_moc|Siblings]]"
  - "[[recipe-graph]]"
  - "[[craft-intent]]"
---

# The fuel-at-smelt deadlock

Symptom in steve races: a bot smelts cleanly to about 3 iron ingots and stalls for the rest of the race. Cause, in steve's own comment: burning planks or logs as furnace fuel "starves the downstream chain (the crafting_table + tools + buckets all need wood), and on a deforested cell the bot then can't re-plank to finish the smelt". Fuel was never a planned need; it was spent from whatever the inventory held.

## Key files
- steve `src/lib/steve/tasks/smelt/main.ts`, `smeltItems` - find or place a furnace, load fuel and input, poll the output, reclaim.
- ruststeve `src/tasks/smelt.rs`, `smelt_iron`, `load_slot`, `win_inv_count` - a port of the same task with raw clicks.

## What they learned
1. **Coal first.** The fuel slot takes coal or charcoal before any wood ("Coal is mined right beside the iron"); wood is the fallback.
2. **Clicks lie under load.** `bot.transfer`/`putInput` are silent no-ops on 26.1.2, and a single click could deposit the wrong item (a diorite in the input slot with no fuel, idle until timeout). Every move is pick-up, put-down, verify the destination, retry up to 3 times; anything stranded in a slot is evicted first.
3. **Fuel runs out mid-batch.** A short plank stack burns about 1.5 items per plank; the wait loop tops fuel up to 4 times or raw iron is left inside.
4. **Wait below the step timeout.** The in-call wait is capped at 90 s, under the run loop's 120 s step kill, or the take-output code never ran and ingots stayed in the furnace.
5. **Reclaim everything.** A click moves the whole fuel stack, so leftover planks are taken back, then any late ingot, then unsmelted input.
6. **Never close with a loaded cursor** - the item drops on the ground.
7. **Count in the open window.** ruststeve's `win_inv_count` counts the open window's inventory section, because while a furnace is open the player inventory model is stale; counting there made the loop finish instead of burning its 220 s deadline.

## What it means here
Fuel is an explicit want in the recipe graph, not a side effect: smelting n items needs ceil(n / 8) coal, and coal is gathered like logs ([[recipe-graph]]). The furnace window uses the same rules as window 0 in [[craft-intent]]: one click per round trip against the state id, verify before taking, and the cursor is never left loaded.

## Limits
The "about 3 ingots" cap is steve's race record, cited in the code comment and the operator notes, not reproduced here. ruststeve's top-up and grace-tick loop was read only through its comments.

## See also
- [[sib-wood-lock]] - the same undercount family at the crafting grid.
- [[craft-intent]] - our click discipline.
