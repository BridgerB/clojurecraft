---
title: The dimension change both siblings broke on
description: What happens when the bot walks through a portal in 775 (a respawn packet whose dimension is a registry index), and the three things both siblings had to fix: dimension name, chunk height per dimension, and stale overworld columns answering Nether lookups.
type: reference
tags: [siblings, steve, ruststeve, nether, dimensions, chunks]
aliases: [respawn packet, entering the nether, nether chunk height, dimension_type registry, exactly 64 higher]
status: verified
lastUpdated: 2026-10-09
verifiedAgainst: steve c28028b, ruststeve bc575e3
sourceRefs:
  - ruststeve:src/bot/mod.rs#A DIMENSION CHANGE invalidates every loaded column: chunk keys are just (cx,cz)
  - ruststeve:src/bot/mod.rs#Update world height for the NEW dimension so chunk parsing reads the right
  - steve:src/lib/typecraft/bot/game.ts#26.x sends the dimension as a REGISTRY INDEX (e.g. 3 = the_nether), not
  - steve:src/lib/typecraft/bot/game.ts#const applyDimensionHeight = (dimensionId: number | string) => {
  - steve:src/lib/steve/steps.ts#canExecute: (s) => s.world.dimension === "nether",
  - ruststeve:src/tasks/nether.rs#pub(crate) fn loaded_block_nearest(bot: &Bot, names: &[&str]) -> Option<(i32, i32, i32)> {
related:
  - "[[siblings/_moc|Siblings]]"
  - "[[mc-dimensions]]"
  - "[[chunk-sections]]"
---

# The dimension change both siblings broke on

Both siblings reached the Nether naturally only recently, and both found their world model was built for one dimension. In 775 the server announces the change with `respawn`, carrying the new world's spawn info.

## Key files
- ruststeve `src/bot/mod.rs`, the `"respawn"` arm - clears columns, entities and lava memory on a change, sets min y and height.
- steve `src/lib/typecraft/bot/game.ts` - `registry_data` for `minecraft:dimension_type`, `applyDimensionHeight`, `handleRespawnData`.
- steve `src/lib/steve/steps.ts` - steps gated on `s.world.dimension`.
- ruststeve `src/tasks/nether.rs`, `loaded_block_nearest` - the fortress scan.

## What they learned
1. **The dimension is an index.** 26.x sends the dimension as a registry index (e.g. 3 = the_nether), not a name. typecraft keeps the `dimension_type` registry entries from configuration (key, `height`, `min_y`) and maps the index back; without it the Nether read as "3" and the enter-nether goal never completed.
2. **Chunk height per dimension.** The overworld is 384 tall from -64 (24 sections); the Nether and End are 256 from 0 (16 sections). ruststeve kept the 24-section count and the parser ran off the end of the shorter Nether buffer and panicked; it now sets `(min_y, height)` on respawn.
3. **Stale columns lie.** Chunk keys are only `(cx, cz)`, so overworld columns kept answering Nether lookups until resent: a fortress brick was reported exactly 64 blocks too low. On a change ruststeve clears every column, every entity and the lava sightings, and syncs any open window back to the inventory (the server closes containers on respawn).
4. **Gate steps by dimension.** steve's Nether steps run only when `s.world.dimension === "nether"`; its wood step only in the overworld.
5. **Finding the fortress is easy, reaching it is not.** ruststeve's `loaded_block_nearest` scans loaded palettes for exposed blocks in y 32..127 only (above the lava sea); a brick 15 blocks under the sea surface had been picked over a reachable fortress.

## What it means here
`chunk/section-count` and `chunk/min-y` are constants today ([[chunk-sections]]); they become per-dimension values read from the dimension at decode time, `:world/chunks` is keyed by dimension (or cleared on change), and sightings carry the dimension. The heights are in [[mc-dimensions]].

## Limits
typecraft's `respawn` field order and steve's own Nether tasks (`tasks/nether/main.ts`) were not read; the fortress approach (tunnels, lava falls) is only in ruststeve's design notes, summarised in docs/issues/09.

## See also
- [[mc-dimensions]] - the heights from the jar.
- [[sib-blaze-combat]] - the next step in the Nether.
