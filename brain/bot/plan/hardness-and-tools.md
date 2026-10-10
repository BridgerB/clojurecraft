---
title: Hardness and tools
description: Where a block's break time comes from (hardness, harvest tags and tool materials read from the game itself at datagen), the vanilla formula dig/ms reproduces, how a tool is chosen, and the two numbers the issue had wrong.
type: reference
tags: [bot, plan, dig, data, tools]
aliases: [dig/ms, hardness.edn, harvest.edn, materials.edn, best-tool, harvest?, tier-rank, jar-probe]
status: verified
lastUpdated: 2026-10-10
verifiedAgainst: 56441f5
sourceRefs:
  - src/clojurecraft/dig.clj#defn ms
  - src/clojurecraft/dig.clj#defn harvest?
  - src/clojurecraft/dig.clj#defn best-tool
  - src/clojurecraft/blocks.clj#defn hardness
  - src/clojurecraft/blocks.clj#defn tool-of
  - src/clojurecraft/blocks.clj#defn needs-tier
  - src/clojurecraft/blocks.clj#def tier-rank
  - src/clojurecraft/blocks.clj#def tools
  - dev/clojurecraft/jar_probe.clj#defn hardness
  - dev/clojurecraft/jar_probe.clj#defn materials
  - dev/clojurecraft/datagen.clj#defn harvest
  - test/clojurecraft/dig_test.clj#the-worked-table
related:
  - "[[bot/plan/_moc|Plan]]"
  - "[[dig-timeline]]"
  - "[[datagen]]"
  - "[[sib-dig-hardness]]"
---

# Hardness and tools

Three generated tables, and one pure formula over them.

## The tables
- `hardness.edn` `{block hardness}` and `materials.edn` `{tier {:speed :durability :incorrect}}` come from `clojurecraft.jar-probe`: a JVM with the inner server jar and the bundler's libraries boots the game's registries and reads `BlockState.getDestroySpeed` for every block and every `ToolMaterial` constant. Neither is in the `--reports` output or any data file, and the inner jar is not obfuscated, so this is the game's own number at the version `datagen.sh` downloads. Datagen throws when a block of `blocks.edn` has no hardness: a version bump can never default one silently, which is the placeholder-hardness bug ruststeve paid for ([[sib-dig-hardness]]).
- `harvest.edn` `{block {:tool kind :needs tier}}` comes from the inner jar's `mineable/*` and `needs_*_tool` block tags, nested tags resolved.

## The formula (`dig/ms`)
`damage per tick = speed / hardness / (30 when the held item harvests the block, else 100)`; `ticks = ceil(1 / damage)`, 0 when damage is 1 or more; `nil` for an unbreakable block. `speed` is the tier's speed when the held tool's kind is the block's `:tool`, else 1.0, divided by 5 for each penalty (`:eyes-in-water?`, `:off-ground?`). `harvest?` is by tier alone: a block with no `:needs` drops for anything; one with `:needs` drops when the tool's `tier-rank` reaches it. Copper ranks with stone because the game's `incorrect_for_copper_tool` tag equals stone's; its speed is 5.0, read from `ToolMaterial`.

## Choosing a tool (`dig/best-tool`)
The fastest held tool of the block's own kind that harvests it, as `[slot item]`; nil when no held tool is of that kind (the hand is as good), or when the block needs a tier no held tool of its kind reaches. A tool of another kind is never chosen: a diamond axe would, by the game's rule, drop a netherite block after 75 s, and the dig refuses rather than spend that.

## Gotchas
- **Stone needs no tier.** The issue said stone by hand takes 7500 ms and drops nothing; the tags say otherwise, and the formula gives 2300 ms with cobblestone dropped. The pickaxe is only faster (1150 ms wooden, 600 stone).
- **A penalty divides the speed before the ceiling.** Stone with a wooden pickaxe off the ground is 5650 ms (speed 0.4, ceil(112.5) = 113 ticks), not 5750 (5 × 1150).
- Leaves' hardness is `0.20000000298023224`: the game's float widened. Kept as the game has it; the ceiling makes it 6 ticks either way.
- `materials.edn` names tiers as item names spell them (`:wooden`, `:golden`), not as `ToolMaterial` does (`WOOD`, `GOLD`).

## See also
- [[dig-timeline]] - the consumer.
- [[datagen]] - how to regenerate.
