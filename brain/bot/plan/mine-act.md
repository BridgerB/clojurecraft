---
title: Mine act
description: How the :mine act gets what a goal row's blocks drop - the rows as data (stone, coal, iron, gravel), the tool each needs, the drops a collect waits for, the walk that ends where the drop lands, and the stairs cut down to the row's depth when nothing is in sight - and how :redig gets flint from a held gravel.
type: reference
tags: [bot, plan, mining, goals]
aliases: [:mine, stone.clj, mine-next, :goal/blocks, :goal/depth, mine-stone, mine-coal, mine-iron, dig-gravel, none-in-sight, intent/pickup?, step-down?, :redig, flint-from-gravel, flint]
status: verified
lastUpdated: 2026-10-21
verifiedAgainst: HEAD
sourceRefs:
  - src/clojurecraft/stone.clj#defn mine-next
  - src/clojurecraft/stone.clj#def drops-of-row
  - src/clojurecraft/stone.clj#defn tool-missing
  - src/clojurecraft/stone.clj#defn exposed-of-states
  - src/clojurecraft/path.clj#defn near-lava-trap?
  - test/clojurecraft/stone_test.clj#ore-beside-a-lava-trap-is-left-alone
  - src/clojurecraft/stone.clj#defn beside
  - src/clojurecraft/stone.clj#defn step-down?
  - src/clojurecraft/path.clj#defn step-down-node?
  - test/clojurecraft/sim_test.clj#gravel-in-the-tunnel-floor-is-dug-and-picked-up-from-its-hole
  - test/clojurecraft/stone_test.clj#a-block-in-the-floor-counts-only-where-it-leaves-a-step-down
  - test/clojurecraft/sim_test.clj#ore-at-the-head-and-overhead-is-mined-from-the-tunnel
  - src/clojurecraft/intent.clj#defn routable?
  - test/clojurecraft/stone_test.clj#an-ore-no-route-reaches-is-passed-over
  - test/clojurecraft/stone_test.clj#exposed-rock-that-would-let-fluid-in-is-passed-over
  - src/clojurecraft/stone.clj#defn stairs
  - src/clojurecraft/plan.clj#defn fail-intent
  - test/clojurecraft/stone_test.clj#a-boxed-descent-is-not-started-again-from-the-same-cell
  - src/clojurecraft/stone.clj#defn tunnel-heading
  - src/clojurecraft/stone.clj#defn deeper
  - test/clojurecraft/stone_test.clj#deeper-only-where-a-stair-can-be-dug
  - src/clojurecraft/stone.clj#defn retreat
  - src/clojurecraft/stone.clj#defn dead-end-retreat
  - src/clojurecraft/plan.clj#defn start-intent
  - test/clojurecraft/stone_test.clj#a-retreat-from-a-dead-end-names-it-boxed-as-it-starts
  - src/clojurecraft/stone.clj#defn over-stone
  - src/clojurecraft/stone.clj#defn under-surface?
  - test/clojurecraft/stone_test.clj#a-staircase-cut-from-the-surface-over-stone-is-not-walked-back-from
  - src/clojurecraft/stone.clj#defn seal-stone
  - src/clojurecraft/stone.clj#defn exposed-near
  - test/clojurecraft/stone_test.clj#stone-beside-water-is-sealed-with-what-is-held-before-it-is-dug
  - src/clojurecraft/stone.clj#defn surface-over
  - test/clojurecraft/stone_test.clj#a-stone-no-route-reaches-is-cut-down-to-from-the-surface-over-it
  - src/clojurecraft/stone.clj#defn unsupported?
  - src/clojurecraft/stone.clj#defn footing
  - src/clojurecraft/stone.clj#defn settle
  - src/clojurecraft/stone.clj#defn placeable-cell?
  - test/clojurecraft/stone_test.clj#feet-over-a-gap-wait-to-fall-before-the-mine-plans
  - test/clojurecraft/stone_test.clj#stone-beside-kelp-is-not-sealed
  - src/clojurecraft/stone.clj#defn off-ice
  - test/clojurecraft/stone_test.clj#a-boxed-bot-on-ice-steps-to-the-standable-cell-beside-it
  - test/clojurecraft/stone_test.clj#a-failed-walk-off-the-ice-is-not-chosen-again
  - src/clojurecraft/stone.clj#defmethod path/goal-done? :unboxed-away
  - test/clojurecraft/stone_test.clj#a-long-retreat-does-not-end-on-boxed-ground
  - test/clojurecraft/stone_test.clj#a-boxed-search-does-not-retreat-to-its-start
  - test/clojurecraft/stone_test.clj#a-boxed-pocket-retreats-along-a-short-route-to-new-rock
  - src/clojurecraft/stone.clj#defn climb
  - src/clojurecraft/plan.clj#def boxing-kinds
  - test/clojurecraft/stone_test.clj#boxed-in-by-water-with-no-way-away-the-mine-climbs-out
  - test/clojurecraft/sim_test.clj#a-retreat-out-of-water-ends-on-dry-land
  - test/clojurecraft/sim_test.clj#at-the-rows-depth-a-tunnel-finds-the-ore-its-walls-show
  - src/clojurecraft/intent.clj#defn arrived?
  - resources/clojurecraft/goals.edn#{:goal/id :mine-iron
  - test/clojurecraft/stone_test.clj#with-nothing-in-sight-the-stairs-stop-at-the-rows-depth
  - test/clojurecraft/sim_test.clj#coal-and-iron-are-mined-where-they-lie
  - src/clojurecraft/stone.clj#defn link
  - src/clojurecraft/stone.clj#defn redig-place
  - test/clojurecraft/stone_test.clj#redig-places-only-where-the-drop-can-be-collected
  - test/clojurecraft/stone_test.clj#redig-places-on-the-neighbouring-stair
  - test/clojurecraft/stone_test.clj#redig-tries-placement-again-after-reaching-fresh-ground
  - src/clojurecraft/stone.clj#defmethod intent/run :redig
  - test/clojurecraft/sim_test.clj#one-gravel-is-placed-and-dug-again-until-it-drops-flint
  - test/clojurecraft/sim_test.clj#ore-is-mined-with-a-pickaxe-from-the-main-inventory
  - src/clojurecraft/stone.clj#defn filler-dig
  - test/clojurecraft/stone_test.clj#a-mine-boxed-with-little-filler-digs-dirt-for-it-before-another-stair
  - test/clojurecraft/stone_test.clj#the-surface-over-stone-is-the-ground-not-the-canopy-over-it
related:
  - "[[bot/plan/_moc|Plan]]"
  - "[[make-goals]]"
  - "[[stairs-down]]"
  - "[[gather-chain]]"
  - "[[hardness-and-tools]]"
---

# Mine act

A mining row is data: `:goal/act :mine`, the blocks it digs (`:goal/blocks`) and how deep it may cut stairs looking for them (`:goal/depth`). `stone.clj` is the one act for all of them.

| Row | Blocks | Holds | Provides | Depth |
|---|---|---|---|---|
| `:mine-stone` | stone | a wooden pickaxe | cobblestone | none |
| `:mine-coal` | coal ore, deepslate coal ore | any pickaxe (`:tag/pickaxes`) | coal | 40 |
| `:mine-iron` | iron ore, deepslate iron ore | a stone pickaxe | raw iron | 16 |
| `:dig-gravel` | gravel | any pickaxe, for the stairs down, and a spare (race 38477665442 bot Clj_r19 wore both out tunnelling for gravel, issue #67) | gravel | 40 |

## Short retreats from boxed ground

`stone/retreat` still prefers a route more than twelve blocks away. When the feet are in `:plan/boxed-at` and the search exhausts the reachable ground before that distance (`:path/status :none`), it instead walks to the last unboxed waypoint on the returned route. An empty route or one with only boxed cells still yields nil; a search that merely spent its budget is not exhaustion. Retreat walks end standing on that destination (`intent/standing-walks`), never merely within reach of it, which could leave a short retreat in its boxed starting cell. Sim race 39028864642 (issue #370, R1/29) finished a climb for portal wood under gravel at y 48 with an eight-cell route back through its tunnel, all shorter than twelve blocks away, and waited `:no-log`. The short retreat leaves that pocket so gathering can re-plan from new rock without relaxing the gravel or boxed-cell guards.

A boxed pocket whose search finds no waypoint at all still has a way when the cell the search began on (`:path/from`: the feet's own cell, or the standable cell beside it, `path/start-cell`) is another cell not in `:plan/boxed-at`: the retreat is one step there, and the next descent starts from it. Sim race 39074156402 (issue #71, world R2/35): the bot stood on the edge of a frozen lake in a cove under a cliff, the stairs died `:boxed` on the ice's edge, the retreat had nothing to walk to, and the plan waited `:boxed` to its end. A cell it has already died on is never the step, so the walk ends.

The `:unboxed-away` route goal excludes both boxed cells and `:plan/boxed-route`, the cells visited by descents or tunnels that failed `:boxed` (`plan/fail-intent`). Routes may cross those cells but cannot end on them. Issue #161's R1/29 snapshot repeatedly walked between boxed cells; excluding only the final boxed cells then repeated short descents from their still-unmarked starting cells. Remembering the failed searches' visited cells lets the bot leave that area and finish the portal kit.

## How it works (`mine-next`)
1. A chain in flight continues: walk → dig → collect via `intent/chain-next`, tagged `:intent/for` the row's id, so another row's chain (a coal walk when iron is now wanted) is never continued as this one's dig.
2. A tool the row needs that only the main inventory holds is put in hand first, with an `:equip` intent (`intent/stowed-tool`). `tool-missing`: a block that drops only to its tool (`blocks/drops-need-tool?`, [[hardness-and-tools]]) with no such tool held waits `:no-pickaxe`; gravel drops to a hand and never waits.
3. `exposed-of-states`: the blocks of the row within 24 of the eye and 12 of its height, read from the loaded chunks through the block overlay, that the bot can dig from a cell it can stand in (`beside`: a horizontal neighbour at the block's level or a block lower, the block then at the head, or the cell under it, the block overhead, or a horizontal neighbour a block up when the dig leaves a hole one deep, `step-down?`: solid under the block and the two cells over it clear, so the drop lands in the hole and the collect steps down into it; never straight from above, where the drop would lie under the feet), nearest first (never memory: stone is most of the world and ore is in every chunk; [[memory-edge-cases]]). Of the three nearest, the first a route reaches (`intent/routable?`: the planner's route is not `:none`) is walked to (`:found`, or `:partial` only toward an unloaded chunk); one in a cave below with no way down is passed over. The fourth live iron-kit run walked at three such ores, each failing `:no-path` at once, and the third failure ended the plan. The walk carries `:intent/pickup? true`: it ends where the dug block's drop lands in the pickup box (`intent/arrived?`, the path's `:pickup` goal; [[walk-intent]]), and `:intent/step-down? true` when the stand is a block up, which lets the goal end there.
4. Stone in sight that the dig refuses only for water beside it (`exposed-near` lists it, `exposed-of-states` does not): `seal-stone`, a `:place` (`:intent/for :seal-stone`) of a filler, else planks, into its one to three water cells beside it, each within the place reach with a solid neighbour (`place/support`) and none given up, when a route reaches the stone; the block it makes lets the walk, dig and collect chain take the stone, and the cobblestone it gives is the next seal's filler. Issue #342 (the R1/30 sim moment): the bot on the west bank of a cave lake beside two stones with water on their north sides, three planks and no filler, cut stairs down and climbed out again for the whole window (`stone-beside-water-is-sealed-with-what-is-held-before-it-is-dug`). 5. With none a route reaches, but stone in sight (`exposed-of-states` finds it, `routable?` is false for it: a cave's wall under the ground), `over-stone`: a walk (`:intent/for :over-stone`, so the chain never digs its target, and one of `intent/standing-walks`, ending in the cell itself: a walk that ended near it ended at once and was planned again every tick) to the surface over the nearest of `max-candidates` (`surface-over`: above the highest solid block of its column) that a route reaches, unless the feet are within reach of one already (`under-surface?`: `over-radius` (3) on the plane, and a block more for each block the feet are below the surface, since a staircase down gains a block along for each it drops; sim race 39593216223, issue #62, world R4/2, walked back up to the surface from the foot of the staircase it had just cut, every twelve seconds, until the run stalled: `a-staircase-cut-from-the-surface-over-stone-is-not-walked-back-from`) or it lies within `over-radius` of a cell of `:plan/boxed-at`; the staircase is cut from there. Issue #342 (a real bot, Clj_r15, landed at R1/30): stone lay in the wall of a cave lake five blocks under the far bank; the mine cut its stairs at the near bank, every stair refused for the water, and went up and down the same stairs until the plan failed `:boxed`, with the surface over the stone eight blocks off along a strip of grass and routable all the while (`a-stone-no-route-reaches-is-cut-down-to-from-the-surface-over-it`). Else `stairs`: a staircase six down from the feet, never below the row's depth ([[stairs-down]]). A feet cell in `:plan/boxed-at` is skipped: a `:stairs-down` (or a `:tunnel`) died `:boxed` there (`fail-intent`; `:plan/blacklist` keeps only targets that failed), and another from it would die the same way. Full run ab3945e stood at y 32 over an aquifer, every stair and every further level step refused, and two more staircases from that cell boxed in under a second (`a-boxed-descent-is-not-started-again-from-the-same-cell`). The act then tunnels, goes deeper, retreats, or climbs out. At the depth, a `:tunnel` of `tunnel-length` (12) level steps along `tunnel-heading`: of the four, those with dry ground over the column a tunnel ahead first (`stairs/dry-top?`: gym batch iron-a8 run 2 tunnelled 260 blocks out under the sea and its stairs up met the seabed every way, `tunnels-and-climbs-keep-under-dry-ground`), then the one leading farthest from the landing, the first whose first level step is allowed and has rock to cut, so never back along an open tunnel nor straight at what refused the last. With none, `deeper`: stairs six more down, the row's depth notwithstanding (ore lies below it too), when a stair from here can be cut and dug at all (`stairs/level-safe?`: no cell of it bordering water or lava, which the dig refuses; gym batch iron-a8 run 3 was sent down stairs whose every cell bordered water and failed `:boxed` twice in a second, `deeper-only-where-a-stair-can-be-dug`), or cast, its floor laid from carried cobblestone (`stairs/lay`, [[stairs-down]]). With neither, `retreat`: out of the water first when the feet are in it (a `:swim-out` for `:retreat`, which ends itself on dry land), else a walk to the end of a route at least `retreat-distance` (12) away, where the rock is new. The mine's fallback is `dead-end-retreat`: the walk carries the cell it leaves as `:intent/dead-end`, and `plan/start-intent` puts it in `:plan/boxed-at` as the walk starts, so the staircase cut from above is refused its last stair there (`stairs/clear-way?`) and turns. Without it, sim race 39593216223 (issue #62, world R4/42) alternated a retreat up the staircase and the same staircase down to the same cell, where an aquifer left no stair or tunnel to cut, until the run stalled (`a-retreat-from-a-dead-end-names-it-boxed-as-it-starts`). With no way away either, `climb`: stairs up out of the ground, `climb-stairs` (8) at a time, unless the feet are out already (`stairs/out?`); a stair up seals the water beside its cells and lays its step from carried cobblestone ([[stairs-down]]), which a level step does not, so it is the one cut out of a pocket an aquifer closed, and up top the search starts afresh. Only then a wait `:boxed`. A `:tunnel` that dies `:boxed` records its feet cell in `:plan/boxed-at` as a staircase does (`plan/boxing-kinds`), and `tunnel-heading` and `deeper` are nil there, as `stairs` is: each heading is judged by its first step alone, so a tunnel started again from the pocket dies the same way. Race 38515209959 (issue #105, four real bots at the flint and steel, tunnelling for gravel at y 34-40 into underground water with 300-500 cobblestone in hand): runner 3 bot Clj_r19 and runner 9 bot Clj_r18 swam out of the water that came in onto a cell every stair and tunnel from which was refused, with no route away along the flooded tunnel, and waited `:boxed` out; runner 3 bot Clj_r13 started three tunnels in a row from one pocket between two sheets of water, each `:boxed` a step or two on (`boxed-in-by-water-with-no-way-away-the-mine-climbs-out`). Gym batch iron-a6 run 3's tunnel at y 16 ran along an aquifer until water filled its floor, and it was sent down stairs that could not be cut, `:boxed` three times in a second. With the heading fixed to the farthest, gym batches iron-a3 and iron-a4 retried tunnels boxed in by an aquifer three times in a second and failed `:boxed`. A staircase shows little rock: the live runs that waited at the depth with nothing in sight failed there. The tunnel's walls are where the ore shows; the next pass sees it (`at-the-rows-depth-a-tunnel-finds-the-ore-its-walls-show`).
   A tunnel never heads back under the staircase just cut (`tunnel-heading`: with `:plan/last` a `:stairs-down`, its heading turned round is left out): its first steps would dig the floors of the stairs, the only way back up. Sim race 38839665459 runner 3 (issue #71, every seed of world R1/19 at 3f836ff) cut stairs west down to y 1 and tunnelled east under them; boxed beside an aquifer under gravel, its retreat found no route up the stairs it had undermined, every climb was `:boxed`, and the plan failed (`stone_test`, "never back under the stairs just cut").
5. The collect waits for one more of any item the row's blocks can drop (`drops-of-row`, every try of their loot tables: flint and gravel both for gravel).

**Three edges of the flint hunt (issues #456, #349, #105).** The seal's wet cells must each be one a block takes the place of (`placeable-cell?`, `blocks/placeable-into?`): a kelp plant holds water but is not replaceable, and the seal laid into one was `:rejected` three times (sim race 39186569902, R1/24 seed 2; `stone-beside-kelp-is-not-sealed`). `over-stone` counts the feet as over the surface by the blocks they are under it, since a staircase moves a block along for every block down: stairs cut six down from the surface ended six along, more than `over-radius`, and the mine walked back up them to cut them again for the whole window (sim race 39598424082, R1/35 seed 1; the last assertion of `a-stone-no-route-reaches-is-cut-down-to-from-the-surface-over-it`). And `unsupported?`: with air, water or lava in the cell under the feet and not afloat, the mine, ahead of everything but the lava retreat (`settle`, wrapped round `mine-next` in the `:mine` act), waits (`{:plan/wait :unsupported}`, at most `fall-wait-ticks` planner ticks) for the fall instead of planning stairs from the lip of the hole, which were refused four ways and `:boxed` before the body fell (sim race 39550841573, R2/32 seed 3: the bot dug the ice under its feet to scoop the lake; `feet-over-a-gap-wait-to-fall-before-the-mine-plans`). That body rested on the sliver of its box over the rim and never fell, so after the wait `footing` walks it onto the nearest neighbouring cell that stands (`:intent/for :footing`, ending in that cell). Not fixed: a bot a swim-out left on a one-block gravel island in a pond has no stair, tunnel or route off it (`real-4-s1-Clj_r23`, issue #105).
**On a sheet of ice**, `off-ice` comes before any stair: a walk toward the landing over the sheet (`path/on-ice?`, [[pathfinder]]), tried before the mine row's stairs and the gather's climbs. Race 39088700340 bot Clj_r02 (issue #71) climbed out of a frozen sea onto the ice and cut stairs and laid floors on it until `:boxed`. Not again once a walk to the landing failed (the landing is in `:plan/blacklist`): race 39181272601 bot Clj_r23 (issue #40) was sent three hundred blocks over a glacier to a landing in chunks not loaded, the route partial, and the walk ended `:no-path` where it stood twelve times (`a-failed-walk-off-the-ice-is-not-chosen-again`).

## Flint: dig a held gravel again (`:redig`)
Gravel's loot table drops flint one time in ten, else the gravel itself. The `:flint-from-gravel` row needs one gravel and provides flint; its act starts a `:redig` intent, which runs three child intents in its own place (`link`, as stairs runs its digs): `:place` the held gravel in the pickup cell chosen by `redig-place` (in a passage if need be, since it is dug again at once), `:dig` what it placed, `:collect` with the row's drops (flint and gravel). One intent from placing to picking up matters: as three separate intents, the placing spent the gravel the row needs, and on the next tick the planner went for more gravel with `:dig-gravel` instead of digging the one it had just placed. When gravel comes back, the planner simply starts another `:redig`; there is no counter. In the sim, seed 4 rolls gravel four times and flint on the fifth dig, and the run replays to the same tick.

## Gotchas
- **Click reach is not pickup reach.** `redig-place` chooses a supported, clear cell immediately beside the feet, including a passage, and gives that cell to the child placement. With none, it retreats to fresh ground and tries placement again; it never cuts a placement niche. Issue #151's R3/14 and real runner 8 snapshots placed gravel through rock or round a corner, then walked into the intervening wall until three collects failed `:not-picked-up`. Both now collect gravel repeatedly and reach flint-and-steel within their kept windows.
- **A stair is pickup ground too.** The neighbouring cell may be one step up or down, not only level with the feet. Issue #349's R1/4 and R4/42 snapshots held gravel but retreated along stairs indefinitely because no same-height neighbour had headroom; the next placement search must accept a standable stair.
- **Exposed is not safe to dig.** `exposed-of-states` excludes blocks whose dig would expose a fluid (`intent/exposes-fluid?`), using the live terrain rather than permanently blacklisting them. Race 39037024176 runner 1 (issue #342) repeatedly walked to stone beside water, received `:unsafe`, and descended into another boxed pocket. A dry observation makes those blocks candidates again. Together with resetting turns after each completed stair, the kept snapshot reaches the furnace 363.3 seconds after its moment instead of failing `[:furnace :boxed]` after 54.15 seconds.
- **Lava that caught the bot is still coming.** Ore within `path/lava-trap-reach` (4) on the plane of a lava trap still in force (`:world/traps`, ten minutes) is left alone (`path/near-lava-trap?`): sim race 38844683780 (issue #230, world R2/9) fled lava falling from ten blocks up, then mined the iron two blocks off under the same flow and was caught again until the flee found no footing; and where it was caught, the mine retreats before it cuts a stair, which the flow followed down (`ore-beside-a-lava-trap-is-left-alone`).
- **A tunnel shows its floor too.** Live run 14 (local landing 2360,2360, 58fc6aa) held an ingot and needed flint, tunnelled for gravel at y 18 and walked over five gravel blocks in its own floor, none counted because a block a level under the feet was never reachable (`gravel-in-the-tunnel-floor-is-dug-and-picked-up-from-its-hole`).
- **A tunnel shows its head and its ceiling too.** Until a block at the head or overhead counted, a two-high tunnel showed ore only in the walls at its feet: batch iron-a2 run 4 tunnelled over a hundred blocks at y 16 without seeing an iron ore (`a-tunnel-shows-ore-at-the-head-and-overhead`).
- The depths are where the game makes each ore densest (iron peaks at y 16; coal is densest high up, around y 96, and absent below y 0, so its stairs stop at 40: at 0, the twelfth live run tunnelled thirteen minutes in deepslate for coal it never found): where a tunnel is cut. Iron's went 16, then 0 (the second live run waited at 16 with the only iron in sight out of reach), then back to 16 when tunnels came, since a tunnel at the peak shows the most.
- `:mine-stone` holds a wooden pickaxe by item, not by tag, as it always has: a bot that has only a stone pickaxe plans a wooden one before more cobblestone.
- Before rows named their blocks, the chain was tagged `:intent/for :stone` and the walk keyed its arrival off that; it is now the row's id and `:intent/pickup?`.
- **The surface over stone is ground, not canopy.** `surface-over` skips leaves: sim race 39550831606 world R4/25 (issue #85) walked to the top of a birch's canopy over its stone, every stair from there was refused for the leaves, and each level step was followed by the walk back (`the-surface-over-stone-is-the-ground-not-the-canopy-over-it`).
- **A boxed mine digs its filler.** Once a staircase died `:boxed` (`:plan/boxed-at`) and under `filler-want` (8) filler is carried, `filler-dig` walks to the nearest dirt whose dig exposes no fluid, tagged `:filler`, and the chain digs and collects it before another stair is cut. Real bot Clj_r15 at R1/30 (issues #342 and #65) stood on lake shores with one or two dirt to seal with (`a-mine-boxed-with-little-filler-digs-dirt-for-it-before-another-stair`).

## See also
- [[make-goals]] - how the planner reaches a mining row (its holds, then its provides).
- [[collect-intent]] - the last link.
