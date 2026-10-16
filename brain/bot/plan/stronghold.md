---
title: Stronghold
description: How the bot finds the stronghold (issue #265) - an eye of ender's flight read from the entity it becomes, a throw as a fact, and two throws' lines crossed on the plane by a pure function that answers nothing when they are parallel or cross behind a throw.
type: reference
tags: [bot, plan, stronghold, eyes, goals]
aliases: [stronghold.clj, triangulate, :stronghold/throws, :throw/from, :throw/dir, min-crossing, :throw-eye, :eye-throw, :thrown?, :no-eye, :eye-unmoved, throw, bearing, :stronghold, :frames-seen?, stronghold-next, second-spot, crossing, hop, explore, :near-stronghold, :no-way, :no-frame, :truth/at, :frame, :portal-room, :in-portal-room?, :intent/dry?, dry?, portal-room]
status: verified
lastUpdated: 2026-10-16
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
5. `:follow` keeps the eye's last place in `:intent/eye-pos` each tick; `follow-ms` (2000 ms) after it was seen, or at once if the server removes it sooner, the line from `:intent/eye-from` to there is recorded as a throw (`record-throw`; under `min-flight`, one block, it fails `:eye-unmoved`). Once the server removes the eye (it drops as an item four throws in five, or shatters, at 80 ticks) the intent keeps `:intent/gone-at` and is done when the dropped eye lies where it ended (`eye-drop`: within the drop radius of its last place brought down to the feet's height, since it flew up) or `drop-grace-ms` (1000 ms) later: the removal and the item's add need not come in one tick, and the planner picks the item up only if it is there when the intent ends.

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
3. a crossing (`crossing`: the latest throw crossed with each earlier one, the widest kept) the feet are not within `there` (16 blocks) of: a hop toward it;
4. at the crossing with the last throw made more than `rethrow-beyond` (48 blocks) off: a throw from here, whose line crosses the first at a wide angle;
5. at the crossing, thrown from near it already: walks round it (`explore`: hops toward eight points `explore-reach`, 64 blocks, round it, from a heading the clock picks), each loading chunks further out; none reachable waits `:no-frame`;
6. throws that do not cross (one, or parallel): a hop toward `second-spot` of the latest, `travel` (150) blocks from it along its line turned `travel-turn` (45 degrees), ahead and aside at once, and a throw there.

Every long walk goes in hops (`hop`): a `:walk` to a cell a body stands in (`stand-in`, within 12 of the feet's height) 32 blocks along the way, or turned by 30, 60 or 90 degrees either side when straight on has no route (`intent/routable?`); none waits `:no-way`. A walk to somewhere hundreds of blocks off is beyond a route's node budget.

The gym row `stronghold` lands the bot 250 blocks (`gym/stronghold-off`) from the stronghold the server locates from the landing, on the line through it, on dry ground (`land! :near-stronghold`, `fixture/land-at!`), with six eyes, an iron sword, pickaxe, helmet, chestplate and food; it is judged by `execute if block {at} minecraft:end_portal_frame`, where a truth row's `:truth/at` names a path into the RESULT whose `[x y z]` fills `{at}` (`gym/truth-cmd`): the RESULT names the nearest frame remembered as `:frame`.

Against the model (#260's eyes, `sim/init :stronghold`), `the-stronghold-is-found-by-throws-and-walks-against-the-model` plays the whole search: a frame deep in stone 113 blocks off, columns streamed two chunks round the player, six eyes; a throw, the walk off the line, a second throw, the walk to the crossing, the frame seen as its chunk loads.

## The portal room
The `:portal-room` row (`--until portal-room`, priority 23, an aside, overworld only) is done by `:in-portal-room?`: the feet cell's centre within `fortress/reach` (6) of the nearest remembered frame. Its plan: with a frame remembered, the fortress's `:tunnel-to` to it ([[fortress-tunnel]]: one step at a time, each a plan of ops, never opening a cell beside lava, never digging a frame, which `fortress/unbreakable` holds) with `:intent/dry? true`: `fortress/dry?` refuses a step that opens a cell that is water or has water beside it, since the overworld's way down runs through aquifers and under oceans. With none remembered, the search above. No corridor is walked: the frame is the tunnel's target from wherever the bot stands, down through the ground and the stronghold's walls. The gym row `portal-room` lands as `stronghold` does, with two pickaxes and cobblestone for the floors, and is judged by the frame at the RESULT's `:frame` and the bot within 7 of it, asked at the frame's coordinates (`execute positioned {at} if entity @a[name=...]`), never as the bot. Against the model, `the-portal-room-is-reached-against-the-model` tunnels from the surface to a frame 24 blocks down.
