---
title: Build plan
description: How a build is data (issue #8) - ops with done-predicates read against the world, the first op not satisfied is worked; portal-plan, the portal cut into the rock beside any lava pool as a player casts it (issue #124); the site, the station, the blocks the cast asks for, and the hunt for lava when no site is at hand (issue #41).
type: reference
tags: [bot, plan, build, portal]
aliases: [build.clj, portal-plan, cast-one-plan, build/holds?, satisfied?, op, :build/done, choose-site, station, refill, blocks-needed, lava-hunt, uncap, no-site]
status: verified
lastUpdated: 2026-10-15
verifiedAgainst: HEAD
sourceRefs:
  - src/clojurecraft/build.clj#defmulti holds?
  - src/clojurecraft/build.clj#defn satisfied?
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
  - src/clojurecraft/build.clj#defn way-back
  - test/clojurecraft/build_test.clj#a-site-left-for-a-pickaxe-is-gone-back-to
  - src/clojurecraft/build.clj#defn work-op
  - src/clojurecraft/build.clj#defn way-dig
  - src/clojurecraft/build.clj#defn tool-first
  - test/clojurecraft/build_test.clj#a-build-dig-puts-the-stowed-pickaxe-in-hand-first
  - src/clojurecraft/build.clj#defn pickaxe-at-hand?
  - src/clojurecraft/build.clj#defn hand-dig-refused?
  - src/clojurecraft/make.clj#defn take-table-along
  - test/clojurecraft/build_test.clj#a-build-never-digs-rock-by-hand

  - src/clojurecraft/build.clj#defn nearest-stand-cell
  - src/clojurecraft/build.clj#defn stand-cell?
  - test/clojurecraft/build_test.clj#a-room-cell-behind-a-wall-is-cut-toward
  - src/clojurecraft/build.clj#defn refused-for-the-body?
  - test/clojurecraft/build_test.clj#a-block-is-never-placed-into-the-bot
  - src/clojurecraft/build.clj#defn toward-stand
  - src/clojurecraft/build.clj#defn refill
  - src/clojurecraft/build.clj#defn sight-block
  - src/clojurecraft/build.clj#defn source-in-sight
  - src/clojurecraft/build.clj#defn scoop-at-station
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
  - src/clojurecraft/build.clj#defn lava-hunt
  - src/clojurecraft/build.clj#defn lava-walk
  - src/clojurecraft/build.clj#defn uncap
  - src/clojurecraft/build.clj#defn toward-lava
  - src/clojurecraft/build.clj#defn castable?
  - src/clojurecraft/build.clj#defn hunt-target
  - test/clojurecraft/build_test.clj#the-lava-hunt-goes-down-past-lava-it-cannot-cast-from
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

In solid rock the cast places four blocks: the walls its own digging opened (the bottom cells' over cells are the columns' side walls; the columns' top over cells are the top cells' side walls). `cast-one-plan` is the room and the first cell, with a `:verify` that it is obsidian and the water taken back.

## The site and the station
`choose-site` looks beside the `max-sources` lava sources nearest the feet, loaded, at any depth. For each cell beside the cell over a source (`stands-beside`: firm under it, it and the head openable, no fluid touching them, the cell over the source openable with no fluid round it but the source), each facing and each of `station-spots` (the lava's stand seven in front of the frame, 1 to `max-access` over the room's floor), the origin follows; `site-ok?` wants the footprint (the frame's rock `frame-box`, the room and its floors) loaded and no fluid in it or touching it, and a pool that can finish the frame (`spent?` false: `pool`, the sources any lava stand of the site could scoop, every height up to `max-access`, at least as many as the frame's cells still to cast). Sim race 38664661309 (issue #142, 89 runs in 18 rounds) chose sites beside a lone source in five worlds, cast one cell and failed `:no-station` with nine to go (`a-lone-lava-source-is-no-site`). Each candidate's checks look only at the sources near its own source (`build-near`, within `pool-radius` of any origin it may take): over the lava sea, with thirty thousand sources loaded, a choice took forty seconds. Of those, the fewest blocks to place (`blocks-needed`, at most `site-blocks`), then the nearest. The lava's stand is always over the room's floor: water spilled on the floor never runs up to the pool.

`refill` (a lava pour with no lava bucket): the `station` (`station-among`: the cell nearest the `landing`, four to `station-depth` in front of the frame and up to `station-span` aside of the landing's column, from which lava sources are in reach, `reach-sources`; the build's own height first). As the near sources are scooped the next stand along the pool is the station: from one stand a one-wide trench gave five sources and the cast stopped `:no-station` at the sixth cell, and a stand beside one source alone moved across the pool once that source was gone, where no route went. The feet go to the stand (`toward-stand`: a walk when routable; at the lava's height a level way dug straight out from the landing to the stand's distance, then aside, never along the stair, whose floors a sideways way cut and the plan put back; `to-height` stairs otherwise); there (`scoop-at-station`) the nearest source in reach the eyes can be given a line to (`source-in-sight`: never through obsidian, the next source when the nearest is behind it) is scooped when the line is clear, else the first block on the line is dug (`sight-block`: the rock between the stand and a sealed pool, the cell over the source last). The scoop is aimed (`:intent/aim`, `:intent/fluid :lava`) and asks only that the source is still there (`scoop/source?`), never air over it: the bucket's ray passes air and every fluid that is not a source, so a source whose top is flowing lava, or one the line enters from the side through a cell just dug, fills it. Sim race 38715231137 (issue #142, regressed, 166 runs in 44 rounds, worlds R4/15, R1/26, R2/34): the source over the nearest had been scooped and lava flowed into its cell, or the sight-block dug the cell beside a sealed source under another; the line was clear but the source had no air over it, and the refill failed `:no-station` three times in three ticks with half the pool left (`refill-scoops-a-source-under-flowing-lava`). A refill with no station at a site whose pool is `spent?` ends the build instead of failing it, and the planner passes over a remembered site that is spent (`plan/next-intent :build`), choosing a new one or hunting for lava. The way dug never takes the frame's rock (`kept`).

## The `:build` intent
`{:intent/kind :build :intent/build :cast-one|:portal|:nether :intent/origin o :intent/facing f :intent/access h}`: each tick the plan (`plan-for`, memoized) is read and the first op not satisfied is worked; done when the plan's last op holds. `op-child` makes the child: a lava pour with no lava bucket refills; a water pour refuses (`:stray-lava`) when the cell over the lava touches any other lava (`stray-lava?`); a room cell is dug from any cell in reach, else the nearest standable cell a route reaches (`reachable-stand`, near the cell's height or a block under it, then `room-stand`, any room cell, a stair step for a cell high in the rows), else the way to the stand cell nearest it at the feet's height with a block in the straight way that may be dug is cut (`nearest-stand-cell`, `toward-stand`; sim race 38660819204, issue #74, stood outside its own room with no route in and failed `:no-stand`; a stand at another height flipped with the feet, and the frame's rock between the feet and the room is still not gone round), else, from more than `site-reach` off the room's landing, the way back to the site as a landing op goes (`way-back`: a walk, else stairs to the landing's height and a level way dug; sim race 38806318231, issue #196, climbed from its room at y 23 to y 64 for a pickaxe's wood, and the next room cell, 41 below, had no stand and no cut at the feet's height: `:no-stand` three times in three ticks; `a-site-left-for-a-pickaxe-is-gone-back-to`); any other op from its stand (`approach-stand`, `toward-stand`, a solid in the way at the stand's height dug first, `stance-block`). Then `:place` into a given `:intent/cell` (`place/support` or a `foundation` built up from below), a child dig, `:pour` (equip, look at the aim, `use-item`, done when `:intent/expect` holds), `:scoop` (the water aimed through the cell's open side, `:intent/aim`: the scoop judges only that the source is still there, `scoop/source?`, since rock is over it by design), `:use-on` or `:await`. A new op drops the child. Every dig the build opens a cell with is a `way-dig`: beside a fluid when it must be, and with its drop not wanted (`:intent/drop-ok?`), so ore the held pickaxe cannot harvest is broken for nothing rather than refused `:needs-tool`, and before it the pickaxe only the main inventory holds is put in hand (`tool-first`, `intent/stowed-breaker`): sim race 38806318231 (issue #70, 1065 runs) met deepslate redstone ore in the room at y -24 with a wooden pickaxe, and dug the way to a landing at y 29 by hand with the spare stone pickaxe in slot 35 until iron ore in the way failed `:needs-tool`, three times each (`a-build-dig-puts-the-stowed-pickaxe-in-hand-first`)A dig child of rock with no pickaxe held fails the build `:no-pickaxe` (`stairs/tool-missing`) instead of digging it by hand when one can be made where the bot stands (`pickaxe-at-hand?`: the needs planner's next step toward one is a craft or a table placed, no trip out for wood or stone; or the block takes over `hand-dig-limit`, 10 s, by hand, deepslate; else the hand digs on, as a cast's last blocks are best dug: `hand-dig-refused?`), so the row's `held-first` makes one; and with no site the placed table within reach is taken along before the lava hunt (`make/take-table-along`), so a pickaxe broken down there can be made again from sticks and cobblestone. Sim race 38669750355 (issue #135) broke its last pickaxe at y -20 and dug deepslate by hand, fifteen seconds a block, for half an hour (`a-build-never-digs-rock-by-hand`). `reachable-stand` never takes a cell whose body, feet or head, is the cell worked on, and a placement the server refused with its cell inside the player (`refused-for-the-body?`, `place/inside-player?`) drops its child to be approached again rather than failing the build: the game refuses a block into any player. Sim race 38665691522 (issue #44) laid a floor from a stand whose head was in the floor's cell and failed `:rejected` three times (`a-block-is-never-placed-into-the-bot`).

## The targets
`goals.edn` rows `:cast-one`, `:portal` and `:nether` plan with `:goal/plan :build` and are done by `:built?`; each holds a pickaxe (`:goal/holds`), and before the build starts `held-first` asks the needs planner for it and for the blocks the plan places at the site (`blocks-needed`, counted from the plan, never a constant). `:portal-kit` holds the buckets alone: the water bucket and an empty one for lava (`the-portal-kit-asks-for-no-constant-of-blocks`). With no site, `lava-hunt` walks to an exposed source (`lava-walk`), uncaps one in reach (`uncap`), or cuts stairs and a tunnel toward lava or the lava sea's depth (`toward-lava`). The stairs and the tunnel go only toward the nearest source below or level with the feet round which a frame could be cast (`hunt-target`, `castable?`: as many sources within `pool-radius` as the frame has cells), else down toward the sea: sim race 38811561269 (issue #41) tunnelled under a lone source six over the feet and under a small pool eleven over, stopped a block short with nothing to cut, and waited `:no-site` over the sea (`the-lava-hunt-goes-down-past-lava-it-cannot-cast-from`).

## Gotchas
- **A dig never takes obsidian**: `dig-op`'s done holds when its cell is obsidian (`no-dig-can-take-obsidian`).
- **Water above, never beside.** The model ([[server-model]]) turns a lava source with water above or beside it to obsidian, as vanilla does; the bot puts the water above because on a server the source's open side flows first.
- **The landing is reached at the lava's height**: a way dug toward a stand starts at the feet's column at the stand's height, so `clear-stand` only digs toward a stand at the feet's height; another height is stairs first (`to-height`). The landing's ops go through `toward-stand` alone: as a room cell, out of reach 23 over the sea, it had no stand and the cast failed `:no-stand`.
- **The footprint is every cell the plan works** (`every-cell-the-plan-works-is-in-its-footprint`), so the site's fluid check covers them all.
- **A stand inside the ground is dug, not walked** (`a-stand-inside-a-hill-is-dug`), and the frame's rock in the way is kept.
- **A stand over water is not walked onto**: the place is made from a cell that already reaches the block (`a-pad-stand-over-water-is-placed-from-the-shore`, `a-stand-up-a-bank-is-passed-over-for-one-a-route-reaches`, issue #45).
- **Lava is not renewable**: a scoop takes a source for good; a pool has many, and the stand reaches the next.
- **The row against the frame is five wide**: a diagonal ray from the stair's last step (x 4) crossed x 4 of that row when the eye was 0.07 off the step's middle; it met rock, the lava landed in the room, and the bot was trapped in it. A stand aside aims further across the cell (`side-aim`).
- **Blocks counted are the walls the cast opens, not its holes**: a cell dug as a hole is obsidian after (`blocks-needed`); counting it as a missing wall made every sealed-pool site ten blocks and none was chosen.
