---
title: steve's wood lock
description: steve's Craft Planks and Gather Wood oscillated forever because crafting results stranded in the 2x2 grid were invisible to inventory counts; we inherited the same hole in container->player-slot.
type: reference
tags: [siblings, steve, crafting, gotcha]
aliases: [wood-lock, craft-grid undercount, stranded in the grid]
status: draft
lastUpdated: 2026-10-09
verifiedAgainst: steve d98387d
sourceRefs:
  - src/clojurecraft/game.clj#defn container->player-slot
related:
  - "[[siblings/_moc|Siblings]]"
  - "[[goals-and-intents]]"
---

# steve's wood lock

Symptom in steve: `Craft Planks` and `Gather Wood` alternate every tick for the rest of the race; the furthest runs stalled at 3 ingots because every craft re-counted wood.

## What the research found
Items left in window-0 crafting slots 1-4 were not counted by `windowItems`, so a step that had just crafted saw fewer planks than it had; steps re-derived from inventory flickered. Research for issue #2 cites `src/lib/steve/lib/bot-utils.ts` (craftItem) and steve's LOOP.md "craft/smelt undercount + wood-lock" board entry, and ruststeve's CHANGES.md for stale-window clicks (0/6 crafts until fixed).

## What it means here
`container->player-slot` maps window-0 slots 36-44, 9-35, 5-8 and 45 and drops 0-4; `:state-id` is decoded and discarded. Issue #2 keeps grid slots in the inventory model and confirms every click against the state id.

## Limits
Draft: the sibling anchors were read by the research agent, not re-opened here; the clojurecraft anchor is verified.

## See also
- [[goals-and-intents]]
