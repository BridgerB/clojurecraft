---
title: Suffocation escape and prevention
description: How the bot reacts to and prevents suffocation under falling sand and gravel - the :escape-suffocation priority-zero goal, the :escape-suffocation intent (stepping to a free neighbour or digging the head cell), prevention of unsafe digs and moves under falling columns, and the :player/suffocating? fact.
type: reference
tags: [bot, plan, survival, suffocation, gravel, sand, trap, intents, goals]
aliases: [:escape-suffocation, suffocating?, :player/suffocating?, falling-column?, falling-in-column?, step-into-falling?, unsafe-falling?, free-neighbours]
status: verified
lastUpdated: 2026-10-27
verifiedAgainst: HEAD
sourceRefs:
  - src/clojurecraft/suffocate.clj#defn free-neighbour?
  - src/clojurecraft/suffocate.clj#defn free-neighbours
  - src/clojurecraft/trap.clj#defn suffocating?
  - src/clojurecraft/intent.clj#defn falling-column?
  - src/clojurecraft/intent.clj#defn falling-in-column?
  - src/clojurecraft/intent.clj#defn step-into-falling?
  - src/clojurecraft/intent.clj#defn walk-into-falling?
  - src/clojurecraft/intent.clj#defn unsafe-falling?
  - src/clojurecraft/stairs.clj#defn falls-over?
  - src/clojurecraft/physics.clj#defn overlaps-column?
  - src/clojurecraft/physics.clj#defn eye-cell
  - src/clojurecraft/physics.clj#defn suffocating?
  - src/clojurecraft/physics.clj#defn suffocation-cell
  - "resources/clojurecraft/goals.edn#{:goal/id :escape-suffocation :goal/priority 0 :goal/target? true :goal/always? true"
related:
  - "[[bot/plan/_moc|Plan]]"
  - "[[water-escape]]"
  - "[[goals-and-intents]]"
  - "[[stairs-down]]"
---

# Suffocation escape and prevention

Real race 40466567590 lane 3 had 137 deaths, 27 of them "suffocated in a wall". Each of those 27, matched to the player's own in_wall cluster, had sand or gravel in the eye box. The bot dug under a falling column or stepped into one while the blocks were still falling, then kept digging somewhere other than that cell until it died.

Survival under falling sand and gravel consists of two shared behaviours:

## 1. Reaction (`:escape-suffocation`)

When a full cube intersects the eye box (`physics/suffocating?`), clearing it comes before any other intent. The box is the eye point grown by 0.8 of the body width, vanilla's wall test: at the logged r08 burial (x 2434.98) the eye point is still in the air cell and the gravel is the next cell, which the box reaches.
- Pure fact: `:player/suffocating?` is written by `physics/step` every tick. A cell counts when its collision-top is at least 1.0 and its chunk is loaded. The dig breaks `physics/suffocation-cell`, the point cell when that is the cube, otherwise the other cell the box touches.
- Trap: `trap/trapped-tick` marks `:plan/trap :suffocation` without delay from the pure physics fact.
- Goal: `:escape-suffocation` is an always-on priority-0 goal at the top of `goals.edn`. It preempts any running intent (such as digging stairs down).
- Executor: `:escape-suffocation` in `clojurecraft.suffocate`. It looks for an adjacent free neighbour footing (open feet and head, solid floor, no falling blocks overhead) and steps to it. If no free neighbour exists or stepping does not clear the head within 8 ticks, it digs `physics/suffocation-cell`, repeating if falling blocks refill it.
- Invariant: while buried, digging below the feet is strictly refused.

## 2. Prevention

- Column check: `intent/falling-column?` inspects the column above a cell. If a falling block (`blocks/falls?`) is found with only air, passable or falling blocks below it, it can fall. `intent/falling-in-column?` also checks for falling block entities (type 51) currently falling in that column.
- Body overlap: `physics/overlaps-column?` determines if the player's 0.6x0.6 bounding box touches the column.
- Dig refusal: `intent/dig-stage` refuses any dig where `unsafe-falling?` holds (the cell is under a falling column and the player overlaps the column), failing with `:unsafe`. Digging the full cube in the eye box is allowed, and digging any other cell while buried is refused.
- Movement prevention: `intent/step-into-falling?` is true when a position's body box overlaps a column with a falling block above it, or a falling-block entity in that column. `intent/toward` suppresses forward when the point would enter one. `intent/walk-into-falling?` fails a walk `:unsafe` before it can count as arrived: race 40466567590 bot Clj_r08 stood at x 2434.56, beside the gravel, already within reach of the column cell, so the walk was `:done` and the body then stepped to x 2434.98 while the gravel was still falling. A pickup walk is not that failure: it ends beside the block, and the column is cleared from the side. `stairs/stairs-stage :step` waits with empty controls while a stair cell can still receive a fall. `falls-over?` stays the one block directly over a stair's head cell, so a desert or a gravel beach is not refused in every direction.
