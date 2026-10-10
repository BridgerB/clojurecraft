---
title: Recipe patterns shrink
description: Symptom: a recipe never matches and a small recipe seems to need a crafting table. Cause: vanilla trims blank padding from shaped patterns; datagen must too.
type: reference
tags: [bot, gotcha, crafting, datagen]
aliases: [padded pattern, spyglass never matches, shaped pattern trim, shrink]
status: verified
lastUpdated: 2026-10-09
verifiedAgainst: 2d7f669
sourceRefs:
  - dev/clojurecraft/datagen.clj#defn shrink
  - test/clojurecraft/table_clicks_test.clj#every-shaped-recipe-crafts-its-result-in-a-table
  - test/clojurecraft/recipe_test.clj#shaped patterns are shrunk like vanilla: padding columns are not part of the shape
related:
  - "[[bot/gotchas/_moc|Gotchas]]"
  - "[[recipe-table]]"
  - "[[recipe-match]]"
---

# Recipe patterns shrink

Symptom: laying a spyglass, mace, creaking heart or waxed chiseled copper by its click plan crafts nothing, and waxed chiseled copper (really 1x2) is treated as needing a crafting table.

## Root cause
Vanilla writes some shaped patterns with blank padding, e.g. the spyglass as `" # "`, `" X "`, `" X "`, and trims blank rows and columns when it loads the recipe, so the real shape is 1 wide. Our datagen copied the padded rows, so `:recipe/width` was 3 and `recipe/match` compared a 1-wide grid against a 3-wide pattern. Seven of the 1,030 crafting recipes were affected.

## Fix
`datagen/shrink` trims blank rows and columns before `recipes.edn` is written. Found by the enumerated test that lays every shaped recipe through the sim's table window, the first test to check all of them rather than a sample.

## See also
- [[recipe-match]] - the matcher that relies on the true shape.
