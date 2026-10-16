---
title: Plan
type: moc
tags: [bot, plan]
status: verified
lastUpdated: 2026-10-11
verifiedAgainst: HEAD
related:
  - "[[bot/_moc|Bot pillar]]"
---

# Plan

- [[goals-and-intents]] - the goal table, the planner tick, and how intents advance, finish, fail and retry.
- [[dig-timeline]] - settle, choose the tool, START, swings, FINISH at the block's own time, confirm: the absolute-time schedule of a dig.
- [[hardness-and-tools]] - hardness, harvest tags and tool materials read from the game at datagen; the break formula; choosing a tool.
- [[stairs-down]] - a staircase cut one stair at a time, refusing water, lava and drops, never digging the support.
- [[water-escape]] - the trap with hysteresis, the two always-on priority-zero goals, :swim-out (one bank, dig from the floor), :flee, :wade, trap facts, dig refusals beside water.
- [[survival]] - Normal difficulty (issue #259): the food from set-health, the :eat always row (food in hand, one use-item, done when the server's food rises), the sim's eating, the eat gym row.
- [[ender-pearls]] - endermen hunted for their pearls (issue #263): the loot table and the stare rules from the jar, a stare from range to provoke, the fight at the legs, the drop picked up.
- [[pathfinder]] - a route for the feet over the block grid: A* with a node budget, moves and goals as data, water and lava as walls.
- [[walk-intent]] - plan a route on the first tick, follow it waypoint by waypoint, plan again when the world changes, fail :no-path after six plans.
- [[collect-intent]] - walk onto the drop until any log is held; name a blocking leaf; 10 s timeout.
- [[gather-chain]] - walk, dig, collect tagged for :log, continued from :plan/last, up to six leaf digs.
- [[scoop-intent]] - fill a bucket at surface water in sight or remembered, never cave water; aim at the surface, use-item, done when the water bucket is held.
- [[mine-act]] - mining rows as data (stone, coal, iron, gravel): the tool, the drops, the walk beside the block, stairs down to the row's depth.
- [[build-plan]] - a build as data: ops with done-predicates, the first not satisfied is worked; portal-plan, the portal cut into the rock beside a lava pool.
- [[fortress-tunnel]] - the Nether leg: bricks as sightings, a tunnel whose steps are plans of ops, never opening a cell beside lava.
- [[roadmap-issues]] - which GitHub issue number is which roadmap step and draft.

- [[intentions-as-facts]] - every intent's story in memory; what the bot was doing at any moment is a query.
## See also
- [[bot/_moc|Bot pillar]]
- [[early-finish-aborts]] - why FINISH is late on purpose.
- [[bot/crafting/_moc|Crafting]] - the :kit goal and the :craft intent.
