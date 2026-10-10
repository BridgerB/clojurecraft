---
title: Junk crafts
description: Symptom - the bot takes a pressure plate or a button instead of the intended item. Cause - leftover planks in the grid form a different valid recipe (two side by side = pressure plate, one alone = button).
type: reference
tags: [bot, gotcha, crafting]
aliases: [pressure plate, oak button, dirty grid, wrong result, blind take]
status: verified
lastUpdated: 2026-10-09
verifiedAgainst: 5c7d6c1
sourceRefs:
  - test/clojurecraft/recipe_test.clj#the junk a dirty grid mints
  - resources/clojurecraft/recipes.edn#{:id :oak_pressure_plate, :result :oak_pressure_plate, :count 1, :kind :shaped, :pattern ["##"]
  - src/clojurecraft/craft.clj#defmethod stage :verify
related:
  - "[[bot/crafting/_moc|Crafting]]"
  - "[[bot/gotchas/_moc|Gotchas]]"
  - "[[recipe-match]]"
  - "[[craft-intent]]"
---

# Junk crafts

Symptom: after a craft that left material behind (a lost click, an aborted lay-out), the next take produces `oak_pressure_plate` or `oak_button`, burning planks the kit needed.

## Root cause
Vanilla crafts whatever the grid currently matches. Two planks in one row are the shaped `pressure_plate` recipe (`["##"]`); a single plank anywhere is the shapeless `button` recipe. A grid that is "sticks minus one plank" is not empty, it is a button. The recipe test `match-is-the-servers-rule` pins both.

## Fix
- `:settle` shift-clicks every item out of the grid (slots 1..size²) before laying a craft, so a craft always starts from an empty grid.
- `:verify` clicks slot 0 only when it shows exactly `{:item result :count makes}`; anything else fails `:wrong-result` and the retry's `:settle` reclaims the grid.
- The sim records `[:click-on-empty-result mode]` so a test would catch a blind take of nothing.

## See also
- [[sib-wood-lock]] - steve's grid-stranding bug, the same family.
