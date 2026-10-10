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
- [[ci-wood-workflow]] - the one-job CI: server boot, bot, RESULT, RCON judge, artifacts.
- [[record-replay]] - every run is a file; replay it with no server.
- [[server-model]] - the pure vanilla-server model tests run the whole bot against.
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
