---
title: Grass type is grass
description: Symptom: the bot sinks through the ground. Cause: grass_block's definition type is literally grass, which a naive non-solid list includes.
type: reference
tags: [bot, gotcha, blocks, physics]
aliases: [bot falls through grass, passable grass_block, non-solid list]
status: verified
lastUpdated: 2026-10-09
verifiedAgainst: 60b624e
sourceRefs:
  - src/clojurecraft/blocks.clj#def passable-types
  - resources/clojurecraft/blocks.edn#[:grass_block :grass 8 9]
  - resources/clojurecraft/blocks.edn#[:short_grass :tall_grass 2248 2248]
related:
  - "[[bot/gotchas/_moc|Gotchas]]"
  - "[[land-physics]]"
---

# Grass type is grass

Symptom: a bot standing on a meadow falls through the ground, or physics tests pass on stone and fail on grass.

## Root cause
Solidity is decided per block definition type. In the vanilla report, `grass_block` has type `grass` and `short_grass` has type `tall_grass`. A non-solid list copied from ruststeve's datagen contains `grass`, which makes every grass block passable. `passable-types` deliberately omits `grass` and includes `tall_grass`; the docstring says so.

## Fix
Keep `grass` out of `passable-types`. The blocks test asserts `grass_block` (id 8) solid and `short_grass` (2248) passable.

## See also
- [[land-physics]] - where the oracle is used.
