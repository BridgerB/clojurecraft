---
title: Plan
type: moc
tags: [bot, plan]
status: verified
lastUpdated: 2026-10-10
verifiedAgainst: b08563f
related:
  - "[[bot/_moc|Bot pillar]]"
---

# Plan

- [[goals-and-intents]] - the goal table, the planner tick, and how intents advance, finish, fail and retry.
- [[dig-timeline]] - settle, START, swings, FINISH: the absolute-time schedule of a dig.
- [[pathfinder]] - a route for the feet over the block grid: A* with a node budget, moves and goals as data, water and lava as walls.
- [[walk-intent]] - plan a route on the first tick, follow it waypoint by waypoint, plan again when the world changes, fail :no-path after six plans.
- [[collect-intent]] - walk onto the drop until any log is held; name a blocking leaf; 10 s timeout.
- [[gather-chain]] - walk, dig, collect tagged for :log, continued from :plan/last, up to six leaf digs.
- [[roadmap-issues]] - which GitHub issue number is which roadmap step and draft.

- [[intentions-as-facts]] - every intent's story in memory; what the bot was doing at any moment is a query.
## See also
- [[bot/_moc|Bot pillar]]
- [[early-finish-aborts]] - why FINISH is late on purpose.
- [[bot/crafting/_moc|Crafting]] - the :kit goal and the :craft intent.
