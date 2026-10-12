---
title: Build plan
description: How a build is data (issue #8) - ops with done-predicates read against the world, the first op not satisfied is the one worked, and portal-plan, ruststeve's template mold as a pure function of an origin and a facing.
type: reference
tags: [bot, plan, build, portal]
aliases: [build.clj, portal-plan, cast-one-plan, build/holds?, satisfied?, mold, op, :build/done]
status: verified
lastUpdated: 2026-10-12
verifiedAgainst: HEAD
sourceRefs:
  - src/clojurecraft/build.clj#defmulti holds?
  - src/clojurecraft/build.clj#defn satisfied?
  - src/clojurecraft/build.clj#defn portal-plan
  - src/clojurecraft/build.clj#defn cell-ops
  - src/clojurecraft/build.clj#defn dig-op
  - src/clojurecraft/build.clj#defn point
  - test/clojurecraft/build_test.clj#the-plan-casts-the-ten-cells-in-layer-order
  - test/clojurecraft/build_test.clj#the-plan-never-places-into-its-own-stance-nor-lids-a-cup
  - test/clojurecraft/build_test.clj#no-dig-can-take-obsidian
related:
  - "[[bot/plan/_moc|Plan]]"
  - "[[sib-portal-mold]]"
  - "[[server-model]]"
---

# Build plan

A plan is a vector of ops, flat maps: what (`:build/op` `:place :dig :pour :scoop :use-on :stand-in`), where (`:build/pos`, and `:build/stand` where the bot stands), with what (`:build/item`, `:build/aim` for a pour), and when it is done (`:build/done`, a predicate as data read by the open multimethod `holds?`: `[:solid p] [:not-solid p] [:filled p] [:block p name] [:lava-source p] [:no-water p] [:dimension name] [:or ...] [:and ...]`). Nothing about progress is stored: the op to work is the first one not satisfied, so a build resumes after a death, a chunk reload or a relaunch by reading the world.

## The mold (`portal-plan origin facing`)
ruststeve's template mold ([[sib-portal-mold]]) as data, pure (`the-plan-never-places-into-its-own-stance-nor-lids-a-cup` checks purity over 200 origins and facings). The frame is ten obsidian in the plane of the origin's z, cast in `layers` order (never sorted by height): `(1,0)(2,0) / (0,1)(3,1) / (0,2)(3,2) / (0,3)(3,3) / (1,4)(2,4)`.
1. `pad-ops`: a floor under dx -1..5, dz -1..2; `rim-ops`: a ring at pad level round the footprint, only where it is air.
2. Per layer: `stair-ops` (steps east of the platform, the top one beside its back row, each a block lower going east), `platform-ops` (the front row standing on the level below, then the back row standing on the front row: never a block into the bot's own cell), then `cell-ops` for each cell.
3. `cell-ops`: from the stance (x, Y+1, bz+1) the cup's floor and walls, the bowl's north wall and the sides with no same-layer sibling, dig the cup and the bowl, pour lava aimed low on the cup's north wall, pour water aimed at the bowl's north wall, scoop the bowl. Every op but the scoop is done once the cup is obsidian, so a neighbour's cobblestone dug out of a cup later never sends the bot back.
4. `front-ops` (the platform and anything solid in front of the frame, standing on the rim; the interior scooped and dug), `light-op` (flint and steel on the top of cell (1,0) from two blocks in front), `enter-op` (stand in the portal until the dimension is the Nether).

`cast-one-plan` is the pad, the rim, layer 0's platform and the first cell: the gym's cast-one stage.

## Gotchas
- **A dig never takes obsidian**: `dig-op`'s done holds when its cell is obsidian (`no-dig-can-take-obsidian`); a cell's bowl can be another cell (the bowl of (0,1) is (0,2)).
- **Facings turn about the origin cell's middle** (`point`), so the origin cell stays put and a cell and the points inside it turn alike.
- The stair differs from ruststeve's, whose steps rose away from the platform and left a gap the pathfinder bridged.
