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
- [[suffocation]] - reaction and prevention under falling sand and gravel: the :escape-suffocation priority-zero goal, the :unbury intent, avoiding unsafe digs and steps under falling columns.
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
- [[blaze-rods]] - seven rods in the Nether: hurt first, a rod lying near, the nearest blaze, the remembered spawner walked or tunnelled to and camped by; the blaze gym rows.
- [[stronghold]] - the stronghold by eyes of ender (issue #265): a throw as a fact, two throws' lines crossed on the plane, nothing when parallel or behind.
- [[eyes-of-ender]] - twelve eyes crafted in the 2x2 grid from the rods and pearls (issue #264): the needs planner, a wait that names the short stage, the eyes gym row.
- [[end-portal]] - the end portal lit and the End entered (issue #266): an eye into each frame from a cell a route reaches, frames and stairs as floors, the step in over the portal's middle; the vanilla room the gym builds.
- [[dragon]] - the crystals broken and the dragon killed (issue #267): a pillar beside each spike, two blocks between the stand and the crystal's blast, the pillar dug back down; the perched dragon struck by its box; the win by the server's word.
- [[roadmap-issues]] - which GitHub issue number is which roadmap step and draft.

- [[intentions-as-facts]] - every intent's story in memory; what the bot was doing at any moment is a query.
## See also
- [[bot/_moc|Bot pillar]]
- [[early-finish-aborts]] - why FINISH is late on purpose.
- [[bot/crafting/_moc|Crafting]] - the :kit goal and the :craft intent.
