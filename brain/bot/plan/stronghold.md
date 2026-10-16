---
title: Stronghold
description: How the bot finds the stronghold (issue #265) - an eye of ender's flight read from the entity it becomes, a throw as a fact, and two throws' lines crossed on the plane by a pure function that answers nothing when they are parallel or cross behind a throw.
type: reference
tags: [bot, plan, stronghold, eyes, goals]
aliases: [stronghold.clj, triangulate, :stronghold/throws, :throw/from, :throw/dir, min-crossing, :throw-eye, :eye-throw, :thrown?, :no-eye, :eye-unmoved, throw, bearing]
status: verified
lastUpdated: 2026-10-16
verifiedAgainst: HEAD
sourceRefs:
  - src/clojurecraft/stronghold.clj#def min-crossing 0.035
  - src/clojurecraft/stronghold.clj#defn cross
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
related:
  - "[[bot/plan/_moc|Plan]]"
  - "[[sib-end-and-dragon]]"
  - "[[entities]]"
  - "[[roadmap-issues]]"
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
5. `:follow` keeps the eye's last place in `:intent/eye-pos` each tick; `follow-ms` (2000 ms) after it was seen, or at once if the server removes it sooner, the line from `:intent/eye-from` to there is recorded as a throw (`record-throw`; under `min-flight`, one block, it fails `:eye-unmoved`). The intent is done when the server removes the eye (it drops as an item or shatters at 80 ticks), so the planner after it knows where it ended.

## The throw trial
The `:eye-throw` row (`--until throw`, priority 21, an aside, overworld only) is done by `:thrown?` (a throw on record) and planned by `:throw`: a `:throw-eye` with an eye held, else wait `:no-eye`. The gym row `throw` gives two eyes on a surface landing at noon and judges with a new kind of truth: a `:truth/check` keyword picks a `gym/verdict` method instead of the pattern, and `:bearing` reads the located stronghold out of `locate structure minecraft:stronghold` run at the landing and passes when the RESULT's first throw (`:throws`, added to the RESULT once a throw is kept) is within `gym/bearing-within` (10 degrees) of the way there.

## The crossing
`stronghold/triangulate a b` crosses the two throws' rays on the plane and returns `[x z]`, a pure function of the two facts. It answers nil when:

- the lines are nearly parallel: the sine of their angle is under `min-crossing` (0.035, two degrees). Two eyes thrown along one line say nothing about how far the stronghold is, so the second throw must come from off the first line;
- the crossing is behind either throw: an eye never flies away from its stronghold.

Tests: a crossing found from either throw first, parallel and one-degree lines answering nothing, a crossing behind one or both throws answering nothing, and a property over 300 generated strongholds and throw points that every pair of exact throws far enough from parallel names the stronghold.
