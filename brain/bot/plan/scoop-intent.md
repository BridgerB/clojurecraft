---
title: Scoop intent
description: How the bot fills a bucket - the :scoop act choosing surface water in sight or a remembered pool (never cave water), hunting outward and downhill when none is known, and the :scoop intent that equips the bucket, aims at the water's surface and sends use-item, done only when the inventory shows the water bucket.
type: reference
tags: [bot, plan, water, bucket, intents]
aliases: [:scoop, scoop.clj, fill-bucket, water bucket, use-item, no-scoop, no-water, aim-point]
status: verified
lastUpdated: 2026-10-16
verifiedAgainst: HEAD
sourceRefs:
  - src/clojurecraft/scoop.clj#defn scoop-next
  - src/clojurecraft/scoop.clj#defn seen-source
  - src/clojurecraft/scoop.clj#defn from-below
  - src/clojurecraft/scoop.clj#defn overhead
  - src/clojurecraft/scoop.clj#defn boxed-here?
  - test/clojurecraft/scoop_test.clj#deep-under-water-the-bucket-is-filled-from-below
  - src/clojurecraft/scoop.clj#defn sources
  - src/clojurecraft/scoop.clj#defn surface-water?
  - test/clojurecraft/scoop_test.clj#a-pond-below-a-hill-is-surface-water
  - src/clojurecraft/scoop.clj#defn hunt-targets
  - src/clojurecraft/scoop.clj#a hunt that knows where the water is
  - src/clojurecraft/scoop.clj#def far-pool-radius 256
  - test/clojurecraft/scoop_test.clj#a-hunt-that-knows-a-far-pool-walks-toward-it
  - src/clojurecraft/scoop.clj#defn first-walk
  - src/clojurecraft/scoop.clj#defn climb
  - src/clojurecraft/scoop.clj#defn pit-climb
  - src/clojurecraft/scoop.clj#defn way-up
  - test/clojurecraft/scoop_test.clj#deep-in-a-pit-under-the-sky-the-bot-climbs-to-the-landings-height
  - test/clojurecraft/sim_test.clj#a-climb-from-a-pit-ends-at-its-height-not-at-the-open-sky
  - src/clojurecraft/scoop.clj#defn dig-out
  - src/clojurecraft/intent.clj#defn opening
  - src/clojurecraft/scoop.clj#defn tunnel-out
  - src/clojurecraft/scoop.clj#defn to-bank
  - "src/clojurecraft/stairs.clj#A cut toward the bank of water"
  - test/clojurecraft/scoop_test.clj#a-boxed-cut-toward-the-bank-gives-that-water-up
  - test/clojurecraft/scoop_test.clj#inside-a-hill-at-the-landings-height-the-bot-cuts-out
  - test/clojurecraft/scoop_test.clj#with-no-way-back-up-the-bot-cuts-stairs-up
  - test/clojurecraft/sim_test.clj#from-a-pit-below-the-water-the-bot-climbs-to-the-bank
  - src/clojurecraft/scoop.clj#defn sees?
  - src/clojurecraft/scoop.clj#defn ray-cells
  - src/clojurecraft/scoop.clj#defn clear?
  - src/clojurecraft/scoop.clj#defn surface-aim
  - src/clojurecraft/scoop.clj#defn centre-aim
  - test/clojurecraft/scoop_test.clj#a-ray-past-a-blocks-edge-is-a-wall
  - src/clojurecraft/scoop.clj#defn scoopable?
  - src/clojurecraft/blocks.clj#defn air?
  - test/clojurecraft/scoop_test.clj#a-plant-on-the-ray-is-a-wall
  - test/clojurecraft/sim_test.clj#the-models-bucket-ray-stops-at-a-plant
  - test/clojurecraft/scoop_test.clj#water-the-eyes-cannot-reach-is-no-target
  - test/clojurecraft/scoop_test.clj#with-no-water-known-the-hunt-walks-out-and-downhill
  - src/clojurecraft/scoop.clj#defn aim-point
  - src/clojurecraft/scoop.clj#defmethod scoop-stage :sent
  - src/clojurecraft/place.clj#defn equip
  - src/clojurecraft/sim.clj#defn scooped
  - test/clojurecraft/scoop_test.clj#the-act-scoops-in-reach-walks-to-water-in-sight-and-refuses-cave-water
  - test/clojurecraft/scoop_test.clj#a-scoop-the-server-never-answers-fails-no-scoop
  - test/clojurecraft/sim_test.clj#an-empty-bucket-is-filled-at-a-pond-from-its-bank
related:
  - "[[bot/plan/_moc|Plan]]"
  - "[[sib-bucket-water-find]]"
  - "[[mc-bucket-use-item]]"
  - "[[memory-sightings]]"
---

# Scoop intent

The `:fill-bucket` row needs an empty bucket, holds a pickaxe and keeps a spare (`:goal/holds {:tag/pickaxes 1}`, `:goal/spares {:tag/pickaxes 1}`, for the stairs up out of a mine: a stone pickaxe lasts 131 blocks and a climb of fifty stairs digs about 150; gym batch iron-a6 runs 2 and 5 broke theirs climbing out with no table near and no wood left, and failed `:no-log` and `:no-path`; when it held two, gym batch iron-a7 runs 1-3 broke one deep in the mine with no wood left and waited `:no-log` for another with a working one in hand, so the spare is made only from what is held and near, [[make-goals]]) and provides a water bucket; its act is `:scoop`, which goes out like a mine, so the placed table within reach is taken along first (`make/take-table`, [[make-goals]]; race 38477665442 bots Clj_r19, Clj_r23 and Clj_r06 left theirs at the furnace and could not make the pickaxe the climb wore out). Without the hold, a pickaxe broken on the way down was never made again: gym batch iron-a3 run 2 failed its stairs up `:no-pickaxe` three times.

## Where the water is (`scoop-next`)
1. `sources`: the plain water sources (`blocks/water-base`: not a waterlogged block) open to the air above it (`surface-source?`) in the loaded terrain, within 24 of the eye, surface water (`surface-water?`: no more than `memory/surface-water-below` (5) under the eye, or deeper but under the open sky, `terrain/under-sky?`; gym batch solid-b1 run 9 stood on a hill at y 70 over ponds at y 62, the depth rule alone refused every one and it waited `:no-water`, `a-pond-below-a-hill-is-surface-water`), not blacklisted, nearest first. The nearest in reach (4 blocks, eye to centre) and in sight (`sees?`: every cell on the ray from the eye to its surface before it loaded, and air or water): `{:intent/kind :scoop :intent/target src}`; else a walk to the bank of the first of the three nearest that a route reaches (`first-walk`, `intent/routable?`). A scoop walk ends beside the source, the feet level with the cell over it or a block lower (`intent/arrived?` and `walk-goal`, path's `:pickup` goal at that cell, `scoop-range` 1), where nothing stands between the eyes and its surface. Live run 10 came up its stairs into a pit two below a pond's surface: the water was in reach but behind the pit wall, in reach meant no walk, and it waited `:no-water` (`from-a-pit-below-the-water-the-bot-climbs-to-the-bank`).
2. With none in sight, a walk to the first of the three nearest remembered pools within 96 a route reaches (`pools-near` leaves out a pool in a loaded chunk that is not a surface source: memory's pool is the highest source with no water above it, which can be under stone), the same depth rule ([[memory-sightings]]): one fact per chunk, so a far pond is known without a fact per water cell.
3. Deep under the landing (`deep?`), the water down there before any climb: `seen-source`, the nearest water source within reach not blacklisted, that the eyes see into (`centre-aim`: its centre, else a point a quarter block off it along an axis, with a clear ray), whatever is over it, scooped with `:intent/aim` there, as the cast scoops its water from the side; else `from-below`, a source over the feet's column under at most `bed-depth` (2) blocks of rock over the head (`overhead`, flowing water between passed): the rock dug bottom first (`:intent/exposes-ok?`, the dig may let the water in), then the source scooped from below, the bucket's ray passing the water falling in. Sim race 38662908438 (issue #61, 115 runs over real and sim rounds): climbs for water under a lake or the sea, no shore within 48 columns, stepped and dug until `:boxed`, one a room of 150 cells at y 43 under a lake bed two blocks thick, the water it needed over its head; another stood in a flooded pocket with a source in reach. A climb out to the surface that is `:boxed` (`stairs/box`, `:intent/for :surface`) ends instead of failing, its cell put in `:plan/boxed-at`, and no climb, pit climb or dig-out is cut from a boxed cell again (`boxed-here?`; the gather's `wood/climb-out` likewise), so the hunt chooses again from where it stands (`deep-under-water-the-bucket-is-filled-from-below`).
4. Deep in the mine (feet more than `underground-depth`, 8, under the landing `:plan/go-at`, and the sky not open over them), with no water seen that `to-bank` could cut toward (the climbs out are for water not seen: sim race 38669750355, issue #69, landed on hills at y 100 and y 73, cut a stair down toward a pond, was then deep under the landing with rock over the head, climbed back up its opening, and did it again, sixteen hundred times in ninety minutes; `pit-climb` gives way the same), out first (`climb`, `:intent/for :surface`): a walk back to the landing when a route reaches it, else a walk up the air already dug (`intent/opening`, `:intent/for :opening`), else `:stairs-up` to the landing's height, `stairs/climb-to` a length at a time ([[stairs-down]]). From down there no water is in sight, and a hunt looks for ground near the bot's own height. Gym batch iron-a1 runs 3 and 5 made the bucket deep in the mine and failed `:no-water` (`deep-in-the-mine-with-no-water-the-bot-climbs-out-first`); the siblings climbed out first too ([[sib-bucket-water-find]]). The way down is often no way back up: batch iron-a2 runs 2 and 5 had mined an ore out of the floor under a stair, or dug a niche under one, and waited `:no-water` at y 18 and 24 with no route out (`with-no-way-back-up-the-bot-cuts-stairs-up`).
5. Else a hunt (`hunt-targets`, the first a route reaches): of eight bearings, the ground 40 blocks out (the highest cell the feet could stand in, 16 over to 24 under the eye, in a loaded chunk, under the open sky: race 38477665442 bot Clj_r15 hunted into a cave floor under a roof, climbed back out (`intent/opening`) to the pit it had hunted from, and went between the two for an hour until the race ended), leaving out any nearer the landing (`:plan/go-at`) than the bot is now, lowest first, since water lies low; a walk there tagged `:intent/for :water-hunt`. The chunks it loads are remembered as they arrive, pools included, so the next pass finds the water in sight or remembered. Nothing is stored for the hunt: going outward from the landing is what keeps it from walking back over dry ground. This is the siblings' directional hunt, downhill first ([[sib-bucket-water-find]]). A hunt that knows where the water is (`hunt`) goes toward it instead: with a surface pool remembered within `far-pool-radius` (256; `pools-near` with that radius, so a pool past the walk's 96 or one no route reaches), of every target, homeward ones too (`hunt-targets` with `homeward?`, as the wood hunt does, [[gather-chain]]), only those that land nearer that pool than the feet are now, nearest it first: each hop closes on it, so none undoes the last. Sim race 38806318231 runner 1 (issue #60, every seed of world R2/19 at 76010d2, 75 runs in 16 batches) mined 180 blocks from its landing, then hunted west, outward and downhill, away from surface pools a hundred blocks east it remembered, to the captured world's edge, and waited `:no-water`; runner 8 (world R4/29) climbed out of its mine in the world's corner with pools 150 blocks toward the landing and no outward bearing left (`a-hunt-that-knows-a-far-pool-walks-toward-it`). Both stood at the edge of a captured world, where a real server would have loaded more ground; walking toward water already remembered is the better hunt on any server.
6. With no hunt a route reaches and the feet deep under the landing (`deep?`, more than `underground-depth` under it), the open sky over them notwithstanding, `pit-climb`: the same `way-up` as the climb (a walk home, the opening, stairs up to the landing's height), its stairs `:intent/to-height?`, ending at that height alone, since the floor of a ravine under the sky is `stairs/out?` the tick they start. Race 38477665442 (issue #60, 19 real bots) had four bots stand at y 6 at the bottom of one ravine, sixty under their landing, the sky open over them, no hunt with a route and nothing to tunnel toward, and wait `:no-water` out (`deep-in-a-pit-under-the-sky-the-bot-climbs-to-the-landings-height`, `a-climb-from-a-pit-ends-at-its-height-not-at-the-open-sky`).
7. With no hunt a route reaches and water seen (the nearest surface source in sight or remembered pool), `to-bank`, `:intent/for :bank`, toward its bank (the cell over it): `:stairs-up` to that height when it is higher than the feet (`:intent/to-height?`, `stairs/climb-to` a length at a time), `:stairs-down` toward it (`heading-to`, the heading most toward it) to that height when lower, `stone/stair-depth` at a time, and at that height a `:tunnel` of `tunnel-out-length` along that heading. Sim race 38657453715 (issue #60) had bots in a trench of their own digging two below a pond's surface, the water in reach but over the eyes; on ravine floors at y 8 and y 32 under pools remembered at y 62; on a mountain ledge at y 80 over pools at y 62; and at y 63 under a lip of rock forty blocks from pools; nothing had a route. The cut names the water it heads for (`:intent/target`); a cut that is `:boxed` ends rather than failing and puts that water in `:plan/blacklist` (`stairs/box`), so the next look chooses other water, or none and a climb out, never the same cut three times: race 38805762352 runner 2 (issue #61, at 76010d2) had bot Clj_r18 at y 30 in a cave, 80 under its landing, tunnel toward cave water 15 blocks off across the cave's floor, and bot Clj_r22 tunnel at y 63 from the top of the steps its climb had laid in a cavern toward pools 55 blocks off through the air; each tunnel was `:boxed` three times in a row, the cut keeping the climb out from being chosen, and the plan failed (`a-boxed-cut-toward-the-bank-gives-that-water-up`).
8. With no hunt a route reaches and no open sky over the feet, `dig-out`: walk up the air already dug (`intent/opening`), else stairs up until out of the ground (`:intent/to-y` the top of the world, so only `stairs/out?` ends them), whatever the height. Live run 17 climbed out of its mine to the landing's height, y 65, which 145 blocks east of the landing was inside a hill; a pond lay 22 blocks off with no route to it nor to any hunt, and it waited `:no-water` (`inside-a-hill-at-the-landings-height-the-bot-cuts-out`). Full run 4528031 had no landing, cut that stair from y 17, and the only pickaxe broke at y 49.
9. With no hunt a route reaches even under the open sky, `tunnel-out`: a `:tunnel` of `tunnel-out-length` (8) level steps at the feet's height along `stone/tunnel-heading` (a heading it can cut, away from the landing). Gym batch solid-b1 run 13 came up its stairs into a pit under the sky, leaves and rock round it with no room to jump out, and waited `:no-water` (`in-a-pit-with-no-way-out-the-bot-tunnels-out`).
10. Else `{:plan/wait :no-water}`. The issue's two moments are kept under `test/issues/60` and played on by the issue check.

The hunt came from gym batch `water-a1` (forest landings, set A): 1 pass in 5, and all four failures waited `:no-water` about 33 s in, with no water in sight within 24 and none remembered within 96.

## The intent
1. `:equip`: `place/equip`, shared with the place intent: the bucket into the held slot (select it in the hotbar, or swap it there with a window-0 click).
2. `:aim`: look at `aim-point`, the centre of the source's surface (0.85 up the block, a source being 8/9 of a block tall), and after 3 still ticks and 300 ms send `use-item` with that look and a fresh sequence. A target that is no longer a surface source fails `:target-gone`. Aimed at the block's centre from the bank, the ray clipped the bank block's corner and the sim's pond test failed three scoops in a row.
3. `:sent`: done when the inventory holds one more water bucket than when the intent began; 3 s without, aim again; the third unanswered scoop fails `:no-scoop`, and the planner's failure path blacklists the source ([[goals-and-intents]]).

No flag stands for "the bucket is in use" (steve's `usingHeldItem`): only the inventory says the scoop worked.

## In the sim
`sim/scooped` casts a ray from the reported eye along the packet's look, in 0.05 steps up to 4.5: the first water source fills the held bucket (the source becomes air), any block but air, water or lava first stops it (`the-models-bucket-ray-stops-at-a-plant`). The pond test fills a bucket from hotbar slot 4, from the bank, without entering the water.

## Gotchas
- **No walk already arrived.** `first-walk` leaves out targets whose walk has already arrived (for water, standing at its bank): when it left out targets in reach and the arrival rule was reach, gym batch water-a3 run 5 walked to a remembered pool under stone, in reach, and the walk ended the tick it began and began again every tick for four minutes until the trial timed out (`a-remembered-pool-under-stone-is-no-walk`).
- **A route that only half gets there is no route.** `intent/routable?` accepts a `:partial` route, or a `:none` one with waypoints (the search ran out of loaded terrain, not of ways), only toward a target in a chunk not loaded yet: gym batch solid-b1 run 1, 146 blocks from its landing under the sea, had its own tunnel home end at the edge of what was loaded, and cut stairs up under the sea until `:boxed` five times (`the-way-home-runs-out-of-loaded-terrain`); in loaded terrain a search that spent its budget short of the target meant walks failing `:no-path` again and again (water-a3 run 3).
- **A ray past a block's edge is a wall.** The game casts the bucket's ray exactly; samples of the line every 0.05 step past a block's edge the line grazes. So the ray must clear every cell it passes through or within `ray-margin` (0.03) of (`ray-cells`, `clear?`), and the eyes aim at the surface's centre or, when its ray is not clear, at a point `aim-offset` (a quarter block) off it (`surface-aim`; `centre-aim` for a scoop through a cell's side), and `sees?` is that some point is clear. Race 38759618225 runner 4 (issue #83, at 76010d2): bots Clj_r15, Clj_r38, Clj_r61 and Clj_r78 held the bucket and sent nine scoops each along lines within 0.03 of an edge of sand by a pond, dirt and sand under a lake, stone and granite in a cave (one stood at a corner of its cell, the line to the centre running down the edges of four columns); the server filled nothing, while the model's sampled ray filled the bucket, so only real runs failed. And `seen-source` read no blacklist, so after a scoop failed `:no-scoop` the next intent chose the same source, three in a row; it, and `overhead` for `from-below`, now leave blacklisted sources out (`a-ray-past-a-blocks-edge-is-a-wall`).
- **A plant on the ray is a wall.** The game clips a bucket's ray against block outlines (`ClipContext.Block.OUTLINE`), and short grass, sugar cane, kelp and seagrass all have one: race 38477665442 (issue #83) had seven bots at beaches at y 63, where sugar cane grows on the sand by the water, send three scoops each that the server acked and never filled. `scoopable?` wants `blocks/air?` over the source (not merely nothing solid), and `sees?` wants every cell on the ray air or water (`a-plant-on-the-ray-is-a-wall`).
- **Water under stone is no target.** The third live iron-kit run (local landing 2360,2360) reached the bucket and then scooped three times at aquifer sources beside it at y 23, each with stone directly above: the server acked every `use-item` and filled nothing, since the ray met the wall before any water. The rule had been "no water above"; it is now air above, and a source in reach must be in sight.

## Limits
The sim treats a water cell as wholly water; the game clips the ray against the fluid's 8/9 shape. Scoops on the real server have filled the bucket (gym batch iron-a3 run 3, live run 13; [[mc-bucket-use-item]]).

## See also
- [[sib-bucket-water-find]] - the rules this follows.
