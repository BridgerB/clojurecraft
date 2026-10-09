---
title: Bot pillar
type: moc
tags: [pillar, hub, bot]
status: verified
lastUpdated: 2026-10-09
verifiedAgainst: 5c7d6c1
related:
  - "[[_index]]"
---

# Bot pillar

The clojurecraft bot itself: one world value, pure reducers, packets and plans as data. Verified against 18afddc (notes carry their own pins).

## Areas
- [[bot/concepts/_moc|Concepts]] - the models every other note assumes.
- [[bot/protocol/_moc|Protocol]] - bytes, packets, phases, the socket.
- [[bot/world/_moc|World]] - chunks, blocks, physics, memory.
- [[bot/plan/_moc|Plan]] - goals, intents (walk, dig, collect), the planner, the wood goal, the roadmap.
- [[bot/crafting/_moc|Crafting]] - recipes as data, the recipe graph, the craft intent, window 0, the sim window.
- [[bot/runtime/_moc|Runtime]] - the main loop, effects, the RESULT line.
- [[bot/tooling/_moc|Tooling]] - local server, CI, recording, the sim, datagen, specs, properties, harness, RCON.
- [[bot/gotchas/_moc|Gotchas]] - durable known issues, symptom-first.
- [[bot/how-to/_moc|How-to]] - task recipes.
- [[bot/decisions/_moc|Decisions]] - what was rejected and why.

## See also
- [[_index]] - the master index.
- [[siblings/_moc|Siblings]] - lessons imported from steve and ruststeve.
