---
title: Suffocation escape and prevention
description: How the bot reacts to and prevents suffocation under falling sand and gravel - the :escape-suffocation priority-zero goal, the :unbury intent (stepping to a free neighbour or digging the head cell), prevention of unsafe digs and steps under falling columns, and the :player/suffocating? fact.
type: reference
tags: [bot, plan, survival, suffocation, gravel, sand, trap, intents, goals]
aliases: [:escape-suffocation, :unbury, suffocating?, :player/suffocating?, falling-column?, unsafe-falling?, free-neighbours]
status: verified
lastUpdated: 2026-10-27
verifiedAgainst: HEAD
sourceRefs:
  - src/clojurecraft/suffocate.clj#defn free-neighbour?
  - src/clojurecraft/suffocate.clj#defn free-neighbours
  - src/clojurecraft/trap.clj#defn suffocating?
  - src/clojurecraft/intent.clj#defn falling-column?
  - src/clojurecraft/intent.clj#defn unsafe-falling?
  - src/clojurecraft/stairs.clj#defn falls-over?
  - src/clojurecraft/physics.clj#defn overlaps-column?
  - src/clojurecraft/physics.clj#defn eye-cell
  - src/clojurecraft/physics.clj#defn suffocating?
  - "resources/clojurecraft/goals.edn#{:goal/id :escape-suffocation :goal/priority 0 :goal/target? true :goal/always? true"
related:
  - "[[bot/plan/_moc|Plan]]"
  - "[[water-escape]]"
  - "[[goals-and-intents]]"
  - "[[stairs-down]]"
---

# Suffocation escape and prevention

Real race 40466567590 lane 3 had 137 deaths, 27 of them "suffocated in a wall". The bot would dig beneath falling sand or gravel while its body overlapped that column, get buried under the falling blocks, and then continue digging down below its feet for 20 seconds while losing 1 HP per hit until death.

Survival under falling sand and gravel consists of two shared behaviours:

## 1. Reaction (`:escape-suffocation` and `:unbury`)

When the bot's head (eye) cell holds a solid block (`blocks/solid?`), clearing it comes before any other intent.
- Pure fact: `:player/suffocating?` is written by `physics/step` every tick, and `trap/suffocating?` derives whether the eye cell holds a solid block.
- Trap: `trap/trapped-tick` marks `:plan/trap :suffocation` without delay.
- Goal: `:escape-suffocation` is an always-on priority-0 goal at the top of `goals.edn`. It preempts any running intent (such as digging stairs down).
- Executor: `:unbury` in `clojurecraft.suffocate`. It looks for an adjacent free neighbour footing (open feet and head, solid floor, no falling blocks overhead) and steps to it. If no free neighbour exists or stepping does not clear the head within 8 ticks, it digs the head cell (`physics/eye-cell`), repeating if falling blocks refill it.
- Invariant: while buried, digging below the feet is strictly refused.

## 2. Prevention

- Column check: `intent/falling-column?` inspects the column above a cell. If a falling block (`blocks/falls?`) is found with only air, passable or falling blocks below it, it can fall.
- Body overlap: `physics/overlaps-column?` determines if the player's 0.6x0.6 bounding box touches the column.
- Dig refusal: `intent/dig-stage` refuses any dig where `unsafe-falling?` holds (the cell is under a falling column and the player overlaps the column), failing with `:unsafe`.
- Stair refusal: `stairs/falls-over?` checks whether any cell of a stair down or level step has a falling column above it. If so, `stairs/down-safe?` and `stairs/level-safe?` return false, causing the staircase to turn away rather than walking into or cutting under falling blocks.
