---
title: Spot two blocks away
description: Why the bot places a table in one of twelve feet-level cells exactly two blocks away on a solid support, not in the cell it is facing or adjacent to.
type: decision
tags: [bot, decision, placement]
aliases: [placement spot, around cells, never inside the player, place-into-player]
status: verified
lastUpdated: 2026-10-09
verifiedAgainst: 5c7d6c1
sourceRefs:
  - src/clojurecraft/place.clj#Feet-level cells two blocks away: never inside the player's own box.
  - test/clojurecraft/place_test.clj#a-placement-spot-is-never-inside-the-player
  - src/clojurecraft/sim.clj#defn- overlaps-player?
related:
  - "[[bot/decisions/_moc|Decisions]]"
  - "[[place-intent]]"
  - "[[sim-placement]]"
---

# Spot two blocks away

## The choice
`place/spot` tries a fixed list of twelve feet-level cells with offsets of 2 on one axis (`[2 0] [-2 0] ... [1 -2] [-1 -2]`), choosing the first that is empty, not liquid, on a solid support and within 4.0 of the eye.

## What was rejected
Adjacent cells (distance 1). The player box is 0.6 wide and the bot stands anywhere within its block, so a cell one block away can overlap the box; vanilla rejects a placement into the player, and the sim records it as a violation. Two blocks away can never overlap whatever the position inside the block, which the 300-case property checks. It also keeps the target within reach from any standing position.

## What would change the answer
Cramped terrain (a one-wide tunnel, a pillar) where no cell two away is free; then a search over more cells, or placing above the head, becomes necessary. Today such a case fails `:no-spot`.

## See also
- [[placement-judged-by-server]]
