---
title: Stronghold
description: How the bot finds the stronghold (issue #265) - an eye of ender's flight read from the entity it becomes, a throw as a fact, and two throws' lines crossed on the plane by a pure function that answers nothing when they are parallel or cross behind a throw.
type: reference
tags: [bot, plan, stronghold, eyes, goals]
aliases: [stronghold.clj, triangulate, :stronghold/throws, :throw/from, :throw/dir, min-crossing, :throw-eye, :eye-throw, :thrown?, :no-eye, :eye-unmoved, throw, bearing, :stronghold, :frames-seen?, stronghold-next, second-spot, crossing, hop, explore, :near-stronghold, :no-way, :no-frame, :truth/at, :frame, :portal-room, :in-portal-room?, :intent/dry?, dry?, portal-room, to-room, dry-start, dry-cell?, stairs-toward, tunnel-spent?, room-over, deep, stair-leg, shore, far-start, walked-ground, far-reach, last-done, tunnel-gain, :intent/from-feet, downhill, downhill-reaches, downhill-drop]
status: verified
lastUpdated: 2026-10-18
verifiedAgainst: HEAD
sourceRefs:
  - src/clojurecraft/stronghold.clj#def min-crossing 0.035
  - src/clojurecraft/stronghold.clj#The 2-D cross product
  - src/clojurecraft/stronghold.clj#defn triangulate
  - test/clojurecraft/stronghold_test.clj#two-throws-from-apart-cross-at-the-stronghold
  - test/clojurecraft/stronghold_test.clj#parallel-throws-say-nothing
  - test/clojurecraft/stronghold_test.clj#a-crossing-behind-a-throw-is-none
  - test/clojurecraft/stronghold_test.clj#exact-throws-from-any-two-places-cross-at-the-stronghold
  - src/clojurecraft/stronghold.clj#def throw-pitch -20.0
  - src/clojurecraft/stronghold.clj#def follow-ms 2000
  - src/clojurecraft/stronghold.clj#defn own-eye
  - src/clojurecraft/stronghold.clj#defn record-throw
  - src/clojurecraft/stronghold.clj#defmethod throw-stage :seek
  - src/clojurecraft/stronghold.clj#defmethod throw-stage :follow
  - src/clojurecraft/stronghold.clj#defmethod intent/run :throw-eye
  - src/clojurecraft/stronghold.clj#defmethod plan/next-intent :throw
  - "resources/clojurecraft/goals.edn#{:goal/id :eye-throw"
  - src/clojurecraft/gym.clj#defmethod verdict :bearing
  - test/clojurecraft/stronghold_test.clj#an-eye-is-thrown-followed-and-its-line-kept
  - test/clojurecraft/stronghold_test.clj#no-eye-seen-fails-the-throw
  - test/clojurecraft/gym_test.clj#the-throw-trial-holds-the-throws-line-to-the-located-stronghold
  - src/clojurecraft/stronghold.clj#def drop-grace-ms 1000
  - src/clojurecraft/stronghold.clj#defn eye-drop
  - src/clojurecraft/stronghold.clj#defn frames
  - src/clojurecraft/stronghold.clj#defn second-spot
  - src/clojurecraft/stronghold.clj#defn crossing
  - src/clojurecraft/stronghold.clj#defn hop
  - src/clojurecraft/stronghold.clj#defn picked-up
  - src/clojurecraft/stronghold.clj#defn explore
  - src/clojurecraft/stronghold.clj#defn way
  - src/clojurecraft/stronghold.clj#defn off-line
  - src/clojurecraft/stronghold.clj#defn spots
  - src/clojurecraft/stronghold.clj#defn side
  - src/clojurecraft/stronghold.clj#defn to-room
  - src/clojurecraft/stronghold.clj#defn dry-cell?
  - src/clojurecraft/stronghold.clj#defn dry-start
  - src/clojurecraft/stronghold.clj#defn far-start
  - src/clojurecraft/stronghold.clj#defn walked-ground
  - src/clojurecraft/stronghold.clj#def far-reach 80
  - src/clojurecraft/stronghold.clj#def tunnel-gain 4.0
  - src/clojurecraft/memory.clj#defn last-done
  - src/clojurecraft/stronghold.clj#defn downhill
  - src/clojurecraft/stronghold.clj#def downhill-drop 4
  - test/clojurecraft/stronghold_test.clj#a-mountain-is-walked-down-toward-the-frame-before-the-stairs
  - test/clojurecraft/stronghold_test.clj#the-portal-room-is-reached-down-a-mountain-against-the-model
  - src/clojurecraft/stronghold.clj#defn stairs-toward
  - src/clojurecraft/stronghold.clj#defn tunnel-spent?
  - src/clojurecraft/stronghold.clj#def room-over 1
  - src/clojurecraft/stronghold.clj#def deep 8
  - test/clojurecraft/stronghold_test.clj#the-next-throws-spot-is-on-the-side-the-feet-are-on
  - test/clojurecraft/stronghold_test.clj#a-way-swims-what-no-walk-crosses-and-tunnels-where-no-route-goes
  - src/clojurecraft/stronghold.clj#defn stronghold-next
  - src/clojurecraft/memory.clj#def landmark-kinds
  - "resources/clojurecraft/goals.edn#{:goal/id :stronghold"
  - src/clojurecraft/gym.clj#defmethod land! :near-stronghold
  - src/clojurecraft/gym.clj#defn truth-cmd
  - test/clojurecraft/stronghold_test.clj#a-throw-waits-for-the-eyes-drop-after-the-removal
  - test/clojurecraft/stronghold_test.clj#the-search-throws-walks-off-the-line-and-throws-again
  - test/clojurecraft/stronghold_test.clj#two-crossing-throws-walk-to-the-crossing-and-look-round-it
  - test/clojurecraft/stronghold_test.clj#a-remembered-frame-is-the-stronghold-found
  - test/clojurecraft/stronghold_test.clj#the-stronghold-is-found-by-throws-and-walks-against-the-model
  - src/clojurecraft/stronghold.clj#defmethod plan/done-by :in-portal-room?
  - src/clojurecraft/stronghold.clj#defmethod plan/next-intent :portal-room
  - src/clojurecraft/fortress.clj#defn dry?
  - "resources/clojurecraft/goals.edn#{:goal/id :portal-room"
  - test/clojurecraft/fortress_test.clj#an-overworld-step-opens-no-cell-by-water
  - test/clojurecraft/stronghold_test.clj#the-portal-room-is-tunnelled-to-once-a-frame-is-remembered
  - test/clojurecraft/stronghold_test.clj#the-portal-room-is-reached-against-the-model
related:
  - "[[bot/plan/_moc|Plan]]"
  - "[[sib-end-and-dragon]]"
  - "[[entities]]"
  - "[[roadmap-issues]]"
  - "[[fortress-tunnel]]"
---

# Stronghold

The one stage of the run no sibling has proven: steve's finder throws one eye and reports success after three seconds ([[sib-end-and-dragon]]), ruststeve never needed one. Issue #265 splits it from the roadmap's `:end-and-dragon` problem into its own row of `problems.edn` (`:stronghold`, goals `:eye-throw`, `:stronghold`, `:portal-room`).

## What an eye says
An eye of ender used in the overworld finds the nearest stronghold (the game's `EnderEyeItem.use`, `findNearestMapStructure` over the `eye_of_ender_located` structures, the same search `locate structure` makes) and flies from the thrower's middle toward it: twelve blocks along the line on the plane and eight up when the stronghold is farther, the stronghold itself when it is nearer (`EyeOfEnder.signalTo`). Its way on the plane is straight all along, so two positions of the eye entity a moment apart give the line exactly, up to the server's rounding of a move (1/4096 block). The eye is an entity like any other ([[entities]], issue #258 keeps every one).

## A throw is a fact
A throw is kept on the world as `{:throw/from [x y z] :throw/dir [dx dz] :throw/at ms}` in `:stronghold/throws` (oldest first): where the eye was first seen, the unit way it flew on the plane, and when. It is never cleared: what an eye said stays true across a death.

## The throw
`{:intent/kind :throw-eye}` (`stronghold.clj`, stages of `throw-stage`) stands still throughout:

1. `:equip` puts an eye in the hand (`place/equip`).
2. `:look` looks up to `throw-pitch` (-20 degrees) along the yaw it has and stands `look-ms` (300 ms), so the pose reaches the server before the use. Never down: a use-item looking at an end portal frame fills the frame instead of throwing.
3. `:use` sends one `use-item` carrying the look.
4. `:seek` waits for the eye: the next `eye_of_ender` entity added at or after the use within `seen-within` (3 blocks) of the bot's middle (`own-eye`). None within `answer-ms` (3000 ms, stretched by the server's slowdown) fails `:no-eye`: in the Nether or the End the server throws nothing.
5. `:follow` keeps the eye's last place in `:intent/eye-pos` each tick; `follow-ms` (2000 ms) after it was seen, or at once if the server removes it sooner, the line from `:intent/eye-from` to there is recorded as a throw (`record-throw`; under `min-flight`, one block, it fails `:eye-unmoved`). Once the server removes the eye (it drops as an item four throws in five, or shatters, at 80 ticks) the intent keeps `:intent/gone-at` and is done when the dropped eye lies on a floor where it ended (`eye-drop`: within the drop radius of its last place brought down to the feet's height, once the item has fallen to the ground) or `drop-grace-ms` (2000 ms) later: the removal and the item's fall take about a second, and the planner picks the item up only if it is on the ground when the intent ends.

## The throw trial
The `:eye-throw` row (`--until throw`, priority 21, an aside, overworld only) is done by `:thrown?` (a throw on record) and planned by `:throw`: a `:throw-eye` with an eye held, else wait `:no-eye`. The gym row `throw` gives two eyes on a surface landing at noon and judges with a new kind of truth: a `:truth/check` keyword picks a `gym/verdict` method instead of the pattern, and `:bearing` reads the located stronghold out of `locate structure minecraft:stronghold` run at the landing and passes when the RESULT's first throw (`:throws`, added to the RESULT once a throw is kept) is within `gym/bearing-within` (10 degrees) of the way there.

## The crossing
`stronghold/triangulate a b` crosses the two throws' rays on the plane and returns `[x z]`, a pure function of the two facts. It answers nil when:

- the lines are nearly parallel: the sine of their angle is under `min-crossing` (0.035, two degrees). Two eyes thrown along one line say nothing about how far the stronghold is, so the second throw must come from off the first line;
- the crossing is behind either throw: an eye never flies away from its stronghold.

Tests: a crossing found from either throw first, parallel and one-degree lines answering nothing, a crossing behind one or both throws answering nothing, and a property over 300 generated strongholds and throw points that every pair of exact throws far enough from parallel names the stronghold.

## The search
The `:stronghold` row (`--until stronghold`, priority 22, an aside, overworld only) is done by `:frames-seen?`: an end portal frame on record (`frames`, `memory/positions-now` of every frame state). `end_portal_frame` is one of `memory/landmark-kinds` now, and a column's chunk data holds every block in it, so the portal room is remembered the moment its chunk loads, however deep it lies: no corridor needs walking to find it. Its plan, `stronghold-next`, in order:

1. the eye the last throw dropped (`picked-up`: the last intent a `:throw-eye` done, an eye lying where it ended) picked up with a `:collect-drops`;
2. no throw yet: a throw (`throw-now`; with no eye held, wait `:no-eye`);
3. a crossing (`crossing`: the latest throw crossed with each earlier one, the widest kept) the feet are not within `there` (16 blocks) of: a way toward it (`way`, below) at `tunnel-y` (30);
4. at the crossing with the last throw made more than `rethrow-beyond` (48 blocks) off: a throw from here, whose line crosses the first at a wide angle;
5. at the crossing, thrown from near it already: walks round it (`explore`: hops toward eight points `explore-reach`, 64 blocks, round it, from a heading the clock picks), each loading chunks further out; none reachable waits `:no-frame`;
6. throws that do not cross (one, or parallel): a throw once the feet are `min-offset` (32 blocks) off the latest's line (`off-line`), wherever the way got to; else a way toward one of its two `spots` (`second-spot`: `travel`, 64, blocks from it along its line turned `travel-turn`, 60 degrees, either side; the one on the side of the line the feet are on first once they are `on-line`, 4 blocks, off it, so a way that turned back from one spot does not swing to the other and back, as batch stronghold-265b run 2 did twelve times across a lake), at the feet's height.

A way (`way`) toward a far point is, in order: a hop (`hop`: a `:walk` to a cell a body stands in, `stand-in`, within 12 of the feet's height, 32 blocks along the way, or turned by 30, 60 or 90 degrees either side when straight on has no route, `intent/routable?`, never a cell a walk failed at, `:plan/blacklist`); with open water toward the point (`wood/water-toward?`), a swim across it (swim's `:cross`, done on the far side's dry land); else a dry tunnel toward it at the height asked (the fortress's `:tunnel-to` with `:intent/dry?`) when its first step can be taken; none waits `:no-way`. A walk to somewhere hundreds of blocks off is beyond a route's node budget. Gym batch stronghold-265a (plan run 38927120252, probe 38929010443) had only hops 150 blocks off at 45 degrees: two landings stood on a lake's shore with the stronghold across it, every hop ending in the water, and one on a mountainside walked at one cell twelve times, each walk failing :no-path; all three waited :no-way. The throw row passed 5/5 in the same batch.

The gym row `stronghold` lands the bot 250 blocks (`gym/stronghold-off`) from the stronghold the server locates from the landing, on the line through it, on dry ground (`land! :near-stronghold`, `fixture/land-at!`), with six eyes, an iron sword, pickaxe, helmet, chestplate and food; it is judged by `execute if block {at} minecraft:end_portal_frame`, where a truth row's `:truth/at` names a path into the RESULT whose `[x y z]` fills `{at}` (`gym/truth-cmd`): the RESULT names the nearest frame remembered as `:frame`.

Against the model (#260's eyes, `sim/init :stronghold`), `the-stronghold-is-found-by-throws-and-walks-against-the-model` plays the whole search: a frame deep in stone 113 blocks off, columns streamed two chunks round the player, six eyes; a throw, the walk off the line, a second throw, the walk to the crossing, the frame seen as its chunk loads.

## The portal room
The `:portal-room` row (`--until portal-room`, priority 23, an aside, overworld only) is done by `:in-portal-room?`: the feet cell's centre within `fortress/reach` (6) of the nearest remembered frame. Its plan (`to-room`), in order:

1. in water, out to the nearest bank first (swim's `:swim-out :retreat`);
2. more than `deep` (8) over the frame, under the open sky, not once the stairs or the tunnel have begun, and not standing dry, a walk to a dry start (`dry-start`: the nearest cell on the plane within `start-reach`, 16, a body stands in with no water or lava within `shore`, 2, of it from three under to one over, `dry-cell?`, not one a walk failed at, one a route reaches). A shore's every stair opens a cell by water: batch stronghold-265c's portal-room run 2 swam out onto the lake's bank over the frame and had every stair refused there, `:boxed`; batch 265b's run 2 the tunnel's every first step from the bank, `:blocked`. With none within `start-reach` and the open sky over the feet (a lake, not a cave by an underground one), a swim (swim's `:cross`) to the nearest dry cell farther off along eight bearings, out to `far-reach` (80, inside the server's six chunks of view) in a column whose top is ground (`far-start`), else back to where the last walk that arrived ended, within `hop-rise` of the feet's height (`walked-ground`, `memory/last-done`), never the stairs from a block in a lake, nor once the way down has begun (the last intent the stairs or the tunnel: batch stronghold-room-d's run 2 swam back up from its first leg of stairs): batch stronghold-room-b's run 2 ended its swim toward the crossing on a block in the lake over the frame, nothing dry within 48 of it (probe 39134431529), and every stair from it ran into the water, `:boxed`;
3. standing dry under the open sky with ground `downhill-drop` (4) or more under the feet toward the frame that a route reaches, `downhill-reaches` (32, 16, 8) blocks along the way, the farther first, turned as a hop turns: a walk down to it (`downhill`). A mountain's slope falls faster than a staircase's one in one, so stairs cut from it leave it into the air and lay a floor every stair over the valley until the floor would be a bridge over the valley's water (`stairs/over-fluid?`), where every stair is refused: batch stronghold-room-e's run 3 cut six legs from y 120 and left the western slope at y 83 into the open air over a flooded valley, laid a floor a stair down to y 72, fifteen over the water, and had every stair refused there and every level step lay one more block of causeway, 32 east, `:boxed` three times (probes 39158899900, 39159213076; the batch d and 265c mountain failures were the same slope);
4. more than `deep` over the frame, or after a tunnel that took every step it had without arriving and ended less than `tunnel-gain` (4) nearer the frame than it began (`tunnel-spent?`, the tunnel carrying `:intent/from-feet`; batch stronghold-room-d's run 1 had the stairs cut after every tunnel that was on its way, away from the frame into the rock, and walked back up them for fifteen minutes), a staircase down toward it (`stairs-toward`: `:stairs-down` heading along the axis with the farther way to go) a leg of `stair-leg` (8) at a time, so each leg turns toward the frame, never under `room-over` (1) over it. The stairs lay a floor over a cave from the cobblestone carried and turn where water is ([[stairs-down]]); the tunnel steps down only onto a floor: batch 265c's run 3 stood over the frame at y 84 with a cave under it, every step down refused for the air under the next cell, and stepped level between two cells 32 times a tunnel for eighteen minutes to the timeout;
5. the fortress's `:tunnel-to` to the frame ([[fortress-tunnel]]: one step at a time, each a plan of ops, never opening a cell beside lava, never digging a frame, which `fortress/unbreakable` holds) with `:intent/dry? true`: `fortress/dry?` refuses a step that opens a cell that is water or has water beside it, since the overworld's way down runs through aquifers and under oceans;
6. no first step of that tunnel from the feet, still more than `room-over` (1) over the frame: the stairs; else the wait `:no-way`.

With no frame remembered, the search above. No corridor is walked: the frame is the target from wherever the bot stands, down through the ground and the stronghold's walls. The gym row `portal-room` lands as `stronghold` does, with two pickaxes and cobblestone for the floors, and is judged by the frame at the RESULT's `:frame` and the bot within 7 of it, asked at the frame's coordinates (`execute positioned {at} if entity @a[name=...]`), never as the bot. Against the model, `the-portal-room-is-reached-against-the-model` lands two blocks from a pond with a frame 24 blocks down and a cave between: the walk to a dry start, the staircase in legs through the cave, the tunnel the last blocks.

A third wall of the same batches was the bot's own physics: a landing in a pale garden stood on pale moss carpet, whose sixteenth of a block the physics did not collide with, so the server refused every move ([[land-physics]], `blocks/collision-top`, `physics/rise-onto`); every walk there failed `:no-path` (the stronghold row's third landing, 0/3 in three batches).
