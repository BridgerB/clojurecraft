---
title: Pause when empty
description: Why a 26.x server with no players stops ticking after 60 s (pause-when-empty-seconds) and the setting that keeps it ticking for RCON fixtures and a late-connecting bot.
type: reference
tags: [game, server, properties]
aliases: [pause-when-empty-seconds=0, server paused, empty server stops ticking]
status: verified
lastUpdated: 2026-10-09
verifiedAgainst: 26.1.2
sourceRefs:
  - scripts/server.sh#pause-when-empty-seconds=0
  - local-server.sh#export PORT=25571 RCON_PORT=25581
related:
  - "[[game/server/_moc|Server]]"
  - "[[local-server]]"
---

# Pause when empty

## Facts
- 26.x servers pause the game loop when no player has been online for `pause-when-empty-seconds` (default 60). While paused, world time, chunk loading by `forceload`, and item entities do not advance.
- Both our servers write `pause-when-empty-seconds=0` (never pause): CI boots the server well before the bot connects, and RCON fixtures (forceload, locate) run against a server with no players.

## Gotchas
- Any new harness or gym script that writes its own `server.properties` must carry the line, or the first 60 s before the bot connects are the last ticks the world gets.

## Limits
The default of 60 s is recorded in [[local-server]], not re-measured here.

## See also
- [[mc-spawn-protection]] - the other server line the bot depends on.
