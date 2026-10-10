---
title: Held-slot drift
description: Why the bot must re-assert and confirm the held item before digging: the server's selected slot drifted from the siblings' client view (digging "with a pickaxe" while holding andesite), so breaks were rejected.
type: reference
tags: [siblings, dig, tools, inventory, typecraft, ruststeve, steve]
aliases: [set_carried_item drift, quickBarSlot, select_item, ensurePickaxe, phantom air block]
status: verified
lastUpdated: 2026-10-09
verifiedAgainst: steve c28028b, ruststeve bc575e3
sourceRefs:
  - "steve:src/lib/typecraft/bot/digging.ts#// Re-assert the held slot right before the dig. The server's SelectedItemSlot"
  - "steve:src/lib/steve/tasks/mining/main.ts#export const ensurePickaxe = async (bot: Bot): Promise<boolean> => {"
  - "ruststeve:src/bot_utils.rs#pub async fn select_item(bot: &mut Bot<'_>, name: &str) -> std::io::Result<bool> {"
  - "src/clojurecraft/intent.clj#(game/emit {:packet/name :set-carried-item :slot slot})"
related:
  - "[[siblings/_moc|Siblings]]"
  - "[[sib-dig-hardness]]"
  - "[[dig-timeline]]"
---

# Held-slot drift

Symptom (typecraft): a bot digging "with a stone pickaxe" while the server had andesite selected; every client-side dig time was too short, the server rejected the break, and the client kept a phantom air block under its feet.

## Key files
- typecraft `bot/digging.ts` - sends `set_carried_item` with the client's `quickBarSlot` immediately before every START: "One packet makes them agree."
- steve `tasks/mining/main.ts`, `ensurePickaxe` - equips; if the held item still is not a pickaxe, `resyncInventory` and retry once (a race where the client showed dirt and the server had the pick).
- ruststeve `src/bot_utils.rs`, `select_item` - up to 5 rounds: set the held slot (or hotbar-swap from the main inventory with a mode-2 click, then select slot 0), wait 8 ticks for the server's inventory, and re-check the server-confirmed held item.

## What they learned
1. The selected slot is server state; the client's copy drifts.
2. Equipping is done when the server's inventory confirms it, not when the packet is sent.
3. A held tool that vanished from the inventory means it broke; re-select or re-craft.

## What it means here
Our `:dig` chooses the hotbar slot of the best tool for the block and sends `set-carried-item` for it before every START ([[dig-timeline]]), the typecraft re-assert; a tool gone from that slot mid-dig fails `:tool-broke` and the next dig chooses again (issue #9).

## Limits
`set-held-slot` (server to client) is in our spec table but no handler records it yet, so a server-driven slot change is invisible to us.

## See also
- [[sib-dig-hardness]]
