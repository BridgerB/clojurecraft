---
title: Build plan
description: How a build is data (issue #8) - ops with done-predicates read against the world, the first op not satisfied is the one worked, and portal-plan, ruststeve's template mold as a pure function of an origin and a facing.
type: reference
tags: [bot, plan, build, portal]
aliases: [build.clj, portal-plan, cast-one-plan, build/holds?, satisfied?, mold, op, :build/done]
status: verified
lastUpdated: 2026-10-14
verifiedAgainst: HEAD
sourceRefs:
  - src/clojurecraft/build.clj#defmulti holds?
  - src/clojurecraft/build.clj#defn satisfied?
  - src/clojurecraft/build.clj#defn portal-plan
  - src/clojurecraft/build.clj#defn cell-ops
  - src/clojurecraft/build.clj#defn dig-op
  - src/clojurecraft/build.clj#defn point
  - src/clojurecraft/build.clj#defmethod intent/run :build
  - src/clojurecraft/build.clj#defn op-child
  - src/clojurecraft/build.clj#defn stance-block
  - src/clojurecraft/build.clj#defn reachable-stand
  - src/clojurecraft/build.clj#defn approach-stand
  - test/clojurecraft/build_test.clj#a-stand-inside-a-hill-is-dug
  - test/clojurecraft/build_test.clj#a-pad-stand-over-water-is-placed-from-the-shore
  - test/clojurecraft/build_test.clj#a-stand-up-a-bank-is-passed-over-for-one-a-route-reaches
  - src/clojurecraft/build.clj#defn station-among
  - src/clojurecraft/build.clj#def footprint
  - src/clojurecraft/build.clj#defn foundation
  - src/clojurecraft/build.clj#defn choose-site
  - src/clojurecraft/memory.clj#defn site
  - test/clojurecraft/sim_test.clj#the-frame-is-cast-lit-and-stepped-through
  - test/clojurecraft/sim_test.clj#a-cast-six-tenths-done-casts-only-the-other-four
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

## The `:build` intent
`{:intent/kind :build :intent/build :cast-one|:portal|:nether :intent/origin o :intent/facing f}`: each tick the plan (`plan-for`, memoized) is read and the first op not satisfied is worked; the build is done when the plan's last op holds (its goal: once in the Nether the earlier ops' cells are not even loaded). `op-child` makes the child that works the op: a lava pour with no lava bucket first refills (`refill`: a walk to the `station`, a lava source outside the footprint within 16 with ground beside the cell over it, then a `:scoop` with `:intent/fluid :lava`); else a solid cell in the body or the head on the way to the stand is dug when it is in reach, or walked to beside, else a walk to the op's stand, ending centred in that cell (`intent/centred?`). A place or a dig whose stand cannot be occupied (air over water) is worked from a standable cell that already reaches the block, the feet when they do, else the one nearest the stand that a route from the feet reaches (`reachable-stand`); with no such cell the build fails `:no-stand` rather than walking onto the water. Race 38477665442 Clj_r25 (issue #45) scooped its water from a sand cell under a bank: the cell nearest the stand was the bank's top, which only cells over the water led to, and three builds failed `:no-path` walking at it while the sand cell reached the pad (`a-stand-up-a-bank-is-passed-over-for-one-a-route-reaches`). Then `:place` into a given `:intent/cell` against a solid neighbour (`place/support`, or a `foundation` built up from below), a child dig, `:pour` (equip, look at the aim, `use-item`, done when `:intent/expect` holds), `:scoop`, `:use-on` (flint and steel on the top face) or `:await` (hold still until the dimension changes). A new op drops the child. In the model a cast from the kit and a lava trench reaches the Nether (`the-frame-is-cast-lit-and-stepped-through`), and one with six cells already obsidian casts only the other four (`a-cast-six-tenths-done-casts-only-the-other-four`).

## The targets
`goals.edn` rows `:cast-one`, `:portal` and `:nether` (`--until` the same) plan with `:goal/plan :build` and are done by `:built?`: the last op of the row's plan (`:goal/build`) holds at the remembered site. The site is a fact in memory (`memory/remember-site`, `memory/site`, the latest wins), written by the `:build` intent when it starts; with none the planner chooses one (`choose-site`: the origin nearest the feet within 4, the first facing whose footprint is loaded and free of lava, with solid ground under the origin and a lava station), else waits `:no-site`. After a death the planner starts the build again at the site and the first op not satisfied is worked; the model's `:kill-at` fault tests it.

## Gotchas
- **The tower stands until the frame does.** A layer's stair and platform are done only once all ten cells are cast: later layers stand on them, so a resumed cast whose lower layers are obsidian still builds them.
- **A plan ends in its goal.** cast-one ends in a `:verify` (the cell cast and the bowl dry); its last cell op, the scoop, holds before anything is poured.
- **The footprint is the plan's, not the mold's box**: the stair to the top layer runs to dx 8, past the box (dx -2..6). `footprint` is the box plus every cell an op works in, so no site has lava under a step and no station's source is under one (a step placed into a source destroys it); `no-op-touches-a-column-a-station-may-be-in` holds it.
- **Lava is not renewable**: a scoop takes a source for good, and a station needs ground beside the cell over its source, so the gym's pit is a one-wide trench.
- **A dig never takes obsidian**: `dig-op`'s done holds when its cell is obsidian (`no-dig-can-take-obsidian`); a cell's bowl can be another cell (the bowl of (0,1) is (0,2)).
- **A stand inside the ground is dug, not walked.** Full run 4e59246 stood at a pond with the pad's stand four blocks east inside the hill; the walk at that stone was :none and three :no-path failures ended the plan. The first solid in the body or the head from the feet to the stand is dug when it is in reach (`a-stand-inside-a-hill-is-dug`). Cobblestone the plan places is left alone, so the clear does not take the mold back out; stone where a wall will go later is dug, because a place counts as done on anything solid.
- **A stand over water is not walked onto.** Full run 61abf8c stood on the bank with the portal kit. The first open pad cell was water and its stand was air over the next water cell; the walk was :partial six times and three :no-path failures ended the plan. The place is made from a cell that already reaches the block (`a-pad-stand-over-water-is-placed-from-the-shore`). A pour still walks to its own stand: the aim is computed for it.
- **Facings turn about the origin cell's middle** (`point`), so the origin cell stays put and a cell and the points inside it turn alike.
- The stair differs from ruststeve's, whose steps rose away from the platform and left a gap the pathfinder bridged.
