---
title: Dig time and hardness in the siblings
description: The vanilla break formula both siblings use (speed / hardness / 30 or 100, eyes-in-water and off-ground /5, tier speeds), and how ruststeve's placeholder hardness made deepslate digs fail half the time.
type: reference
tags: [siblings, dig, hardness, tools, ruststeve, typecraft]
aliases: [dig_time, break time formula, placeholder hardness, tool tiers, canHarvest, block_hardness]
status: verified
lastUpdated: 2026-10-09
verifiedAgainst: steve c28028b, ruststeve bc575e3
sourceRefs:
  - "ruststeve:src/bot/mod.rs#pub fn dig_time(&self, x: i32, y: i32, z: i32) -> Duration {"
  - "ruststeve:src/bot/mod.rs#let can_harvest = !needs_tool || tool > 1.0; // the TOOL decides harvestability, not the penalized speed"
  - "ruststeve:src/bot/mod.rs#pub fn block_hardness(name: &str) -> Option<f64> {"
  - "ruststeve:CHANGES.md#**`bot/mod.rs` `dig_time`: REAL BLOCK HARDNESS (SDK-wide).**"
  - "steve:src/lib/typecraft/bot/digging.ts#const TOOL_TIERS: Record<string, number> = {"
  - "steve:src/lib/typecraft/bot/digging.ts#const damage = speed / hardness / (canHarvest ? 30 : 100);"
  - "steve:src/lib/typecraft/bot/digging.ts#// the EYES are in water (isEyeInFluid), not when any part of the hitbox is:"
  - src/clojurecraft/intent.clj#def dig-ms
related:
  - "[[siblings/_moc|Siblings]]"
  - "[[sib-dig-stop-timing]]"
  - "[[mc-dig-and-pickup]]"
  - "[[sib-held-slot-drift]]"
---

# Dig time and hardness in the siblings

## The formula (identical in both)
`damage = speed / hardness / (canHarvest ? 30 : 100)`; `damage >= 1` breaks instantly; otherwise `ceil(1 / damage)` ticks of 50 ms. `speed` is the tool's tier speed (typecraft `TOOL_TIERS`: wooden 2, stone 4, iron 6, golden 12, diamond 8, netherite 9; 1 by hand), times 0.2 when the eyes are in water without Aqua Affinity, times 0.2 when not on the ground.

## Key files
- typecraft `bot/digging.ts` - the formula and the penalties. The water penalty keys on the eyes: "Using isInWater here made the client wait 25× for a dig the server completes at 5×".
- ruststeve `src/bot/mod.rs`, `dig_time` - the same formula; "the TOOL decides harvestability, not the penalized speed". Harvestability comes from name heuristics (`is_pickaxe_block`, metal `_block` names, anvils, furnaces).
- ruststeve `block_hardness` - a hand table of vanilla hardness by name, because the generated registry had none.
- ruststeve `CHANGES.md`, "REAL BLOCK HARDNESS" - its datagen wrote 1.0 for every solid block (the `--reports` output has no destroy time). Deepslate (real 3.0) got a 0.25 s estimate; the 1.35×+200 ms hold (0.54 s) landed on the server's 70% threshold (0.525 s) and natural deepslate digs were rejected about half the time: locally predicted air, then the server's `block_update` put it back.

## What they learned
1. The break time must come from real hardness; the `--reports` output does not carry it.
2. A wrong break time is silent: the client predicts air, the server restores the block.
3. Penalties are part of the formula, and the water one is about the eyes.

## What it means here
Done (issue #9): `dig/ms` is a pure function over hardness and tool materials read from the game's own classes at datagen and the jar's `mineable/*` and `needs_*_tool` tags, no name heuristics; datagen throws rather than default a hardness. The late FINISH rule stays ([[sib-dig-stop-timing]]). See [[hardness-and-tools]].

## Limits
typecraft's hardness source (its Java datagen mod) and the tag files in the server jar were not opened here; see docs/issues/05 for those claims.

## See also
- [[sib-held-slot-drift]] - the other cause of a too-short dig.
