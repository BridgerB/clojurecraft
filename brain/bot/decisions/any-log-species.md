---
title: Gather any log species
description: Why the crafting graph asks for "any log" and lets the next plan pick the matching planks recipe, instead of choosing a species before gathering.
type: decision
tags: [bot, decision, crafting, wood]
aliases: [any species, raw logs, gather :logs, birch planks]
status: verified
lastUpdated: 2026-10-09
verifiedAgainst: 5c7d6c1
sourceRefs:
  - src/clojurecraft/recipe.clj#any species: the next plan picks its recipe
  - src/clojurecraft/blocks.clj#def log-items
  - test/clojurecraft/recipe_test.clj#the species it holds
related:
  - "[[bot/decisions/_moc|Decisions]]"
  - "[[recipe-graph]]"
  - "[[memory-sightings]]"
---

# Gather any log species

## The choice
When the graph bottoms out in logs it returns `{:action :gather :want :logs}` with no species. The wood chain digs the nearest remembered trunk of any `_log` block. On the next tick the graph sees, say, a birch log and picks `:birch_planks` because candidate recipes are sorted by how many of their ingredients are already held.

## What was rejected
Choosing a species first (e.g. oak, because `:oak_planks` sorts first): the nearest tree is often another species, so the bot would walk past usable trees. Every 2x2 wood recipe the kit needs (table, sticks) takes the `#planks` tag, so the species never matters downstream.

## What would change the answer
A recipe that needs one species specifically (boats, signs of a species, hanging signs), or a dimension where the only "logs" are stems (`crimson_stem` is in the `#logs` tag but `blocks/log-items` only matches names ending in `_log`).

## See also
- [[recipe-graph]] - candidate ordering.
