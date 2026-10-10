---
title: steve's wood lock
description: Why steve's Craft Planks and Gather Wood oscillated forever (grid items invisible to inventory counts, plus preemption on that undercount), and why our window model cannot repeat it.
type: reference
tags: [siblings, steve, crafting, gotcha]
aliases: [wood-lock, craft-grid undercount, stranded in the grid, reclaimCraftingGrid]
status: verified
lastUpdated: 2026-10-09
verifiedAgainst: steve c28028b
sourceRefs:
  - "steve:src/lib/steve/lib/bot-utils.ts#export const reclaimCraftingGrid = async (bot: Bot): Promise<void> => {"
  - "steve:src/lib/steve/lib/bot-utils.ts#left in the grid is INVISIBLE to windowItems() — which only reads the"
  - "steve:src/lib/steve/lib/run-loop.ts#forever (the wood-lock). Regressions are handled when the running step"
  - src/clojurecraft/inventory.clj#defn set-window-0-slot
related:
  - "[[siblings/_moc|Siblings]]"
  - "[[window-zero-model]]"
  - "[[craft-intent]]"
  - "[[sib-craft-result-take]]"
---

# steve's wood lock

Symptom in steve: `Craft Planks` and `Gather Wood` alternate for the rest of the race and the bot never leaves the wood stage.

## Key files
- steve `bot-utils.ts`, `reclaimCraftingGrid` - the fix's helper; its docstring states the cause: "A crafted result left in the grid is INVISIBLE to windowItems() — which only reads the inventory section — so the bot 'loses' furnaces/tables it actually holds and hot-spins".
- steve `run-loop.ts`, the preempt block - the second half: moving a log into the 2×2 grid drops the inventory log count, flipping `gather_wood.isComplete()` false; when that was allowed to preempt it cancelled the craft mid-place and re-gathered forever. The fix lets only `escape_water` preempt.

## What they learned
1. An inventory read that skips window-0 slots 0-4 undercounts whatever sits in the grid (ingredients mid-craft, or a result never taken).
2. A planner that re-derives "done" from that undercount, and preempts on it, turns the undercount into an infinite loop.
3. `reclaimCraftingGrid` resyncs from the server, then shift-clicks grid slots high to low and never the result slot (see [[sib-craft-result-take]] for why).

## What it means here
`set-window-0-slot` in `inventory.clj` keeps window-0 slots 0-4 verbatim in `:window/grid` (0 is the result) and the rest in `:player/inventory`, and `:window/state-id` is kept on every `container-set-content`/`container-set-slot`. Nothing in the grid is invisible: a goal can see "a table is in the grid" rather than "no table" ([[window-zero-model]]). `inventory/item-count` deliberately reads the inventory only, and the `:craft` intent's `:settle` stage shift-clicks any dirty grid cell back before planning ([[craft-intent]]). Our planner also never preempts an active intent.

## Limits
steve's LOOP.md race narrative (how many runs it cost) was not re-read; this note covers the mechanism only.

## See also
- [[sib-stale-window-clicks]] - the other way a sibling craft silently did nothing.
