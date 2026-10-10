---
title: Placement spot search
description: Why the bot searches rings 1-3 around its feet, at feet level then one down then one up, with an explicit body-overlap check, instead of the first design of twelve feet-level cells exactly two blocks away.
type: decision
tags: [bot, decision, placement]
aliases: [placement spot, around cells, never inside the player, place-into-player, no-spot, spot two blocks away]
status: verified
lastUpdated: 2026-10-09
verifiedAgainst: 85e133d
sourceRefs:
  - src/clojurecraft/place.clj#Candidate cells around the feet, nearest ring first, at feet level then one down then one
  - src/clojurecraft/place.clj#defn inside-player?
  - test/clojurecraft/place_test.clj#a-spot-is-found-on-an-uneven-floor
  - test/clojurecraft/place_test.clj#a-placement-spot-is-never-inside-the-player
  - src/clojurecraft/sim.clj#defn overlaps-player?
related:
  - "[[bot/decisions/_moc|Decisions]]"
  - "[[place-intent]]"
  - "[[sim-placement]]"
---

# Placement spot search

## The choice
`place/spot` walks the cells around the feet in rings of radius 1, 2 and 3, nearest first, each ring at feet level, then one down, then one up. It takes the first cell that is loaded, not solid, not liquid, on a solid support, not intersecting the player's box (`inside-player?`, an explicit AABB test) and within 4.0 of the eye.

## What was rejected
- **The first design: twelve feet-level cells exactly two blocks away.** Distance alone guaranteed no overlap with the body, which kept it simple. A CI `pickaxe` run on an uneven forest floor then failed `:no-spot`: none of those twelve cells was usable. That is a measured failure, not a preference.
- **Adjacent cells without a body check.** The player box is 0.6 wide and can stand anywhere in its block, so a cell one block away can overlap it; vanilla rejects that placement and the sim records it as a violation. The explicit AABB test makes adjacent cells safe; the 300-case property still checks that no chosen cell intersects the player.

## What would change the answer
Terrain where no cell within three blocks and one up or down works (a one-wide tunnel, a pillar top). Then the bot would need to dig a spot, or place above its head, which needs the pathfinder and digging work (issues #4 and #9).

## See also
- [[placement-judged-by-server]]
