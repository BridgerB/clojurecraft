---
title: Manual clicks, not place-recipe
description: Why the bot lays every craft with container clicks it computes itself instead of the recipe-book place-recipe packet, and what would make place-recipe the better choice.
type: decision
tags: [bot, decision, crafting, protocol]
aliases: [why not place-recipe, recipe book, ghost recipe, place-recipe 39]
status: verified
lastUpdated: 2026-10-09
verifiedAgainst: 5c7d6c1
sourceRefs:
  - src/clojurecraft/recipe.clj#defn clicks
  - src/clojurecraft/packet.clj#[:play :c2s :container-click]
  - "docs/issues/01-crafting-as-data.md#Proposed: ship manual clicks now, keep :recipe/id stable, and accrete :intent/via :recipe-book once recipe-book-add is decoded, with the sim modelling both."
  - resources/clojurecraft/packets.edn#:place-recipe 39
related:
  - "[[bot/decisions/_moc|Decisions]]"
  - "[[recipe-clicks]]"
  - "[[craft-intent]]"
---

# Manual clicks, not place-recipe

## The choice
A craft is a vector of `container-click`s computed by `recipe/clicks` from the inventory value, sent one per round trip. There is no `place-recipe` spec in `packet/specs`; its id (39) is only in the generated `packets.edn`.

## What was rejected
`place-recipe` (the recipe-book button: the server fills the grid from the inventory and the client takes with one click). It needs the per-session recipe display ids that only arrive in `recipe-book-add`, whose `RecipeDisplay`/`SlotDisplay` payload is a real decoder that neither sibling ever wrote (both read it as an opaque rest buffer, per issue 01's research); and with missing ingredients vanilla places a ghost recipe instead of failing. The constraint is a decoder we do not have, not a preference. Clicks need only the 775 `container-click` layout, which the packet test pins byte for byte.

## What would change the answer
`recipe-book-add` decoded into `:recipe/id`-keyed display ids, plus a recipe that fails under manual clicks on a loaded server (lag making the multi-click lay-out the bottleneck). The issue proposes accreting `:intent/via :recipe-book` beside the click path, with the sim modelling both.

## See also
- [[recipe-clicks]] - what is sent instead.
- [[predict-nothing]] - the other half of the click contract.
