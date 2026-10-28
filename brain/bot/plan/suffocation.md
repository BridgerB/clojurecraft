---
title: Suffocation escape and prevention
description: How the bot reacts to and prevents suffocation under falling sand and gravel - the :escape-suffocation priority-zero goal, the :escape-suffocation intent (stepping to a free neighbour or digging the head cell), prevention of unsafe digs and moves under falling columns, and the :player/suffocating? fact.
type: reference
tags: [bot, plan, survival, suffocation, gravel, sand, trap, intents, goals]
aliases: [:escape-suffocation, suffocating?, :player/suffocating?, falling-column?, falling-entity?, falling-in-column?, step-into-falling?, step-toward, unsafe-falling?, column-stand, free-neighbours]
status: verified
lastUpdated: 2026-10-28
verifiedAgainst: HEAD
sourceRefs:
  - src/clojurecraft/suffocate.clj#defn free-neighbour?
  - src/clojurecraft/suffocate.clj#defn free-neighbours
  - src/clojurecraft/trap.clj#defn suffocating?
  - src/clojurecraft/intent.clj#defn falling-column?
  - src/clojurecraft/intent.clj#defn falling-entity?
  - src/clojurecraft/intent.clj#defn falling-in-column?
  - src/clojurecraft/intent.clj#defn step-into-falling?
  - src/clojurecraft/intent.clj#defn step-toward
  - src/clojurecraft/intent.clj#defn walk-into-falling?
  - src/clojurecraft/intent.clj#defn unsafe-falling?
  - src/clojurecraft/intent.clj#defn column-stand
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
- Dig refusal: breaking a cell the body overlaps while `falling-in-column?` is true drops the column onto the bot (`unsafe-falling?`). The dig does not fail `:unsafe` when a standable neighbour exists (`column-stand`): it steps there and breaks the cell from beside, then the existing collect takes the drops. With no such neighbour it still fails `:unsafe`, and a stair turns. Digging the full cube in the eye box is allowed. Digging any other cell while buried still fails `:unsafe`. Probe 40641701942 (R4/1 seed 2) failed `[:ingots :unsafe]` at three attempts because the refusal itself ended the plan.
- Movement prevention: `intent/step-into-falling?` is true only when the body would overlap a column that already has a falling-block entity (type 51). A sitting stack is walked under; it does not fall until its support is broken. `intent/route-stale?` does not replan for one: probe 40641701980 (R3/1 seed 1) failed `[:ingots :no-path]` after twelve walks because every waypoint under sitting sand was stale, each replan found the same route, and six replans failed the walk. `intent/toward` suppresses forward when the next step would enter a column with a block already in the air: `step-toward` is one body width toward the point, or the point itself when it is closer, so a shore many blocks away is still approached. Issue #177 snapshot `b10211335-R4-1-1` (world R4/1) passed on main and recurred when toward tested the shore centre fourteen blocks off. `intent/walk-into-falling?` waits, and does not fail the walk, before it can count as arrived: race 40466567590 bot Clj_r08 stood at x 2434.56, beside the gravel, already within reach of the column cell, so the walk was `:done` and the body then stepped to x 2434.98 while the gravel was still falling. A pickup walk is not that wait: it ends beside the block, and the column is cleared from the side. The walk's own deadline still ends a wait that never clears. `stairs/stairs-stage :step` waits with empty controls only while a falling-block entity (type 51, `intent/falling-entity?`) is in a stair cell's column, and the step's own timeout still ends that wait. A stack that is still sitting does not hold the step: probes 40633083832 and 40633083843 sat in one `:step` until the 30 minute cap because the wait used `falling-in-column?` and came before the timeout. `falls-over?` stays the one block directly over a stair's head cell, so a desert or a gravel beach is not refused in every direction.
