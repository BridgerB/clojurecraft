---
title: Local server
description: The Mac's own vanilla 26.1.2 server: ports 25571/25581, where the RCON password lives, and the server.properties lines the bot depends on.
type: reference
tags: [bot, tooling, server]
aliases: [local-server.sh, port 25571, rcon 25581, server.properties]
status: verified
lastUpdated: 2026-10-09
verifiedAgainst: 60b624e
sourceRefs:
  - local-server.sh#export PORT=25571 RCON_PORT=25581
  - scripts/server.sh#spawn-protection=0
  - scripts/server.sh#pause-when-empty-seconds=0
  - scripts/server.sh#allow-flight=true
related:
  - "[[bot/tooling/_moc|Tooling]]"
  - "[[run-locally]]"
  - "[[ci-wood-workflow]]"
---

# Local server

`./local-server.sh start|stop` runs the vanilla 26.1.2 jar (sha1-checked download) on game port 25571 and RCON 25581, world in `data/local-world`, Java 25 from `nix build nixpkgs#jdk25`. The RCON password is random, stored in `data/local-server/rcon.pass` (mode 600, gitignored).

## Key files
- `local-server.sh` - ports, password, Java, then `scripts/server.sh start`.
- `scripts/server.sh` - shared by Mac and CI: writes `eula.txt` and `server.properties`, starts `java ... nogui` detached, waits for `Done (` in `server.log` (180 s cap), `stop` kills the pid.

## Properties that matter
- `online-mode=false` - offline UUIDs, no encryption.
- `allow-flight=true` - a stationary or slightly drifting bot is not kicked for flying.
- `spawn-protection=0` - a non-op bot can break blocks near spawn; with the default 16 the server silently restores them.
- `pause-when-empty-seconds=0` - 26.x pauses an empty server after 60 s, before a bot connects.
- `level-seed=typecraft`, `difficulty=peaceful`, `view-distance=6`.

## Gotchas
- ruststeve uses 25567/25568 (RCON 25577/25578) and steve 25569/25570 (25579/25580) on this Mac; never point at those.
- The world is ours to delete while the server is down (`data/local-world`).

## See also
- [[run-locally]]
