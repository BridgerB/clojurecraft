---
title: Tooling
type: moc
tags: [bot, tooling]
status: verified
lastUpdated: 2026-10-09
verifiedAgainst: 5c7d6c1
related:
  - "[[bot/_moc|Bot pillar]]"
---

# Tooling

- [[local-server]] - the Mac's own vanilla server: ports, password, properties that matter.
- [[gym-on-runners]] - test.yml, and gym.yml: a goal on real terrain, one runner per run, judged by RESULT and the server, reported with an interval.
- [[race]] - many bots on one server, each its own atom and loop, one claims channel for landing cells; race.yml on every runner.
- [[record-replay]] - every run is a file; replay it with no server.
- [[server-model]] - the pure vanilla-server model tests run the whole bot against.
- [[sim-hunger]] - hunger in the model: FoodData drained, healed, starved and fed at the difficulty given.
- [[sim-mobs]] - mobs in the model: the night rule, straight approach, melee, arrows, fuses, the attack, loot, fixtures.
- [[sim-end]] - eyes of ender toward a stronghold, the frames and the end portal, the End's platform, the perched dragon and its crystals.
- [[sim-race]] - real worlds captured once, the whole bot played in them against the model, thousands of runs a runner.
- [[datagen]] - six EDN tables: four from --reports, recipes and tags from the inner jar.
- [[specs-and-instrumentation]] - what is specced, what is instrumented, what is not.
- [[property-tests]] - what each generative property guarantees.
- [[test-fixtures]] - fold, ticks, packet, and the chunk-column builder.
- [[harness-landing]] - the fixture process: RCON forest landing, then one :go on the bot's stdin.
- [[rcon-client]] - the RCON wire format, auth, and the -M:rcon command.

## See also
- [[bot/_moc|Bot pillar]]
- [[run-locally]] - the recipe.
- [[bot/runtime/_moc|Runtime]] - the loop the tooling drives.
