---
title: Build plan
description: How a build is data (issue #8) - ops with done-predicates read against the world, the first op not satisfied is the one worked, portal-plan, ruststeve's template mold as a pure function of an origin and a facing, and the hunt for lava when no site is at hand (issue #41).
type: reference
tags: [bot, plan, build, portal]
aliases: [build.clj, portal-plan, cast-one-plan, build/holds?, satisfied?, mold, op, :build/done, lava-hunt, uncap, no-site]
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
  - src/clojurecraft/build.clj#defn stands-beside
  - test/clojurecraft/build_test.clj#a-station-is-near-in-three-dimensions-and-open-to-the-air
  - src/clojurecraft/build.clj#def footprint
  - src/clojurecraft/build.clj#defn foundation
  - src/clojurecraft/build.clj#defn choose-site
  - src/clojurecraft/build.clj#defn held-first
  - test/clojurecraft/build_test.clj#a-build-row-holds-a-pickaxe-before-it-starts
  - src/clojurecraft/build.clj#defn lava-hunt
  - src/clojurecraft/build.clj#defn lava-walk
  - src/clojurecraft/build.clj#defn uncap
  - src/clojurecraft/build.clj#defn toward-lava
  - src/clojurecraft/build.clj#def worked-cells
  - test/clojurecraft/sim_test.clj#with-no-lava-near-the-bot-goes-down-to-the-sea-and-casts-beside-it
  - src/clojurecraft/build.clj#defn ground-under?
  - src/clojurecraft/build.clj#def pad-cells
  - test/clojurecraft/build_test.clj#a-site-needs-ground-under-its-pad-and-no-water-in-its-footprint
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
3. `cell-ops`: from the stance (x, Y+1, bz+1) the cup's floor and walls, the bowl's north wall and the sides with no same-layer sibling, dig the cup, the bowl and the lid over the bowl (air in the open; in rock the water's ray, shallow from the stance, clipped it and the pour landed on the bot's own head), pour lava aimed low on the cup's north wall, pour water aimed at the bowl's north wall, scoop the bowl. Every op but the scoop is done once the cup is obsidian, so a neighbour's cobblestone dug out of a cup later never sends the bot back.
4. `front-ops` (the platform and anything solid in front of the frame, standing on the rim; the interior scooped and dug), `light-op` (flint and steel on the top of cell (1,0) from two blocks in front), `enter-op` (stand in the portal until the dimension is the Nether).

`cast-one-plan` is the pad, the rim, layer 0's platform and the first cell: the gym's cast-one stage.

## The `:build` intent
`{:intent/kind :build :intent/build :cast-one|:portal|:nether :intent/origin o :intent/facing f}`: each tick the plan (`plan-for`, memoized) is read and the first op not satisfied is worked; the build is done when the plan's last op holds (its goal: once in the Nether the earlier ops' cells are not even loaded). `op-child` makes the child that works the op: a lava pour with no lava bucket first refills (`refill`: a walk to the `station`, a lava source outside the footprint within 16 in all three dimensions, open to the air over it, with ground beside the cell over it (`stands-beside`), then a `:scoop` with `:intent/fluid :lava`); else a solid cell in the body or the head on the way to the stand is dug when it is in reach, or walked to beside, else a walk to the op's stand, ending centred in that cell (`intent/centred?`). A place or a dig whose stand cannot be occupied (air over water) is worked from a standable cell that already reaches the block, the feet when they do, else the one nearest the stand that a route from the feet reaches (`reachable-stand`); with no such cell the build fails `:no-stand` rather than walking onto the water. Race 38477665442 Clj_r25 (issue #45) scooped its water from a sand cell under a bank: the cell nearest the stand was the bank's top, which only cells over the water led to, and three builds failed `:no-path` walking at it while the sand cell reached the pad (`a-stand-up-a-bank-is-passed-over-for-one-a-route-reaches`). Then `:place` into a given `:intent/cell` against a solid neighbour (`place/support`, or a `foundation` built up from below), a child dig, `:pour` (equip, look at the aim, `use-item`, done when `:intent/expect` holds), `:scoop`, `:use-on` (flint and steel on the top face) or `:await` (hold still until the dimension changes). A new op drops the child. In the model a cast from the kit and a lava trench reaches the Nether (`the-frame-is-cast-lit-and-stepped-through`), and one with six cells already obsidian casts only the other four (`a-cast-six-tenths-done-casts-only-the-other-four`).

## The targets
`goals.edn` rows `:cast-one`, `:portal` and `:nether` (`--until` the same) plan with `:goal/plan :build` and are done by `:built?`; each holds a pickaxe (`:goal/holds {:tag/pickaxes 1}`), which the needs planner makes before anything else (`held-first`, `make/decide` over the holds; the gym's portal kit gives one): sim race 38477665389 (issue #70, 150 runs at one landing) dug for its stance with no pickaxe left and failed `:needs-tool` three times (`a-build-row-holds-a-pickaxe-before-it-starts`). The row is done by `:built?`: the last op of the row's plan (`:goal/build`) holds at the remembered site. The site is a fact in memory (`memory/remember-site`, `memory/site`, the latest wins), written by the `:build` intent when it starts; with none the planner chooses one (`choose-site`: the origin nearest the feet within 4, the first facing whose footprint is loaded and free of water and lava, with solid ground under the origin, ground under every pad cell within a foundation's reach (`ground-under?`, `pad-cells`) and a lava station). With none it hunts for lava (`lava-hunt`): a walk to a stand beside an exposed source a route reaches (`lava-walk`, never one within `site-radius`, where the site was just looked for), the roof over a source in reach of the eye dug when a cell to stand in is beside it (`uncap`, never the cell under the feet, never obsidian or bedrock; the lava under flows no higher), stairs down toward the nearest source loaded, `stone/stair-depth` at a time, else a tunnel of level steps toward it as many as the plane distance less one (the step onto the cell over a source is refused for the lava under it, and the roof is then in reach), or with no source loaded stairs to `lava-depth` (-54: below it the game fills its aquifers with lava, the sea ruststeve cast beside at y -50); else it waits `:no-site`. Issue #41 was the wall before the Nether: race 38477665442's 47 real bots and 950 sim runs stood at the surface with the kit and waited `:no-site` out, lava being nowhere on the surface of a 1.18+ world. In the model a bot with the kit over a roofed sea 24 blocks down goes down, tunnels to it, uncaps a source, chooses a site beside it and casts the first cell (`with-no-lava-near-the-bot-goes-down-to-the-sea-and-casts-beside-it`). After a death the planner starts the build again at the site and the first op not satisfied is worked; the model's `:kill-at` fault tests it.

## Gotchas
- **The tower stands until the frame does.** A layer's stair and platform are done only once all ten cells are cast: later layers stand on them, so a resumed cast whose lower layers are obsidian still builds them.
- **A plan ends in its goal.** cast-one ends in a `:verify` (the cell cast and the bowl dry); its last cell op, the scoop, holds before anything is poured.
- **The footprint is the plan's, not the mold's box**: the stair to the top layer runs to dx 8, past the box (dx -2..6). `footprint` is the box plus every cell an op works in, so no site has lava under a step and no station's source is under one (a step placed into a source destroys it); `no-op-touches-a-column-a-station-may-be-in` holds it.
- **Lava is not renewable**: a scoop takes a source for good, and a station needs ground beside the cell over its source, so the gym's pit is a one-wide trench.
- **The rim fills the bot's own tunnel.** Beside the sea the only stand was the tunnel's last cell, a rim cell of the site chosen, and the rim op filled it: `:no-station` three times. A station's stand is never a cell the plan works (`worked-cells`, the ops' cells apart from the box).
- **A station is near in three dimensions and open above.** Race 38477665442 measured the station's distance on the plane alone and took the lava sea ninety blocks under the feet, within sixteen across, for one; and a source under stone passed, whose scoop then failed `:target-gone` three times (issue #91, 54 sim runs). The distance is `physics/distance` and the source must be `scoop/scoopable?` (`a-station-is-near-in-three-dimensions-and-open-to-the-air`).
- **A dig never takes obsidian**: `dig-op`'s done holds when its cell is obsidian (`no-dig-can-take-obsidian`); a cell's bowl can be another cell (the bowl of (0,1) is (0,2)).
- **A stand inside the ground is dug, not walked.** Full run 4e59246 stood at a pond with the pad's stand four blocks east inside the hill; the walk at that stone was :none and three :no-path failures ended the plan. The first solid in the body or the head from the feet to the stand is dug when it is in reach (`a-stand-inside-a-hill-is-dug`). Cobblestone the plan places is left alone, so the clear does not take the mold back out; stone where a wall will go later is dug, because a place counts as done on anything solid.
- **A site has ground under its pad and no water in its footprint.** Sim race 38395033917 and race 38477665442 (issue #74, 211 sim runs and 3 real bots) failed `:no-stand`: a pad cell over a cave or a deep pond, its stand air over the drop, has no cell within a foundation's four blocks to build it from, and no standable cell within reach of the block. `site-ok?` refuses a footprint with any fluid in it and a pad cell with no solid within `foundation-depth` under it (`a-site-needs-ground-under-its-pad-and-no-water-in-its-footprint`).
- **A stand over water is not walked onto.** Full run 61abf8c stood on the bank with the portal kit. The first open pad cell was water and its stand was air over the next water cell; the walk was :partial six times and three :no-path failures ended the plan. The place is made from a cell that already reaches the block (`a-pad-stand-over-water-is-placed-from-the-shore`). A pour still walks to its own stand: the aim is computed for it.
- **Facings turn about the origin cell's middle** (`point`), so the origin cell stays put and a cell and the points inside it turn alike.
- The stair differs from ruststeve's, whose steps rose away from the platform and left a gap the pathfinder bridged.
