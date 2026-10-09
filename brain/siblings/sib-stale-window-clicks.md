---
title: Stale-window clicks
description: Why a 2x2 craft must close any open container first: ruststeve's clicks landed in a table window the server had closed (0/6 vs 6/6 with the fix), and bot.craft reported a false success.
type: reference
tags: [siblings, ruststeve, steve, crafting, windows]
aliases: [stale window, active_window, close before 2x2, 3968bcf, STALE_WINDOW_FIX]
status: verified
lastUpdated: 2026-10-09
verifiedAgainst: steve c28028b, ruststeve bc575e3
sourceRefs:
  - "ruststeve:CHANGES.md#Stale-window fix (3968bcf): a 2×2 craft clicked into `active_window()`, i.e. a table window the server had closed (walk-away or death), so the clicks were ignored and the grid stayed empty."
  - "ruststeve:CHANGES.md#The fix is necessary but not sufficient."
  - "steve:src/lib/typecraft/bot/crafting.ts#// A 2×2 craft clicks the player's own grid (window 0). The server ignores"
  - "steve:src/lib/steve/lib/bot-utils.ts#// Close stale windows to avoid inventory desync"
  - "src/clojurecraft/craft.clj#defmethod stage :settle"
related:
  - "[[siblings/_moc|Siblings]]"
  - "[[craft-intent]]"
  - "[[window-zero-model]]"
  - "[[sib-empty-prediction-clicks]]"
---

# Stale-window clicks

Symptom (ruststeve, after dying with a crafting table open): `CRAFT 947: result not seen in slot 0` repeated, then `CRAFT stick: result=Ok(())` with 8 planks and no sticks. The craft reported success and nothing happened.

## Key files
- ruststeve `CHANGES.md`, "Stale-window fix (3968bcf)" - the 2×2 craft clicked into `active_window()`, a table window the server had already closed on walk-away or death; the server ignored every click. The fix closes it first and drops it on respawn.
- ruststeve `CHANGES.md`, the rerun - `STALE_WINDOW_FIX=0` 0/6, fix on 6/6, on the slug that kills the bot with a table open. "The fix is necessary but not sufficient": fixed trials still logged a one-plank grid whose output was a button ([[sib-craft-result-take]]).
- typecraft `crafting.ts` - before a 2×2 craft, closes `bot.currentWindow` if it is not the inventory: "The server ignores clicks for any window but the one it has open ... its 3×3 slot numbers would also map to other cells."
- steve `bot-utils.ts`, `craftItem` - closes `bot.currentWindow` and sleeps 300 ms before any craft.

## What they learned
1. The server applies a click only to the container it has open; anything else is dropped with no reply, so a client cannot tell "ignored" from "slow" except by a deadline.
2. A client-side window object can outlive the server's (death, walking away); the client must forget it on respawn.
3. A 3×3 slot number sent to window 0 means a different cell.

## What it means here
The `:craft` intent's `:settle` stage emits `container-close` for any `:window/open` and dissocs it before laying anything ([[craft-intent]]); every click waits for the state id to move and fails `:stale-window` after `answer-timeout`, so an ignored click is a failure, never a false success. `:window/open` is not yet cleared on respawn because `respawn` is not modelled ([[window-zero-model]]).

## Limits
Verified from the CHANGES narrative and the two sibling sources; the 3968bcf diff itself was not opened.

## See also
- [[sib-empty-prediction-clicks]] - how ruststeve makes every click answered.
