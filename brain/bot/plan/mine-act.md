---
title: Mine act
description: How the :mine act gets what a goal row's blocks drop - the rows as data (stone, coal, iron, gravel), the tool each needs, the drops a collect waits for, the walk that ends where the drop lands, and the stairs cut down to the row's depth when nothing is in sight - and how :redig gets flint from a held gravel.
type: reference
tags: [bot, plan, mining, goals]
aliases: [:mine, stone.clj, mine-next, :goal/blocks, :goal/depth, mine-stone, mine-coal, mine-iron, dig-gravel, none-in-sight, intent/pickup?, :redig, flint-from-gravel, flint]
status: verified
lastUpdated: 2026-10-11
verifiedAgainst: HEAD
sourceRefs:
  - src/clojurecraft/stone.clj#defn mine-next
  - src/clojurecraft/stone.clj#def drops-of-row
  - src/clojurecraft/stone.clj#defn tool-missing
  - src/clojurecraft/stone.clj#defn exposed-of-states
  - src/clojurecraft/intent.clj#defn routable?
  - test/clojurecraft/stone_test.clj#an-ore-no-route-reaches-is-passed-over
  - src/clojurecraft/stone.clj#defn stairs
  - src/clojurecraft/stone.clj#defn outward
  - test/clojurecraft/sim_test.clj#at-the-rows-depth-a-tunnel-finds-the-ore-its-walls-show
  - src/clojurecraft/intent.clj#defn arrived?
  - resources/clojurecraft/goals.edn#{:goal/id :mine-iron
  - test/clojurecraft/stone_test.clj#with-nothing-in-sight-the-stairs-stop-at-the-rows-depth
  - test/clojurecraft/sim_test.clj#coal-and-iron-are-mined-where-they-lie
  - src/clojurecraft/stone.clj#defn link
  - src/clojurecraft/stone.clj#defmethod intent/run :redig
  - test/clojurecraft/sim_test.clj#one-gravel-is-placed-and-dug-again-until-it-drops-flint
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
| `:mine-coal` | coal ore, deepslate coal ore | any pickaxe (`:tag/pickaxes`) | coal | 0 |
| `:mine-iron` | iron ore, deepslate iron ore | a stone pickaxe | raw iron | 16 |
| `:dig-gravel` | gravel | nothing | gravel | 40 |

## How it works (`mine-next`)
1. A chain in flight continues: walk → dig → collect via `intent/chain-next`, tagged `:intent/for` the row's id, so another row's chain (a coal walk when iron is now wanted) is never continued as this one's dig.
2. `tool-missing`: a block that drops only to its tool (`blocks/drops-need-tool?`, [[hardness-and-tools]]) with no such tool held waits `:no-pickaxe`; gravel drops to a hand and never waits.
3. `exposed-of-states`: the blocks of the row within 24 of the eye and 12 of its height, read from the loaded chunks through the block overlay, that the bot can stand beside at its own level, nearest first (never memory: stone is most of the world and ore is in every chunk; [[memory-edge-cases]]). Of the three nearest, the first a route reaches (`intent/routable?`: the planner's route is not `:none`) is walked to (`:found`, or `:partial` only toward an unloaded chunk); one in a cave below with no way down is passed over. The fourth live iron-kit run walked at three such ores, each failing `:no-path` at once, and the third failure ended the plan. The walk carries `:intent/pickup? true`: it ends where the dug block's drop lands in the pickup box (`intent/arrived?`, the path's `:pickup` goal; [[walk-intent]]).
4. With none in sight, `stairs`: a staircase six down from the feet, never below the row's depth ([[stairs-down]]); at the depth, a `:tunnel` of `tunnel-length` (12) level steps along `outward`, the heading that leads farthest from the landing, so tunnels never double back. A staircase shows little rock: the live runs that waited at the depth with nothing in sight failed there. The tunnel's walls are where the ore shows; the next pass sees it (`at-the-rows-depth-a-tunnel-finds-the-ore-its-walls-show`).
5. The collect waits for one more of any item the row's blocks can drop (`drops-of-row`, every try of their loot tables: flint and gravel both for gravel).

## Flint: dig a held gravel again (`:redig`)
Gravel's loot table drops flint one time in ten, else the gravel itself. The `:flint-from-gravel` row needs one gravel and provides flint; its act starts a `:redig` intent, which runs three child intents in its own place (`link`, as stairs runs its digs): `:place` the held gravel, `:dig` what it placed, `:collect` with the row's drops (flint and gravel). One intent from placing to picking up matters: as three separate intents, the placing spent the gravel the row needs, and on the next tick the planner went for more gravel with `:dig-gravel` instead of digging the one it had just placed. When gravel comes back, the planner simply starts another `:redig`; there is no counter. In the sim, seed 4 rolls gravel four times and flint on the fifth dig, and the run replays to the same tick.

## Gotchas
- The depths are where the game makes each ore densest (iron peaks at y 16; coal runs down to y 0): where a tunnel is cut. Iron's went 16, then 0 (the second live run waited at 16 with the only iron in sight out of reach), then back to 16 when tunnels came, since a tunnel at the peak shows the most.
- `:mine-stone` holds a wooden pickaxe by item, not by tag, as it always has: a bot that has only a stone pickaxe plans a wooden one before more cobblestone.
- Before rows named their blocks, the chain was tagged `:intent/for :stone` and the walk keyed its arrival off that; it is now the row's id and `:intent/pickup?`.

## See also
- [[make-goals]] - how the planner reaches a mining row (its holds, then its provides).
- [[collect-intent]] - the last link.
