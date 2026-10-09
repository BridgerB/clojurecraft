---
title: Ghost blocks from predicted placement
description: Why a placement must be judged by the server: ruststeve predicted placed blocks locally and got ghost blocks its own physics collided with, never decremented the held stack, and steve saw 15-30% of placements rejected.
type: reference
tags: [siblings, ruststeve, steve, placement, gotcha]
aliases: [ghost block, place_block prediction, use_item_on, placeStationBlock, rejected placement, cave_air table cell]
status: verified
lastUpdated: 2026-10-09
verifiedAgainst: steve c28028b, ruststeve bc575e3
sourceRefs:
  - "ruststeve:src/bot/mod.rs#pub async fn place_block(&mut self, x: i32, y: i32, z: i32, face: Face) -> std::io::Result<()> {"
  - "ruststeve:src/bot/mod.rs#// Never predict a block INTO our own body: the server rejects that placement, but a local"
  - "ruststeve:CHANGES.md#**The SDK never decrements the held stack when it places a block**"
  - "ruststeve:src/bot_utils.rs#fn table_cell_empty(bot: &Bot, x: i32, y: i32, z: i32) -> bool {"
  - "steve:src/lib/steve/lib/bot-utils.ts#export const placeStationBlock = async ("
  - "steve:src/lib/steve/lib/bot-utils.ts#// Standing on a crafting table / furnace: a plain right-click OPENS it, so the"
  - "steve:src/lib/typecraft/bot/placing.ts#if (oldStateId !== newStateId) {"
related:
  - "[[siblings/_moc|Siblings]]"
  - "[[packet-table]]"
  - "[[blocks-tables]]"
---

# Ghost blocks from predicted placement

Symptom (ruststeve gym): three bots stuck at the first stance of every cell, unable to walk along a platform row they had just built; caps reported as ghosts that RCON showed were real cobblestone.

## Key files
- ruststeve `src/bot/mod.rs`, `place_block` - sends `use_item_on` with a sequence, then writes the held block into its local world one step out from the clicked face, on the comment that the server "sends no block_update back to the placer". The guard added after the gym failure: never predict into the bot's own body (the server rejects it, the ghost blocked physics).
- ruststeve `CHANGES.md` - "The SDK never decrements the held stack when it places a block", so "no block spent" misread real placements and callers re-placed caps about once a second.
- ruststeve `src/bot_utils.rs`, `table_cell_empty` - any `*air` name; the old `state == 0` test rejected `cave_air` and looped "could not place a server-confirmed table" 1,944 times in one trial.
- steve `bot-utils.ts`, `placeStationBlock` - "placeBlock is server-rejected ~15-30% of the time, consuming the item client-side without placing anything"; it verifies the block landed and has its own `clipsBot` body test. Standing on a crafting table or furnace, a plain right-click opens it instead of placing.
- typecraft `bot/placing.ts`, `placeBlockWithOptions` - waits up to 5 s for a block update at the destination and counts only a changed state id.

## What they learned
1. A locally predicted block is a belief, and a wrong belief poisons physics and counting.
2. Exclude the bot's own AABB and utility blocks (which interact) from placement sites; treat every air state as empty.
3. The two siblings disagree on whether the placer gets a block update: ruststeve's comment says no, typecraft waits for one, and a ruststeve sniff (`CHANGES.md`, `CAST_SNIFF`) recorded `block_update -> air` for a rejected bed.

## What it means here
No `:place` intent exists yet (issue 02). The rule to keep: the world changes only from server packets, a placement is done when a `block-update` shows the state at the destination, and candidate cells use `blocks/type-of` air rather than state 0 ([[blocks-tables]]); `use-item-on` (id 66) is listed in [[packet-table]] without a spec.

## Limits
Whether vanilla 26.1.2 echoes a successful placement to the placer was not observed in this repo.

## See also
- [[sib-dig-stop-timing]] - the same no-echo claim for digging, which did not reproduce here.
