---
title: ruststeve's portal mold
description: The template mold that cast portals 7 of 8 in the pool gym and once naturally: layer order, stance, aims, scoop timing, and the rule to shift the origin until no lava is under the footprint.
type: reference
tags: [siblings, ruststeve, portal]
aliases: [portal_mold.rs, cast template, lava sea rule]
status: draft
lastUpdated: 2026-10-09
verifiedAgainst: ruststeve bc575e3
sourceRefs:
  - "ruststeve:src/tasks/portal_mold.rs#const LAYERS: [&[(i32, i32)]; 5]"
related:
  - "[[siblings/_moc|Siblings]]"
  - "[[mc-bucket-use-item]]"
---

# ruststeve's portal mold

Research for issue #8 summarised `src/tasks/portal_mold.rs`: a fixed layer order (`LAYERS`), a stance with feet on the cell's cup wall, lava aimed at the cup's north wall face, water at the bowl's north wall, scoop the tick the cup reads obsidian, and an origin shift until `footprint_lava == 0` (the "cap the lava sea" lesson). steve's `cast.ts` adds: cast the frame bottom-up in a deliberate order and never y-sort it; every bucket use goes through a reliable-use wrapper because of a stuck `usingHeldItem` flag in 26.1.2.

## What it means here
Build plans as data with an idempotent `:build` intent and a site query that requires no lava under the footprint.

## Limits
Draft: anchors were quoted by a research agent; open `portal_mold.rs` to promote.

## See also
- [[mc-bucket-use-item]]
