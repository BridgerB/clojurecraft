---
title: Stronghold
description: How the bot finds the stronghold (issue #265) - an eye of ender's flight read from the entity it becomes, a throw as a fact, and two throws' lines crossed on the plane by a pure function that answers nothing when they are parallel or cross behind a throw.
type: reference
tags: [bot, plan, stronghold, eyes, goals]
aliases: [stronghold.clj, triangulate, :stronghold/throws, :throw/from, :throw/dir, min-crossing]
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

## The crossing
`stronghold/triangulate a b` crosses the two throws' rays on the plane and returns `[x z]`, a pure function of the two facts. It answers nil when:

- the lines are nearly parallel: the sine of their angle is under `min-crossing` (0.035, two degrees). Two eyes thrown along one line say nothing about how far the stronghold is, so the second throw must come from off the first line;
- the crossing is behind either throw: an eye never flies away from its stronghold.

Tests: a crossing found from either throw first, parallel and one-degree lines answering nothing, a crossing behind one or both throws answering nothing, and a property over 300 generated strongholds and throw points that every pair of exact throws far enough from parallel names the stronghold.
