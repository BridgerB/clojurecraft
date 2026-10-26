---
title: Build plan
description: How a build is data (issue #8) - ops with done-predicates read against the world, the first op not satisfied is worked; portal-plan, the portal cut into the rock beside any lava pool as a player casts it (issue #124); the site, the station, the blocks the cast asks for, and the hunt for lava when no site is at hand (issue #41).
type: reference
tags: [bot, plan, build, portal]
aliases: [build.clj, portal-plan, cast-one-plan, build/holds?, satisfied?, op, :build/done, choose-site, station, refill, blocks-needed, lava-hunt, uncap, no-site]
status: verified
lastUpdated: 2026-10-23
verifiedAgainst: HEAD
sourceRefs:
  - test/clojurecraft/build_test.clj#the-lava-hunt-passes-sources-it-is-already-beside
  - src/clojurecraft/build.clj#defn lit-entry
  - test/clojurecraft/build_test.clj#a-lit-portal-is-entered-without-rebuilding-or-fetching-tools
  - src/clojurecraft/build.clj#defmulti holds?
  - src/clojurecraft/build.clj#defn satisfied?
  - src/clojurecraft/build.clj#defmethod holds? :no-water-source
  - test/clojurecraft/build_test.clj#a-cast-scoops-sources-not-flow-from-later-cells
  - src/clojurecraft/build.clj#defn portal-plan
  - src/clojurecraft/build.clj#defn cell-ops
  - src/clojurecraft/build.clj#defn firm-ops
  - src/clojurecraft/build.clj#def stands
  - src/clojurecraft/build.clj#def stair
  - src/clojurecraft/build.clj#defn access
  - src/clojurecraft/build.clj#defn room-cells
  - src/clojurecraft/build.clj#defn room-ops
  - src/clojurecraft/build.clj#defn dig-op
  - src/clojurecraft/build.clj#defn point
  - src/clojurecraft/build.clj#defmethod intent/run :build
  - src/clojurecraft/build.clj#defn op-child
  - src/clojurecraft/build.clj#defn stray-lava?
  - src/clojurecraft/build.clj#defn stance-block
  - src/clojurecraft/build.clj#defn reachable-stand
  - src/clojurecraft/build.clj#defn approach-stand
  - test/clojurecraft/build_test.clj#a-reachable-dig-needs-no-new-stance
  - test/clojurecraft/build_test.clj#a-nearby-room-at-another-height-is-gone-back-to
  - test/clojurecraft/build_test.clj#a-portal-behind-its-back-wall-is-entered-from-the-room
  - src/clojurecraft/build.clj#defn way-back
  - src/clojurecraft/build.clj#defn wading-out?
  - src/clojurecraft/build.clj#defn swum
  - src/clojurecraft/build.clj#defn swim-looped?
  - test/clojurecraft/build_test.clj#a-swim-out-that-ends-where-it-began-again-and-again-gives-the-site-up
  - src/clojurecraft/build.clj#defn way-columns
  - test/clojurecraft/build_test.clj#the-lava-hunt-heads-for-lava-beyond-the-hunt-radius
  - test/clojurecraft/build_test.clj#a-tunnel-for-lava-is-not-cut-again-from-the-cell-one-boxed
  - test/clojurecraft/build_test.clj#a-boxed-tunnel-for-lava-gives-its-source-up
  - test/clojurecraft/build_test.clj#a-walk-whose-way-is-gravel-out-of-reach-is-no-refill
  - test/clojurecraft/build_test.clj#a-build-in-water-with-no-route-swims-out-first
  - test/clojurecraft/build_test.clj#a-way-a-wall-bars-is-not-the-way-to-dig
  - test/clojurecraft/build_test.clj#a-cast-one-walk-with-no-path-gives-its-site-up
  - test/clojurecraft/build_test.clj#a-cast-one-with-no-stand-gives-its-site-up
  - test/clojurecraft/build_test.clj#rock-between-the-feet-and-the-landing-is-a-way-to-cut
  - test/clojurecraft/build_test.clj#a-site-left-for-a-pickaxe-is-gone-back-to
  - src/clojurecraft/build.clj#defn far-way
  - test/clojurecraft/build_test.clj#a-site-out-of-sight-is-walked-toward-before-anything-is-dug
  - test/clojurecraft/build_test.clj#the-roof-over-lava-is-broken-though-its-ore-drops-nothing
  - src/clojurecraft/build.clj#defn place-cells
  - src/clojurecraft/build.clj#defn wood-near?
  - test/clojurecraft/build_test.clj#a-build-fetches-its-kit-wood-only-from-the-surface
  - test/clojurecraft/build_test.clj#a-stand-within-reach-is-not-walked-to
  - test/clojurecraft/build_test.clj#the-cells-a-build-places-in-are-those-of-every-access
  - src/clojurecraft/build.clj#defn work-op
  - test/clojurecraft/build_test.clj#a-climb-finishes-before-the-build-repairs-its-headroom
  - src/clojurecraft/build.clj#defn lost-way?
  - src/clojurecraft/build.clj#defn given-up?
  - src/clojurecraft/memory.clj#defn give-up-site
  - src/clojurecraft/memory.clj#defn given-up-sites
  - test/clojurecraft/build_test.clj#a-site-no-way-reaches-is-given-up
  - test/clojurecraft/build_test.clj#a-boxed-climb-back-gives-up-the-distant-site
  - src/clojurecraft/build.clj#defn boxed-way?
  - src/clojurecraft/build.clj#defn to-height
  - src/clojurecraft/build.clj#defn round-corner
  - src/clojurecraft/build.clj#defn in-room?
  - src/clojurecraft/build.clj#defn flooded-room?
  - src/clojurecraft/build.clj#defn near-enough?
  - test/clojurecraft/build_test.clj#a-block-at-the-edge-of-reach-is-walked-to-not-dug
  - src/clojurecraft/build.clj#defn site-lost
  - test/clojurecraft/build_test.clj#a-flooded-room-is-no-site
  - test/clojurecraft/build_test.clj#a-corner-is-never-a-cell-of-the-room-the-cast-digs
  - src/clojurecraft/build.clj#defn way-refilled?
  - test/clojurecraft/build_test.clj#a-way-filled-by-falling-gravel-is-cleared-again
  - src/clojurecraft/build.clj#defn clear-of?
  - src/clojurecraft/build.clj#defn lava-touching?
  - src/clojurecraft/build.clj#defn barred
  - test/clojurecraft/build_test.clj#a-way-is-cut-round-water-and-awkward-footing
  - test/clojurecraft/build_test.clj#a-refill-stand-never-rests-on-magma
  - test/clojurecraft/build_test.clj#an-unsafe-build-stance-gives-up-the-site
  - test/clojurecraft/build_test.clj#an-exhausted-walk-to-a-nearby-build-stand-gives-up-the-site
  - test/clojurecraft/build_test.clj#a-pool-without-a-safe-station-is-left
  - src/clojurecraft/build.clj#defn sources-near
  - test/clojurecraft/build_test.clj#the-lava-hunt-passes-over-a-given-up-pool
  - test/clojurecraft/build_test.clj#a-way-never-opens-a-cell-beside-lava
  - test/clojurecraft/build_test.clj#a-landing-behind-the-frame-is-gone-round-to
  - test/clojurecraft/build_test.clj#a-way-to-the-site-boxed-in-is-left-by-the-way-out
  - src/clojurecraft/build.clj#defn way-dig
  - src/clojurecraft/build.clj#defn tool-first
  - test/clojurecraft/build_test.clj#a-build-dig-puts-the-stowed-pickaxe-in-hand-first
  - src/clojurecraft/build.clj#defn pickaxe-at-hand?
  - src/clojurecraft/build.clj#defn hand-dig-refused?
  - src/clojurecraft/make.clj#defn take-table-along
  - test/clojurecraft/build_test.clj#a-build-never-digs-rock-by-hand
  - test/clojurecraft/build_test.clj#a-build-replans-for-a-broken-pickaxe-before-the-last-attempt

  - src/clojurecraft/build.clj#defn nearest-stand-cell
  - src/clojurecraft/build.clj#defn stand-cell?
  - test/clojurecraft/build_test.clj#a-room-cell-behind-a-wall-is-cut-toward
  - src/clojurecraft/build.clj#defn refused-for-the-body?
  - src/clojurecraft/build.clj#defn dig-rejected?
  - test/clojurecraft/build_test.clj#a-portal-block-in-the-wall-is-a-wall-already
  - test/clojurecraft/build_test.clj#a-dig-the-server-did-not-do-is-dug-again-before-the-build-fails
  - test/clojurecraft/build_test.clj#a-block-is-never-placed-into-the-bot
  - src/clojurecraft/build.clj#defn toward-stand
  - src/clojurecraft/build.clj#defn surface-climb?
  - test/clojurecraft/build_test.clj#out-of-the-ground-below-the-stand-the-way-is-not-the-stairs-up
  - src/clojurecraft/build.clj#defn stand-walk
  - src/clojurecraft/build.clj#defn beside-stand?
  - src/clojurecraft/build.clj#defn out-first?
  - src/clojurecraft/build.clj#defn dig-from-under
  - test/clojurecraft/build_test.clj#a-block-in-the-way-is-dug-from-a-hole-under-the-stands-height
  - test/clojurecraft/build_test.clj#the-way-out-is-not-taken-across-lava-nor-from-beside-the-stand
  - test/clojurecraft/build_test.clj#a-stand-still-in-the-rock-is-cut-to-not-walked-near
  - src/clojurecraft/build.clj#defn way-columns
  - src/clojurecraft/build.clj#defn floor-gap
  - src/clojurecraft/build.clj#defn bridge
  - test/clojurecraft/build_test.clj#a-way-over-a-hole-gets-a-floor
  - src/clojurecraft/build.clj#defn before-gap
  - test/clojurecraft/build_test.clj#a-bridge-walks-onto-the-floor-laid-before-the-gap-past-its-reach
  - test/clojurecraft/build_test.clj#a-bridge-digs-a-cobweb-out-of-the-floor-before-it-lays-there
  - src/clojurecraft/build.clj#defn refill
  - src/clojurecraft/build.clj#defn sight-block
  - src/clojurecraft/build.clj#defn plant-in-sight
  - test/clojurecraft/build_test.clj#a-cast-digs-the-vein-before-its-water-scoop
  - src/clojurecraft/build.clj#defn lava-aim
  - src/clojurecraft/scoop.clj#defn ray-walls
  - src/clojurecraft/scoop.clj#defn surface-points
  - test/clojurecraft/scoop_test.clj#the-refills-ray-past-a-blocks-edge-is-a-wall
  - src/clojurecraft/build.clj#defn source-in-sight
  - src/clojurecraft/build.clj#defn safe-source?
  - test/clojurecraft/build_test.clj#a-refill-never-opens-lava-over-its-stand
  - test/clojurecraft/build_test.clj#a-refill-checks-the-eyes-at-the-stand
  - test/clojurecraft/build_test.clj#a-refill-never-digs-away-its-own-footing
  - test/clojurecraft/build_test.clj#an-in-flight-refill-dig-preserves-the-feets-support
  - src/clojurecraft/build.clj#defn scoop-at-station
  - src/clojurecraft/build.clj#defn scoop-blocked?
  - src/clojurecraft/build.clj#defn lava-gone?
  - src/clojurecraft/build.clj#defn water-needed?
  - src/clojurecraft/build.clj#defn last-bucket-scoop?
  - src/clojurecraft/build.clj#defn stance-way
  - src/clojurecraft/build.clj#defn way-open?
  - test/clojurecraft/build_test.clj#a-stand-no-line-can-dig-to-is-no-station
  - src/clojurecraft/build.clj#defn seal-source
  - src/clojurecraft/build.clj#defn spare-pour-op
  - test/clojurecraft/build_test.clj#a-cleanup-scoop-never-takes-the-last-empty-bucket
  - test/clojurecraft/build_test.clj#a-lava-source-gone-before-the-aim-is-looked-for-again
  - test/clojurecraft/build_test.clj#a-spent-water-bucket-is-asked-for-again
  - test/clojurecraft/build_test.clj#a-refill-reconsiders-a-scoop-whose-ray-filled
  - test/clojurecraft/build_test.clj#refill-scoops-a-source-under-flowing-lava
  - src/clojurecraft/build.clj#defn reach-sources
  - src/clojurecraft/build.clj#defn room-stand
  - src/clojurecraft/scoop.clj#defn source?
  - src/clojurecraft/build.clj#defn station-among
  - src/clojurecraft/build.clj#defn stands-beside
  - src/clojurecraft/build.clj#def footprint
  - src/clojurecraft/build.clj#def kept
  - src/clojurecraft/build.clj#defn foundation
  - src/clojurecraft/build.clj#defn site-ok?
  - src/clojurecraft/build.clj#defn choose-site
  - src/clojurecraft/build.clj#defn pool
  - src/clojurecraft/build.clj#defn spent?
  - src/clojurecraft/build.clj#defn build-near
  - test/clojurecraft/build_test.clj#a-lone-lava-source-is-no-site
  - src/clojurecraft/build.clj#defn blocks-needed
  - src/clojurecraft/build.clj#defn held-first
  - src/clojurecraft/build.clj#defn carry-table?
  - src/clojurecraft/build.clj#defn carry-sticks?
  - src/clojurecraft/build.clj#defn sticks-at-hand?
  - test/clojurecraft/build_test.clj#a-build-carries-sticks-beside-its-table
  - src/clojurecraft/build.clj#defn table-at-hand?
  - test/clojurecraft/build_test.clj#a-build-carries-a-crafting-table
  - src/clojurecraft/build.clj#defn lava-hunt
  - src/clojurecraft/build.clj#defn lava-walk
  - src/clojurecraft/build.clj#defn uncap
  - src/clojurecraft/build.clj#defn toward-lava
  - src/clojurecraft/build.clj#defn explore-lava
  - test/clojurecraft/build_test.clj#the-lava-hunt-tunnels-away-from-the-landing-when-no-lava-is-loaded
  - src/clojurecraft/build.clj#defn castable?
  - src/clojurecraft/build.clj#defn hunt-target
  - test/clojurecraft/build_test.clj#the-lava-hunt-goes-down-past-lava-it-cannot-cast-from
  - test/clojurecraft/build_test.clj#the-lava-hunt-passes-sources-with-no-safe-stand
  - test/clojurecraft/build_test.clj#the-lava-hunt-leaves-a-boxed-pocket-by-the-way-out
  - test/clojurecraft/build_test.clj#the-lava-hunt-climbs-to-a-source-just-over-the-feet
  - src/clojurecraft/memory.clj#defn site
  - test/clojurecraft/build_test.clj#the-plan-casts-the-ten-cells-in-layer-order
  - test/clojurecraft/build_test.clj#the-plan-never-places-into-its-own-stance-nor-into-the-room
  - test/clojurecraft/build_test.clj#every-cell-the-plan-works-is-in-its-footprint
  - test/clojurecraft/build_test.clj#no-dig-can-take-obsidian
  - test/clojurecraft/build_test.clj#a-stand-inside-a-hill-is-dug
  - test/clojurecraft/build_test.clj#a-pad-stand-over-water-is-placed-from-the-shore
  - test/clojurecraft/build_test.clj#a-stand-up-a-bank-is-passed-over-for-one-a-route-reaches
  - test/clojurecraft/build_test.clj#a-site-is-cut-into-the-rock-under-a-surface-pool
  - test/clojurecraft/build_test.clj#a-pool-sealed-in-the-rock-has-a-site-beside-it
  - test/clojurecraft/build_test.clj#no-site-touches-water
  - test/clojurecraft/build_test.clj#a-build-row-holds-a-pickaxe-before-it-starts
  - test/clojurecraft/build_test.clj#the-portal-kit-asks-for-no-constant-of-blocks
  - test/clojurecraft/sim_test.clj#a-portal-is-cut-into-the-rock-beside-a-sealed-pool
  - test/clojurecraft/sim_test.clj#the-frame-is-cast-lit-and-stepped-through
  - test/clojurecraft/sim_test.clj#a-cast-six-tenths-done-casts-only-the-other-four
  - test/clojurecraft/sim_test.clj#with-no-lava-near-the-bot-goes-down-to-the-sea-and-casts-beside-it
related:
  - "[[bot/plan/_moc|Plan]]"
  - "[[server-model]]"
  - "[[sib-portal-mold]]"
---

# Build plan

A plan is a vector of ops, flat maps: what (`:build/op` `:place :dig :pour :scoop :use-on :stand-in :verify`), where (`:build/pos`, and `:build/stand` where the bot stands), with what (`:build/item`, `:build/aim` for a pour), and when it is done (`:build/done`, a predicate as data read by the open multimethod `holds?`: `[:solid p] [:not-solid p] [:firm p] [:filled p] [:block p name] [:lava-source p] [:no-water p] [:dimension name] [:or ...] [:and ...]`). Nothing about progress is stored: the op to work is the first one not satisfied, so a build resumes after a death, a chunk reload or a relaunch by reading the world again.

## The cast (`portal-plan origin facing h`): the rock is the mould
Issue #124 replaced ruststeve's template mold (a pad, a rim, platforms, walled cups, 192 cobblestone) with the way a player casts: beside any lava pool, at any depth, the frame is cut into a face of the rock. The frame is ten obsidian in the plane of the origin's z, cast bottom up in `layers` order (never sorted by height); positions are written for the bot working from +z and turned for the other facings (`point`, `cell`, `aim`).
1. `room-ops`: the landing (where a level way leads to the lava) dug first, reached at its height, then the room in front of the face, nearest the landing first, each cell dug from wherever the bot reaches it: two rows six high (dz 1 and 2), the five-step `stair` at dz 3 the upper cells are cast from, and the `access` steps up to `h`, the lava's height over the room's floor. Floors under the stands are made firm where the rock is missing.
2. `cell-ops` for each cell, from its stand (`stands`): its walls made firm where the rock is missing (`firm-ops`: behind it, under it, either side, and behind the cell over it; a falling block there dug out first), the hole and the cell over it dug, lava poured low against the hole's back wall, water poured into the cell **directly over the lava** against the same wall, so it lands on the source and turns it to obsidian, and the water scooped straight back from that cell. Water is never poured beside the lava: on a server a source with a side open flows within about 1.5 s, and that flow meeting water is cobblestone where the frame cell should be. The plan's tests hold that every water op targets the cell directly above its lava cell (`the-plan-casts-the-ten-cells-in-layer-order`). Every op but the scoop is done once the cell is obsidian.
3. `interior-ops` (the 2x3 inside dug out, never obsidian), `light-op` (flint and steel on the top of cell (1,0) from two in front), `enter-op` (stand in the portal until the dimension is the Nether).

In solid rock the cast places four blocks: the walls its own digging opened (the bottom cells' over cells are the columns' side walls; the columns' top over cells are the top cells' side walls). `cast-one-plan` is the room and the first cell, with a `:verify` that it is obsidian and the water taken back. A plant or vein (`blocks/solid?` false: a sculk vein, glow lichen) on the scoop's line to its water stops the bucket's ray though nothing is built of it, so the scoop was sent three times unanswered and failed `:no-scoop` (issue #227, R2/15); `op-child` digs it first (`plant-in-sight`).

Water cleanup and the first-cast verification use `:no-water-source`, not `:no-water`: only a source can be scooped. A later cast's water can flow through an earlier, already-scooped cell (issue #419, R1/20), which must not select that earlier scoop again and fail `:target-gone`. Re-reading these predicates also drops a failed scoop child when the plan moves on to the remaining source. `:no-water` still means no water of any level.

## The site and the station
The lava hunt's `hunt-target` skips sources already beside the feet at their height: `toward-lava` stops its tunnel one cell short of a source, so such a target has zero steps and hides farther sources when no site can be chosen there (issue #392, R1/14). A source below the feet remains a target even in an adjacent column, since its descent still advances. The same selection runs after an unfinished build gives up its site.

`choose-site` looks beside the `max-sources` lava sources nearest the feet, loaded, at any depth. For each cell beside the cell over a source (`stands-beside`: firm under it, it and the head openable, no fluid touching them, the cell over the source openable with no fluid round it but the source), each facing and each of `station-spots` (the lava's stand seven in front of the frame, 1 to `max-access` over the room's floor), the origin follows; `site-ok?` wants the footprint (the frame's rock `frame-box`, the room and its floors) loaded and no fluid in it or touching it, and a pool that can finish the frame (`spent?` false: `pool`, the sources any lava stand of the site could scoop, every height up to `max-access`, at least as many as the frame's cells still to cast). Sim race 38664661309 (issue #142, 89 runs in 18 rounds) chose sites beside a lone source in five worlds, cast one cell and failed `:no-station` with nine to go (`a-lone-lava-source-is-no-site`). Each candidate's checks look only at the sources near its own source (`build-near`, within `pool-radius` of any origin it may take): over the lava sea, with thirty thousand sources loaded, a choice took forty seconds. Of those, the fewest blocks to place (`blocks-needed`, at most `site-blocks`), then the nearest. The lava's stand is always over the room's floor: water spilled on the floor never runs up to the pool.

`refill` (a lava pour with no lava bucket): the `station` (`station-among`: the cell nearest the `landing`, four to `station-depth` in front of the frame and up to `station-span` aside of the landing's column, from which lava sources are in reach, `reach-sources`; the build's own height first). As the near sources are scooped the next stand along the pool is the station: from one stand a one-wide trench gave five sources and the cast stopped `:no-station` at the sixth cell, and a stand beside one source alone moved across the pool once that source was gone, where no route went. The feet go to the stand (`toward-stand`: a walk when the stand can be stood in as it is and a route reaches it (`stand-walk`: a stand still in the rock is cut to, never walked near, sim race 38855091523, issue #178, walked beside a stone stand, ended a block under it, and went back to the landing and out again for the run's last minutes; from beside a stand a block off its height the way is to its height, `beside-stand?`, and the way out along the landing's column is taken only when its straight way on to the stand crosses no barred cell and the feet are not already beside the stand, `out-first?`: with the pool scooped to its far side, the way out crossed the pool's lava and `round-corner` and the refill sent the feet back and forth for the run's last minutes, `the-way-out-is-not-taken-across-lava-nor-from-beside-the-stand`; and from a hole a block under the stand's height the block in the way is dug when in reach (`dig-from-under`): the approach walk to it ended in such a hole, and the refill went back to the landing, `a-block-in-the-way-is-dug-from-a-hole-under-the-stands-height`; `a-stand-still-in-the-rock-is-cut-to-not-walked-near`); at the lava's height a level way dug straight out from the landing to the stand's distance, then aside, never along the stair, whose floors a sideways way cut and the plan put back, and where the way crosses an open cell with nothing under it, a cobblestone laid there from the feet (`bridge`, `floor-gap`: sim race 38665691522, issue #162, world R3/37, ran the way out over a cave's open cell, the line clear and nothing to dig, and the walk failed `:no-path` three times; `a-way-over-a-hole-gets-a-floor`); `to-height` stairs otherwise); there (`scoop-at-station`) the nearest source in reach the eyes can be given a line to (`source-in-sight`: never through obsidian, the next source when the nearest is behind it) is scooped when the line is clear, else the first block on the line is dug (`sight-block`: the rock between the stand and a sealed pool, the cell over the source last). The line is the bucket's ray as wide as the game's edge test (`scoop/ray-walls` over `scoop/ray-cells`), and the scoop aims at the surface's centre or, when that ray is not clear, at a point a quarter block off it (`lava-aim` over `scoop/surface-points`): race 38839564087 runner 1 (issue #225, at 3f836ff) had bots Clj_r12, Clj_r19 and Clj_r21 send nine scoops each from the station along lines within 0.03 of an edge of deepslate or granite that the old sampled line stepped past, and the build failed `:no-scoop` (`the-refills-ray-past-a-blocks-edge-is-a-wall`). The scoop is aimed (`:intent/aim`, `:intent/fluid :lava`) and asks only that the source is still there (`scoop/source?`), never air over it: the bucket's ray passes air and every fluid that is not a source, so a source whose top is flowing lava, or one the line enters from the side through a cell just dug, fills it. Sim race 38715231137 (issue #142, regressed, 166 runs in 44 rounds, worlds R4/15, R1/26, R2/34): the source over the nearest had been scooped and lava flowed into its cell, or the sight-block dug the cell beside a sealed source under another; the line was clear but the source had no air over it, and the refill failed `:no-station` three times in three ticks with half the pool left (`refill-scoops-a-source-under-flowing-lava`). A site no way reaches is given up the same way: a way child (a walk, a swim out, stairs) that fails `:no-path` or `:stuck` with the feet beyond `site-reach` of the room's landing (`lost-way?`) ends the build, the site's origin a fact (`memory/give-up-site`), and the planner passes over the remembered site and chooses none within `give-up-radius` (16) of it, sources or origins (`given-up?`). Sim race 38839665459 (issue #222, world R1/11, six runs in two rounds) kept a site 70 blocks off across an aquifer: the walk there failed `:no-path`, the stairs toward its landing were refused for the water, the swim out of the water they let in dug a flooded staircase until `:stuck`, and the escape from it failed `:stuck`, three failures toward the one site (`a-site-no-way-reaches-is-given-up`). A refill with no station at a site whose pool is `spent?` ends the build instead of failing it, and the planner passes over a remembered site that is spent (`plan/next-intent :build`), choosing a new one or hunting for lava. The way dug never takes the frame's rock (`kept`).

## The `:build` intent
`approach-stand` works any dig already within reach from the actual eye, without requiring centring or a new walking stance. For a place, the feet are their own stand when they already stand in the designated stand on a block that holds them (`holds? [:solid …]`), though a route cannot stand there (soul sand, mud: path awkward, `path/standable?` false): sim snapshot R4-8-1 (issue #575) fell into a soul sand valley and every tunnel floor laid from its own feet failed `:no-stand` three times (`a-floor-is-laid-from-feet-standing-on-soul-sand`). A `:stand-in` still centres the feet first: its await holds still, so working it off-centre times out `:not-yet`. When a room cell is out of reach and no existing stance or level cut reaches it, `way-back` returns to the room's fixed landing even within `site-reach` if the feet are at another height. Issue #328's R1/1 snapshot could reach its tunnel dig while off-centre in water; its real snapshot stood five blocks above its landing, too high to reach any working stance but too close for the old distance-only return gate. Both rules re-read the world, so resumed attempts recover too; placement stances and the safeguards on ways dug remain unchanged.

The lava hunt's `sources-near` excludes sources within `give-up-radius` of a given-up site, just as `choose-site` does. Otherwise abandoning an unsafe pool sends `lava-walk` back and forth between its stands, although no frame there will be accepted (`the-lava-hunt-passes-over-a-given-up-pool`).

A return climb that exhausts its headings (`:stairs-up`, `:boxed`) beyond `site-reach` also gives up the site through `lost-way?`. Issue #363's R2/43 and R4/18 snapshots retried such climbs toward the same distant room until three build attempts failed. Ending the build and recording the given-up site lets the next planner pass choose elsewhere, including when the failed child is already present. Boxed descents still remember their pocket and seek the way out; nearby climbs and non-build executors retain their own failure handling (`a-boxed-climb-back-gives-up-the-distant-site`).

Refill approaches use the pathfinder's footing rules, not solidity alone. `stands-beside` and `station-among` require a firm walking floor (`path/floor?`); magma is solid but cannot support a route. `barred` includes water and cells over awkward footing, so `out-first?` rejects a flooded or magma-lined straight approach and `round-corner` cuts a dry detour instead. Issue #162's R1/16 approach crossed water fed from an aquifer above the mine, while R1/42 selected magma footing beside the pool; repeated walks failed `:no-path` (`a-way-is-cut-round-water-and-awkward-footing`, `a-refill-stand-never-rests-on-magma`). When a fixed stance itself is flooded or has awkward footing, a failed walk gives up the site even nearby (`lost-way?`). A refill with no safe station also gives up its site, whether lava remains or not: geometric source counts do not prove it can be scooped. Both use `memory/give-up-site`, so the next planner pass chooses elsewhere instead of spending three failures at the same unsafe site (`an-unsafe-build-stance-gives-up-the-site`, `a-pool-without-a-safe-station-is-left`). A room the digs let an aquifer into is left the same way (`flooded-room?`: water in a cell of the room, which the site did not have when it was chosen): the floor row and the stands are awash and a route never crosses water, so a build that finds no stand (`site-lost`), or whose walk or dig fails `:no-path`, `:stuck` or `:out-of-reach` (`lost-way?`), gives its site up, a `:portal` build too, not only a `:cast-one`. Sim race 39470661984 (issue #196, world R3/40) found no stand for the first wall op three times with the floor row flowing, and sim race 39610239818 (issue #461, world R1/17) walked at a stand over aquifer water, failed `:no-path` twice, and failed `:out-of-reach` on the dig the swim's bob left short (`a-flooded-room-is-no-site`). A block in the way of a stand is dug only from inside reach with room to spare (`near-enough?`, `reach-slack` under the dig's 4.5), else walked to first (`clear-stand`): real snapshot real-5-s2-Clj_r09 (issue #461) began the refill's dig from 4.478 blocks, the walk's momentum had the eye at 4.5007 the next tick, and the dig failed `:out-of-reach` each cycle (`a-block-at-the-edge-of-reach-is-walked-to-not-dug`).

The refill's clear ray can fill after its scoop child is chosen. `scoop-blocked?` checks a lava scoop's `:intent/aim` against the current terrain before `work-op` advances it, including a child already waiting for an answer or failed. A blocked child is dropped without spending a build attempt; the next refill chooses a clear source or digs its sight line again. Issue #227's R3/26 and R3/29 snapshots had gravel fall into the ray while the bucket was equipped, then retried that same blocked ray until `:no-scoop` (`a-refill-reconsiders-a-scoop-whose-ray-filled`). Water scoops retain their cast's side aim.

A refill's source can turn to obsidian after it is chosen, when the cast's water or the water a dig let in reaches it. The scoop child then fails `:target-gone`; `lava-gone?` has `work-op` drop it, like a blocked ray, so the next refill chooses another source and a pool with none left ends in `:no-station` and a new site. Real races 39245788157 and 39590862487 (issue #419) failed the build on the first vanished source with lava still in reach (`a-lava-source-gone-before-the-aim-is-looked-for-again`).

A water pour can land in the lava's own cell, where the lava becomes obsidian and the water goes with it: no source is left to scoop back and both buckets are empty. `held-first` adds a water bucket to the row's holds while a water pour is unsatisfied and none is held (`water-needed?`), so the build's next start asks the needs planner to fill one. Issue #455 failed `:not-held` three times in 300 ms at the next cell's water pour (`a-spent-water-bucket-is-asked-for-again`).

A resumed cast can find the cleanup scoop of a cell whose water source is still there while the bot holds one empty bucket and one water bucket (issue #518). The scoop would end with two water buckets and no empty one, and the next lava pour fails `:no-bucket`. `last-bucket-scoop?` catches it and `op-child` removes the source with a filler block instead (`seal-source`, `stairs/filler`): the cleanup's job is to delete the source, not to collect water. The filler is dug out where a later op needs the cell open (the next cell's hole dig, the interior digs). A bot already holding water buckets and no empty one pours one into the cell over a cast frame cell (`spare-pour-op`, obsidian under it), where the cleanup then seals it with a filler, and refills with the freed bucket.

A refill's station must have a way: `refill` passes `station-among` an `open?` predicate (`way-open?`), true when the stand can be walked to as it is or the line `stance-way` digs along (the `stance-block` line) has no solid cell a build's dig may not take (the frame's rock, a cell beside lava). A stand behind such a cell was chosen, nothing was dug, and the fallback walk at the solid stand failed `:no-path` three times (issue #162, real-4-s1-Clj_r08, a cave's lava lake).

Refill sight lines must be safe to excavate as well as reachable. `safe-source?` refuses a source whose ray walls touch lava at or above the stand's feet; lava below the feet may still be uncovered. Both `station-among` and `source-in-sight` apply this rule, so a new station avoids the dangerous opening and a resumed refill reconsiders it. Issue #231's R1/32 snapshot targeted a source at y -26 from a stand at y -25, but the rock on its ray held back lava at y -24. Opening it flooded the stand and three escapes failed `:no-footing`. With the unsafe sight line rejected, the snapshot casts its first obsidian after 30700 ms (`a-refill-never-opens-lava-over-its-stand`).

When the feet already centre on a candidate stand (`intent/centred?`), `station-among` checks its sight lines from the actual eyes, not the cell's exact centre. Sim race 39044511861 runner 6 (issue #380, R3/32) reached a stand slightly off centre: its projected ray was safe, but the actual ray grazed a higher wall holding back lava. The refill refused that ray and failed `:no-station` three times despite 113 sources remaining in the pool. Reconsidering the stand from the actual eyes selects another and casts the first obsidian after 202750 ms (`a-refill-checks-the-eyes-at-the-stand`).

An active child climb finishes before `work-op` switches to a newly unsatisfied op. Issue #369's R3/16 snapshot dug a room floor for the climb's headroom; switching ops cancelled the climb, replaced the floor over the feet and repeated until all 28 cobblestone were spent (`:not-held`). Finishing the climb lets the feet leave the pocket before the plan repairs its floor. Finished or failed climbs, and other children, still give way to a changed op (`a-climb-finishes-before-the-build-repairs-its-headroom`).

When `work-op` reports `:no-pickaxe`, directly or from a failed child staircase, the `:build` executor ends the intent without failing an attempt. The goal remains unmet in the world, so `held-first` plans a replacement and the remembered site is resumed afterward. Issue #347's R1/35 and R1/4 snapshots reached tool loss with two failed attempts already counted: propagating the missing tool as another failure stopped the plan before it could recover (`a-build-replans-for-a-broken-pickaxe-before-the-last-attempt`). Other failure reasons still fail the build. A `:build` dig of rock with no pickaxe held is left to the hand only within `hand-dig-reach` (24) blocks of the site's origin (`hand-dig-refused?`, with the deepslate limit and a pickaxe at hand): the cast's last few blocks. Farther out it fails `:no-pickaxe` and the row makes the pickaxe first, fetching the wood if it must. Issue #504: a bot returning to its portal after a death wore its stone pickaxe out at y 21 with one stick held and dug the eighty blocks still to go at 7.5 s each, ten minutes.

The shared `work-op` makes one exception to the hand-dig time limit for `:tunnel-to`: without a replacement at hand, even slow rock is dug by hand. Issue #296's woodless Nether tunnel stopped at quartz ore and retried until the plan failed; the [[fortress-tunnel]] planner makes a pickaxe from held supplies when it can, and a broken-tool tunnel yields for that planning without spending an attempt. Other failure reasons still fail the build.

`{:intent/kind :build :intent/build :cast-one|:portal|:nether :intent/origin o :intent/facing f :intent/access h}`: each tick the plan (`plan-for`, memoized) is read and the first op not satisfied is worked; done when the plan's last op holds. `op-child` makes the child: a lava pour with no lava bucket refills; a water pour refuses (`:stray-lava`) when the cell over the lava touches any other lava (`stray-lava?`); a room cell is dug from any cell in reach, else the nearest standable cell a route reaches (`reachable-stand`, near the cell's height or a block under it, then `room-stand`, any room cell, a stair step for a cell high in the rows), else the way to the stand cell nearest it at the feet's height with a block in the straight way that may be dug is cut (`nearest-stand-cell`, `toward-stand`; sim race 38660819204, issue #74, stood outside its own room with no route in and failed `:no-stand`; a stand at another height flipped with the feet, and the frame's rock between the feet and the room is still not gone round), else, from more than `site-reach` off the room's landing, the way back to the site as a landing op goes (`way-back`: a walk, else stairs to the landing's height and a level way dug; sim race 38806318231, issue #196, climbed from its room at y 23 to y 64 for a pickaxe's wood, and the next room cell, 41 below, had no stand and no cut at the feet's height: `:no-stand` three times in three ticks; `a-site-left-for-a-pickaxe-is-gone-back-to`); any other op from its stand (`approach-stand`, `toward-stand`, a solid in the way at the stand's height dug first, `stance-block`). Then `:place` into a given `:intent/cell` (`place/support` or a `foundation` built up from below), a child dig, `:pour` (equip, look at the aim, `use-item`, done when `:intent/expect` holds), `:scoop` (the water aimed through the cell's open side, `:intent/aim`: the scoop judges only that the source is still there, `scoop/source?`, since rock is over it by design), `:use-on` or `:await`. A new op drops the child. Every dig the build opens a cell with is a `way-dig`: beside a fluid when it must be, and with its drop not wanted (`:intent/drop-ok?`), so ore the held pickaxe cannot harvest is broken for nothing rather than refused `:needs-tool`, and before it the pickaxe only the main inventory holds is put in hand (`tool-first`, `intent/stowed-breaker`): sim race 38806318231 (issue #70, 1065 runs) met deepslate redstone ore in the room at y -24 with a wooden pickaxe, and dug the way to a landing at y 29 by hand with the spare stone pickaxe in slot 35 until iron ore in the way failed `:needs-tool`, three times each (`a-build-dig-puts-the-stowed-pickaxe-in-hand-first`)A dig child of rock with no pickaxe held fails the build `:no-pickaxe` (`stairs/tool-missing`) instead of digging it by hand when one can be made where the bot stands (`pickaxe-at-hand?`: the needs planner's next step toward one is a craft or a table placed, no trip out for wood or stone; or the block takes over `hand-dig-limit`, 10 s, by hand, deepslate; else the hand digs on, as a cast's last blocks are best dug: `hand-dig-refused?`), so the row's `held-first` makes one; and the placed table within reach is taken along before the build or the lava hunt starts (`make/take-table-along`), and with none at hand one is made for the kit when planks or logs held allow (`carry-table?`, `table-at-hand?`: sim races 38806318231 and 38843552659, issue #186, wore out every pickaxe in the cast with sticks and cobblestone in hand and the table at the furnace, then climbed out for a log and waited `:no-log`; `a-build-carries-a-crafting-table`), and with the table in hand `kit-sticks` (4, two pickaxes' handles; 2 until sim race 39729340993, issue #135 R3-4-1: a climb from y -54 wears out two stone pickaxes) when they can be made from what is held (`carry-sticks?`, `sticks-at-hand?`, the table first so its planks are never spent on sticks): world R3/12 seed 1 from spawn on main (Refs #178) wore its pickaxes out holding a table, 684 cobblestone and one plank, no sticks, and cut stairs up through deepslate by hand until its time ran out (`a-build-carries-sticks-beside-its-table`), so a pickaxe broken down there can be made again from sticks and cobblestone. Sim race 38669750355 (issue #135) broke its last pickaxe at y -20 and dug deepslate by hand, fifteen seconds a block, for half an hour (`a-build-never-digs-rock-by-hand`). `reachable-stand` never takes a cell whose body, feet or head, is the cell worked on, and a placement the server refused with its cell inside the player (`refused-for-the-body?`, `place/inside-player?`) drops its child to be approached again rather than failing the build: the game refuses a block into any player. Sim race 38665691522 (issue #44) laid a floor from a stand whose head was in the floor's cell and failed `:rejected` three times (`a-block-is-never-placed-into-the-bot`).

A site so far off that its landing's chunk is not loaded (a death sent the feet back to spawn: sim race 39076908669, issue #196, world R3/13 seed 2, 190 blocks) is walked toward first (`far-way`: a `:walk` `:for :site` toward the landing's column at the feet's height, as far as the loaded terrain leads), before any op looks for a stand: a floor cell is no dig, so it never took `way-back`, and stairs cut at spawn would have started the climb to the site at the wrong place. A place op or a pour with no stance reachable falls back to `way-back` as a dig does, `:no-stand` only when the feet are at the landing already. The roof `uncap` digs over a lava source is broken for nothing when its ore is above the pickaxe's tier (`way-dig`: issue #163, a stone pickaxe on deepslate diamond ore at y -59). The cells a way dug for a stand must keep (`place-cells`, read by `stance-block`) are those the plan places at every access, not access 0's: sim race 39076908669, issue #178, world R1/11, a build at access 4 dug its landing's cobblestone floor each time it was placed. `lava-walk` never walks to a stand the walk would call arrived at once (`intent/arrived?`, the eye within reach), where it used a two-block radius smaller than the walk's reach: real race 39076909951, issue #163, walked the same stand every fifty milliseconds after the roof was dug. The kit's table and sticks are fetched, not only made from what is held, while the feet are out of the ground with a trunk a route reaches (`wood-near?`): sim race 39076908669, issue #178, world R2/21, broke its last pickaxe at the cast with no table, one stick and no planks and climbed a hundred blocks of deepslate by hand, seventeen seconds a dig, for the log.

An exhausted fixed-stance walk (`:no-path`, not a pickup approach) gives up a nearby site even when the target is dry and has sound footing. The route may be blocked between the feet and the stance; classifying only the target as water or awkward misses this case and retries the same site until the plan fails (issue #162, R1/17 and R3/23). `lost-way?` records the site through the existing `work-op` recovery, so resumed failed children recover too and site selection excludes the same pool. Falling blocks in the way are still cleared first, and nearby pickup walks, dry-stance `:stuck` failures and non-build executors retain their previous handling (`an-exhausted-walk-to-a-nearby-build-stand-gives-up-the-site`).

If the build's way to its landing uses a retreat walk that fails `:no-path`, remember that failed waypoint in `:plan/retreat-unreachable` and recompute the escape before giving up the site. Otherwise replanning can choose another site that asks for the same unreachable waypoint and exhausts the portal attempt budget (issue #162, R2/43; `an-unreachable-retreat-waypoint-is-not-retried`).

## The targets

A lit portal's next operation is entry, not another pass through the casting room (`lit-entry`, `next-op`). The build planner skips the casting kit and table collection when a route reaches that entry; a blocked way still needs its tools. Issue #389 (R2/38) had reached `:portal`, but filled room cells and a broken pickaxe sent `:nether` climbing through deepslate by hand for wood instead of stepping into the existing portal.
Entry walks directly into the portal when a route reaches it; otherwise `approach-stand` goes by the room's landing first, using the same way around the protected rock as the cast. Sim race 39007433993 runner 4 (issue #346, R4/1 seed 1) approached the lit portal from behind its back wall. The portal itself is inside `kept`, so no `round-corner` could end there; the direct way dug up to the wall and three walks failed `:no-path`. Going by the landing preserves the back wall and reaches the open side, including when resuming an already lit portal. The snapshot enters the Nether 107950 ms after its moment, then starts the fortress tunnel (`a-portal-behind-its-back-wall-is-entered-from-the-room`).

`goals.edn` rows `:cast-one`, `:portal` and `:nether` plan with `:goal/plan :build` and are done by `:built?`; each holds a pickaxe (`:goal/holds`), and before the build starts `held-first` asks the needs planner for it and for the blocks the plan places at the site (`blocks-needed`, counted from the plan, never a constant). `:portal-kit` holds the buckets alone: the water bucket and an empty one for lava (`the-portal-kit-asks-for-no-constant-of-blocks`). With no site, `lava-hunt` walks to an exposed source (`lava-walk`), uncaps one in reach (`uncap`), or cuts stairs and a tunnel toward lava or the lava sea's depth (`toward-lava`). The stairs and the tunnel go only toward the nearest source below or level with the feet round which a frame could be cast (`hunt-target`, `castable?`: as many sources within `pool-radius` as the frame has cells), else down toward the sea: sim race 38811561269 (issue #41) tunnelled under a lone source six over the feet and under a small pool eleven over, stopped a block short with nothing to cut, and waited `:no-site` over the sea (`the-lava-hunt-goes-down-past-lava-it-cannot-cast-from`). From a feet cell in `:plan/boxed-at` no stair is cut, so the hunt takes the mine's way out (`stone/retreat`, `stone/climb`) instead of waiting `:no-site`: sim race 38515210080 (issue #41, world R4/36, y 16, sea forty under; `the-lava-hunt-leaves-a-boxed-pocket-by-the-way-out`).

## Where a cast-one stopped (issues #135, #130, #74, #45, #44, #41)
- **The hunt reaches lava beyond `hunt-radius`.** With no safe source within 48 blocks, `hunt-target` takes the nearest of the first `hunt-checked` within `far-radius` (160, what a view shows): at y -54 over thousands of sources loaded, the real snapshots of #41 waited `:no-site` out. The tunnel to it is cut `stone/tunnel-length` steps at a time.
- **A tunnel for lava carries its source and gives it up when boxed in.** `toward-lava`'s tunnel has `:intent/for :lava` and `:intent/target` the source; `stairs/box` ends it (done, not failed) with the feet in `:plan/boxed-at` and the source given up (`memory/give-up-site`, which `sources-near` leaves out with the pool round it), so the hunt takes the next. From a boxed feet cell no second tunnel is cut: the way out (`stone/retreat`, `stone/climb`). #130's tunnel east from the foot of the stairs was boxed three times in a tick.
- **`way-refilled?` drops a walk only for gravel in reach.** A falling block anywhere on a fifty-block straight way is no clear the bot can dig; the walk fails `:no-path` and `lost-way?` gives the site up (#135, R4/34). A build whose feet are in water with no route from them (`wading-out?`) swims out first (`:swim-out` for `:retreat`): a route never crosses water.
- **A staircase can end inside the frame's box** (#45, real-3-s2-Clj_r05): the feet's own cell is the frame's rock and every way to the landing crosses a wall; no single corner (`round-corner`) is clear, the plain walk fails `:no-path`, and a cast-one gives the site up on the second such failure. A route of several corners round the box would keep the site (not built). `stance-block` takes the z-first line when the x-first one holds a solid cell a way may not dig (the frame's rock, cobblestone the plan places, a cell beside lava) and the z-first none (#45, real-6-s2-Clj_r05).
- **No way, no stand: the site is given up.** A cast-one whose plain walk fails `:no-path` a second time (`lost-way?`, `:plan/attempts`) or whose op has no stand (`work-op`'s `:no-stand`) gives its site up and the planner chooses another; `way-back` goes to the landing whenever no route reaches it, however near (#74's two snapshots stood six blocks off with rock between). A portal's site holds a cast and is not given up for these until a plain walk fails `:no-path` a third time in a row (`:plan/attempts` 2), when the site is given up like the others (issue #162, R3-19-1). A :nether build (the return portal) gives its site up on that second walk: lava beside the stand leaves the plain walk no route (issue #561, R1/12 and R1/24).
- **A dig the server did not do is dug again.** A child `:dig` that fails `:rejected` (the server acked the finish with the block standing: a lagging server drops a finish it found early and queued behind another) is counted in `:intent/rejections` and dug again by `stairs/rejected`, as a stair does, and only the third fails the build `:rejected` (`dig-rejected?`). Real race 39405014440 bot Clj_r13 and race 39407278017 bot Clj_r03 (issue #44) each failed the whole build on the first such dig, with the cast room unfinished (`a-dig-the-server-did-not-do-is-dug-again-before-the-build-fails`).
- **A portal block in the wall is a wall.** `firm-ops` counts a `:nether_portal` cell as already walled: the game takes no block into one and no fluid flows into it, so the cobblestone for it was refused three times, `:rejected`, with the cast room unfinished (real race 39407278017 bot Clj_r03, issue #44; `a-portal-block-in-the-wall-is-a-wall-already`).
- **A bubble column takes a block.** The model's `blocks/placeable-into?` accepts it as the game does (replaceable): #44's bridge over a magma stream was refused three times.

## Gotchas
- **A swim-out that ends where it began, asked for again, gives the site up.** `wading-out?` asks for a `:swim-out` whenever the body is in water with no route; a swim-out ends on dry land, and a shallow pool flickers between the two, so a build at health 1 (sim snapshot R3-4-1, issue #178) swam out every 650 ms for the whole window. `swum` counts swim-outs ended on one feet cell (`:intent/swims`); `max-swims` of them make `swim-looped?` and the build gives its site up like `lost-way?` does. The `:cast-one` and `:portal` rows keep a spare pickaxe (`:goal/spares`) so a pickaxe worn out in the cast leaves another, and wood for it is up at the surface (issue #135).
- **Hunt only sources with a safe stand to open.** `hunt-target` requires `stands-beside`, not just enough nearby sources. Issue #392's real snapshot tunnelled to a low source in a lava body extending above the feet; no stand could be opened safely, the last tunnel had zero steps left, and `:no-site` was waited out. Such sources are skipped both on approach and when already beside them, leaving the hunt free to seek another pool or descend toward the sea (`the-lava-hunt-passes-sources-with-no-safe-stand`). The snapshot no longer recurs over its window; followed on, it casts all ten obsidian cells and reaches `:portal` 589200 ms after its moment.
- **A dig never takes obsidian**: `dig-op`'s done holds when its cell is obsidian (`no-dig-can-take-obsidian`).
- **Water above, never beside.** The model ([[server-model]]) turns a lava source with water above or beside it to obsidian, as vanilla does; the bot puts the water above because on a server the source's open side flows first.
- **The landing is reached at the lava's height**: a way dug toward a stand starts at the feet's column at the stand's height, so `clear-stand` only digs toward a stand at the feet's height; another height is stairs first (`to-height`). A staircase or tunnel child that dies `:boxed` (`boxed-way?`) puts the feet's cell in `:plan/boxed-at` and is dropped, not failed, and from that pocket `to-height` takes the way out the mine takes (`stone/retreat`, `stone/climb`) for the next descent from elsewhere: sim race 38806318231 (issue #130, world R3/11) met an aquifer at y 11 on the way down to a landing at y -22 and was sent down the same refused stairs from the same cell three times, `:boxed` (`a-way-to-the-site-boxed-in-is-left-by-the-way-out`). The landing's ops go through `toward-stand` alone: as a room cell, out of reach 23 over the sea, it had no stand and the cast failed `:no-stand`.
- **The footprint is every cell the plan works** (`every-cell-the-plan-works-is-in-its-footprint`), so the site's fluid check covers them all.
- **No way opens a cell beside lava**: `diggable?` refuses a cell lava lies in or beside (`lava-touching?`), and `round-corner` goes round such cells as round the frame's rock (`barred`). Sim race 38843557186 runner 3 (issue #231, world R1/31) dug its way to a landing at the lava sea's height along the sea's edge; a cell beside a source opened, the lava flowed in, and no footing was in reach (`a-way-never-opens-a-cell-beside-lava`).
- **The frame's rock is gone round, not through**: when the straight way at a stand's height from the feet to it crosses the frame's rock (`kept`, never dug), `toward-stand` goes first to a corner from which both legs keep off it (`round-corner`, `clear-of?`, within `round-reach` of the stand). The sim race at 76010d2 (issue #45, world R4/33) stood behind its own frame with the landing straight ahead through the rock; the cells past it were dug, the landing was out of reach and three walks at it failed `:no-path` (`a-landing-behind-the-frame-is-gone-round-to`). A walk along the way that fails `:no-path` with gravel or sand fallen onto it since (`way-refilled?`) is dropped, not failed, and the next clear digs it out: sim race 38837004562 (issue #45, world R1/6) cut its level way to the landing under a bed of gravel that refilled it, and three walks failed (`a-way-filled-by-falling-gravel-is-cleared-again`). A corner is never a cell of the room the cast digs (`in-room?`): sim race 38924530337 (issue #45, regressed, world R2/9) took a corner in the room's upper row, the bridge laid cobblestone under it, the room op dug it out again, and the walk to it failed :no-path.
- **A stand inside the ground is dug, not walked** (`a-stand-inside-a-hill-is-dug`), and the frame's rock in the way is kept.
- **A bridge walks to its gap before the walk to the stand is tried**: `bridge` lays the floor under the way to a stand cell by cell from where the feet are, and when the next gap lies past `place/place-reach` it walks onto the floor laid so far (`before-gap`) instead of falling to the plain walk over the gap, which has no route. Sim race 39550827333 (issue #162, world R3/43 seed 2) laid three cells from the end of the stairs up and failed `:no-path` three times with the fourth out of reach (`a-bridge-walks-onto-the-floor-laid-before-the-gap-past-its-reach`).
- **A bridge digs a cobweb out of the floor before it lays there**: `bridge`, with a gap in reach that holds a block the server will not place into (`blocks/placeable-into?`: a cobweb is neither solid nor replaceable), returns a `:dig` of that cell (`:intent/drop-ok?`) and lays the cobblestone once it is air. The way to a cast's landing tunnelled through an abandoned mineshaft laid into a cobweb in its floor three times, `:rejected` (real race 39781947117 runner 2, Clj_r14, issue #44; `a-bridge-digs-a-cobweb-out-of-the-floor-before-it-lays-there`).
- **The hunt climbs at most `hunt-rise` to a source**: with no safe source level or below the feet, `hunt-target` takes one whose cell over it lies at most `hunt-rise` (4) over them and `toward-lava` goes up to it (`to-height`) before the tunnel. Real race 39408516353 (issue #392, Clj_r05) sat at y -58 at the lava sea's edge with every source level or below roofed in lava (`the-lava-hunt-climbs-to-a-source-just-over-the-feet`). A source farther over is not climbed to: the tunnel under a lone source six blocks over the feet (issue #41) left the roof out of reach.
- **A stand over water is not walked onto**: the place is made from a cell that already reaches the block (`a-pad-stand-over-water-is-placed-from-the-shore`, `a-stand-up-a-bank-is-passed-over-for-one-a-route-reaches`, issue #45).
- **Lava is not renewable**: a scoop takes a source for good; a pool has many, and the stand reaches the next.
- **A refill keeps its own support.** `safe-source?` rejects a blocked sight line through the cell under the feet, even when all lava lies below them. It applies to projected stations and actual eyes; an unobstructed alternative ray is still usable. Issue #228's R1/16 and R3/43 snapshots dug their own floor while refilling and fell into lava, where escapes timed out `:no-footing`. `work-op` drops an in-flight refill dig of that support and stops controls so the next refill chooses another station.
- **The row against the frame is five wide**: a diagonal ray from the stair's last step (x 4) crossed x 4 of that row when the eye was 0.07 off the step's middle; it met rock, the lava landed in the room, and the bot was trapped in it. A stand aside aims further across the cell (`side-aim`).
- **Blocks counted are the walls the cast opens, not its holes**: a cell dug as a hole is obsidian after (`blocks-needed`); counting it as a missing wall made every sealed-pool site ten blocks and none was chosen.
- **No lava loaded at the sea's depth: tunnel away.** With no source in the 160 blocks the server sent and the feet already at `lava-depth`, `toward-lava` has neither a stair nor a tunnel target; `explore-lava` cuts level steps (`stone/tunnel-length` a call) along the plane axis away from the landing, so the chunks ahead load and the hunt asks again, instead of waiting `:no-site` out. Real race 39318301246 (issue #41, Clj_r21) stood at y -54 in deepslate with no lava loaded (`the-lava-hunt-tunnels-away-from-the-landing-when-no-lava-is-loaded`). A boxed feet cell takes the way out first, as a tunnel toward a source does.
- **Out of the ground below the stand, the way is level.** `surface-climb?`: the feet below the stand's height, on the ground and already out (`stairs/out?`), so stairs up end the tick they start; `toward-stand` then takes the way it takes at the stand's height (a corner, a dig, a floor, a walk), not `to-height`. Sim snapshot R2-39-2 (issue #135): on a snowy slope at y 95 with the landing's stand a block higher and sixty blocks off, the build chose the stairs up, done at once, every tick of the window (`out-of-the-ground-below-the-stand-the-way-is-not-the-stairs-up`).
