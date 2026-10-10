---
title: Natural tree, not a fixture
description: Why CI and local runs chop a real tree in a normal world instead of a setblock log on a flat world.
type: decision
tags: [bot, decision, wood, ci]
aliases: [flat world fixture rejected, why locate biome, forest landing]
status: verified
lastUpdated: 2026-10-09
verifiedAgainst: 85e133d
sourceRefs:
  - src/clojurecraft/harness.clj#defn locate-forest
  - scripts/server.sh#level-type=minecraft:normal
related:
  - "[[bot/decisions/_moc|Decisions]]"
  - "[[gym-on-runners]]"
---

# Natural tree, not a fixture

## The choice
The harness runs `locate biome minecraft:forest` over RCON, forceloads the spot, teleports the bot onto the ground there, and the bot finds, walks to and digs a real log. World type is normal, seed `typecraft`.

## What was rejected
A flat world with `setblock` placing a log next to a stationary bot. It would have needed no physics, no search and no walking, and Bridger chose the natural tree on 2026-10-09 so the foundation included movement from day one. Measured cost of the natural version: the first CI run still finished in 1m40s.

## What would change the answer
Nothing for the goal itself; a fixture world may return as an additional fast gym for isolated mechanics (crafting windows), never as the only judge.

## See also
- [[gym-on-runners]]
