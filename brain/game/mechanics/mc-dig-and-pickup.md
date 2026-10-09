---
title: Dig timing and pickup
description: A log by hand takes 60 ticks (3 s); an item is picked up server-side with no packets once the player's box inflated by (1, 0.5, 1) touches it after a 10-tick delay.
type: reference
tags: [game, dig, pickup]
aliases: [hardness 2, pickup box, take-item-entity, 3000 ms dig]
status: verified
lastUpdated: 2026-10-09
verifiedAgainst: 26.1.2
sourceRefs:
  - src/clojurecraft/intent.clj#def dig-ms
  - src/clojurecraft/sim.clj#defn- within-pickup?
  - src/clojurecraft/game.clj#defmethod on-packet [:play :take-item-entity]
related:
  - "[[game/mechanics/_moc|Mechanics]]"
  - "[[dig-timeline]]"
---

# Dig timing and pickup

## Facts
- Log hardness is 2.0; by hand the damage per tick is `1 / hardness / 30`, so `ceil(1 / (1/2/30)) = 60` ticks = 3000 ms. Verified live: START to FINISH at 4.25 s broke the block every time; the server does not echo a block update to the breaker.
- Pickup needs no client packet. The server collects an item when the player's AABB inflated by 1 horizontally and 0.5 vertically touches it, after a 10-tick spawn delay; the client then receives `take-item-entity`, `remove-entities` and an inventory packet. Observed live: `take-item-entity` with our entity id as collector about 0.5 to 1 s after FINISH.

## Limits
Tool speeds, harvest tiers and the in-water and off-ground penalties are not modelled yet (issue #9).

## See also
- [[dig-timeline]]
