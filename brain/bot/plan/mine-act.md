---
title: Mine act
description: How the :mine act gets what a goal row's blocks drop - the rows as data (stone, coal, iron, gravel), the tool each needs, the drops a collect waits for, the walk that ends where the drop lands, and the stairs cut down to the row's depth when nothing is in sight - and how :redig gets flint from a held gravel.
type: reference
tags: [bot, plan, mining, goals]
aliases: [:mine, stone.clj, mine-next, :goal/blocks, :goal/depth, mine-stone, mine-coal, mine-iron, dig-gravel, none-in-sight, intent/pickup?, step-down?, :redig, flint-from-gravel, flint]
status: verified
lastUpdated: 2026-10-16
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
  - src/clojurecraft/stone.clj#defn stairs
  - src/clojurecraft/plan.clj#defn fail-intent
  - test/clojurecraft/stone_test.clj#a-boxed-descent-is-not-started-again-from-the-same-cell
  - src/clojurecraft/stone.clj#defn tunnel-heading
  - src/clojurecraft/stone.clj#defn deeper
  - test/clojurecraft/stone_test.clj#deeper-only-where-a-stair-can-be-dug
  - src/clojurecraft/stone.clj#defn retreat
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
  - src/clojurecraft/stone.clj#defmethod intent/run :redig
  - test/clojurecraft/sim_test.clj#one-gravel-is-placed-and-dug-again-until-it-drops-flint
  - test/clojurecraft/sim_test.clj#ore-is-mined-with-a-pickaxe-from-the-main-inventory
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

## How it works (`mine-next`)
1. A chain in flight continues: walk → dig → collect via `intent/chain-next`, tagged `:intent/for` the row's id, so another row's chain (a coal walk when iron is now wanted) is never continued as this one's dig.
2. A tool the row needs that only the main inventory holds is put in hand first, with an `:equip` intent (`intent/stowed-tool`). `tool-missing`: a block that drops only to its tool (`blocks/drops-need-tool?`, [[hardness-and-tools]]) with no such tool held waits `:no-pickaxe`; gravel drops to a hand and never waits.
3. `exposed-of-states`: the blocks of the row within 24 of the eye and 12 of its height, read from the loaded chunks through the block overlay, that the bot can dig from a cell it can stand in (`beside`: a horizontal neighbour at the block's level or a block lower, the block then at the head, or the cell under it, the block overhead, or a horizontal neighbour a block up when the dig leaves a hole one deep, `step-down?`: solid under the block and the two cells over it clear, so the drop lands in the hole and the collect steps down into it; never straight from above, where the drop would lie under the feet), nearest first (never memory: stone is most of the world and ore is in every chunk; [[memory-edge-cases]]). Of the three nearest, the first a route reaches (`intent/routable?`: the planner's route is not `:none`) is walked to (`:found`, or `:partial` only toward an unloaded chunk); one in a cave below with no way down is passed over. The fourth live iron-kit run walked at three such ores, each failing `:no-path` at once, and the third failure ended the plan. The walk carries `:intent/pickup? true`: it ends where the dug block's drop lands in the pickup box (`intent/arrived?`, the path's `:pickup` goal; [[walk-intent]]), and `:intent/step-down? true` when the stand is a block up, which lets the goal end there.
4. With none in sight, `stairs`: a staircase six down from the feet, never below the row's depth ([[stairs-down]]). A feet cell in `:plan/boxed-at` is skipped: a `:stairs-down` (or a `:tunnel`) died `:boxed` there (`fail-intent`; `:plan/blacklist` keeps only targets that failed), and another from it would die the same way. Full run ab3945e stood at y 32 over an aquifer, every stair and every further level step refused, and two more staircases from that cell boxed in under a second (`a-boxed-descent-is-not-started-again-from-the-same-cell`). The act then tunnels, goes deeper, retreats, or climbs out. At the depth, a `:tunnel` of `tunnel-length` (12) level steps along `tunnel-heading`: of the four, those with dry ground over the column a tunnel ahead first (`stairs/dry-top?`: gym batch iron-a8 run 2 tunnelled 260 blocks out under the sea and its stairs up met the seabed every way, `tunnels-and-climbs-keep-under-dry-ground`), then the one leading farthest from the landing, the first whose first level step is allowed and has rock to cut, so never back along an open tunnel nor straight at what refused the last. With none, `deeper`: stairs six more down, the row's depth notwithstanding (ore lies below it too), when a stair from here can be cut and dug at all (`stairs/level-safe?`: no cell of it bordering water or lava, which the dig refuses; gym batch iron-a8 run 3 was sent down stairs whose every cell bordered water and failed `:boxed` twice in a second, `deeper-only-where-a-stair-can-be-dug`), or cast, its floor laid from carried cobblestone (`stairs/lay`, [[stairs-down]]). With neither, `retreat`: out of the water first when the feet are in it (a `:swim-out` for `:retreat`, which ends itself on dry land), else a walk to the end of a route at least `retreat-distance` (12) away, where the rock is new. With no way away either, `climb`: stairs up out of the ground, `climb-stairs` (8) at a time, unless the feet are out already (`stairs/out?`); a stair up seals the water beside its cells and lays its step from carried cobblestone ([[stairs-down]]), which a level step does not, so it is the one cut out of a pocket an aquifer closed, and up top the search starts afresh. Only then a wait `:boxed`. A `:tunnel` that dies `:boxed` records its feet cell in `:plan/boxed-at` as a staircase does (`plan/boxing-kinds`), and `tunnel-heading` and `deeper` are nil there, as `stairs` is: each heading is judged by its first step alone, so a tunnel started again from the pocket dies the same way. Race 38515209959 (issue #105, four real bots at the flint and steel, tunnelling for gravel at y 34-40 into underground water with 300-500 cobblestone in hand): runner 3 bot Clj_r19 and runner 9 bot Clj_r18 swam out of the water that came in onto a cell every stair and tunnel from which was refused, with no route away along the flooded tunnel, and waited `:boxed` out; runner 3 bot Clj_r13 started three tunnels in a row from one pocket between two sheets of water, each `:boxed` a step or two on (`boxed-in-by-water-with-no-way-away-the-mine-climbs-out`). Gym batch iron-a6 run 3's tunnel at y 16 ran along an aquifer until water filled its floor, and it was sent down stairs that could not be cut, `:boxed` three times in a second. With the heading fixed to the farthest, gym batches iron-a3 and iron-a4 retried tunnels boxed in by an aquifer three times in a second and failed `:boxed`. A staircase shows little rock: the live runs that waited at the depth with nothing in sight failed there. The tunnel's walls are where the ore shows; the next pass sees it (`at-the-rows-depth-a-tunnel-finds-the-ore-its-walls-show`).
   A tunnel never heads back under the staircase just cut (`tunnel-heading`: with `:plan/last` a `:stairs-down`, its heading turned round is left out): its first steps would dig the floors of the stairs, the only way back up. Sim race 38839665459 runner 3 (issue #71, every seed of world R1/19 at 3f836ff) cut stairs west down to y 1 and tunnelled east under them; boxed beside an aquifer under gravel, its retreat found no route up the stairs it had undermined, every climb was `:boxed`, and the plan failed (`stone_test`, "never back under the stairs just cut").
5. The collect waits for one more of any item the row's blocks can drop (`drops-of-row`, every try of their loot tables: flint and gravel both for gravel).

## Flint: dig a held gravel again (`:redig`)
Gravel's loot table drops flint one time in ten, else the gravel itself. The `:flint-from-gravel` row needs one gravel and provides flint; its act starts a `:redig` intent, which runs three child intents in its own place (`link`, as stairs runs its digs): `:place` the held gravel (in a passage if need be, `:intent/passage-ok?`, since it is dug again at once), `:dig` what it placed, `:collect` with the row's drops (flint and gravel). One intent from placing to picking up matters: as three separate intents, the placing spent the gravel the row needs, and on the next tick the planner went for more gravel with `:dig-gravel` instead of digging the one it had just placed. When gravel comes back, the planner simply starts another `:redig`; there is no counter. In the sim, seed 4 rolls gravel four times and flint on the fifth dig, and the run replays to the same tick.

## Gotchas
- **Lava that caught the bot is still coming.** Ore within `path/lava-trap-reach` (4) on the plane of a lava trap still in force (`:world/traps`, ten minutes) is left alone (`path/near-lava-trap?`): sim race 38844683780 (issue #230, world R2/9) fled lava falling from ten blocks up, then mined the iron two blocks off under the same flow and was caught again until the flee found no footing; and where it was caught, the mine retreats before it cuts a stair, which the flow followed down (`ore-beside-a-lava-trap-is-left-alone`).
- **A tunnel shows its floor too.** Live run 14 (local landing 2360,2360, 58fc6aa) held an ingot and needed flint, tunnelled for gravel at y 18 and walked over five gravel blocks in its own floor, none counted because a block a level under the feet was never reachable (`gravel-in-the-tunnel-floor-is-dug-and-picked-up-from-its-hole`).
- **A tunnel shows its head and its ceiling too.** Until a block at the head or overhead counted, a two-high tunnel showed ore only in the walls at its feet: batch iron-a2 run 4 tunnelled over a hundred blocks at y 16 without seeing an iron ore (`a-tunnel-shows-ore-at-the-head-and-overhead`).
- The depths are where the game makes each ore densest (iron peaks at y 16; coal is densest high up, around y 96, and absent below y 0, so its stairs stop at 40: at 0, the twelfth live run tunnelled thirteen minutes in deepslate for coal it never found): where a tunnel is cut. Iron's went 16, then 0 (the second live run waited at 16 with the only iron in sight out of reach), then back to 16 when tunnels came, since a tunnel at the peak shows the most.
- `:mine-stone` holds a wooden pickaxe by item, not by tag, as it always has: a bot that has only a stone pickaxe plans a wooden one before more cobblestone.
- Before rows named their blocks, the chain was tagged `:intent/for :stone` and the walk keyed its arrival off that; it is now the row's id and `:intent/pickup?`.

## See also
- [[make-goals]] - how the planner reaches a mining row (its holds, then its provides).
- [[collect-intent]] - the last link.
