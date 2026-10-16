---
title: Local server
description: The Mac's own vanilla 26.1.2 server: ports 25571/25581, where the RCON password lives, and the server.properties lines the bot depends on.
type: reference
tags: [bot, tooling, server]
aliases: [bb local-server, bb server, clojurecraft.server, local-server.sh, server.sh, port 25571, rcon 25581, server.properties]
status: verified
lastUpdated: 2026-10-11
verifiedAgainst: HEAD
sourceRefs:
  - bb/clojurecraft/server.clj#(def local-ports {:port 25571 :rcon-port 25581})
  - bb/clojurecraft/server.clj#["spawn-protection" 0]
  - bb/clojurecraft/server.clj#["pause-when-empty-seconds" 0]
  - bb/clojurecraft/server.clj#["allow-flight" true]
  - bb/clojurecraft/server.clj#defn start!
  - bb/clojurecraft/server.clj#defn local-opts
related:
  - "[[bot/tooling/_moc|Tooling]]"
  - "[[run-locally]]"
  - "[[gym-on-runners]]"
---

# Local server

`bb local-server start|stop` runs the vanilla 26.1.2 jar (sha1-checked download) on game port 25571 and RCON 25581, world in `data/local-world`, Java 25 from the flake (on PATH in its shell, else `scripts/nix-tool jdk`). The RCON password is random, stored in `data/local-server/rcon.pass` (mode 600, gitignored).

## Key files
- `bb/clojurecraft/server.clj`, `local-opts` - this Mac's ports, the password, the world; `bb local-server`.
- `bb/clojurecraft/server.clj`, `start!` / `stop!` - shared by this Mac, CI's gym trials, the world build and a plan's jobs (`bb server start|stop`, set up by `PORT RCON_PORT RCON_PASS WORLD`): writes `eula.txt` and `server.properties` (`properties`, ordered pairs as data), starts `java ... nogui` detached, waits for `Done (` in `server.log` (180 s cap); `stop!` kills the pid, forcing it after 60 s. Babashka has no `ProcessHandle`, so liveness is `kill -0`, as the shell script did.

## Properties that matter
- `online-mode=false` - offline UUIDs, no encryption.
- `allow-flight=true` - a stationary or slightly drifting bot is not kicked for flying.
- `spawn-protection=0` - a non-op bot can break blocks near spawn; with the default 16 the server silently restores them.
- `pause-when-empty-seconds=0` - 26.x pauses an empty server after 60 s, before a bot connects.
- `level-seed=typecraft`, `difficulty` from `DIFFICULTY` (peaceful by default; every workflow sets normal since issue #10), `view-distance=6`.

## Gotchas
- ruststeve uses 25567/25568 (RCON 25577/25578) and steve 25569/25570 (25579/25580) on this Mac; never point at those.
- The world is ours to delete while the server is down (`data/local-world`).
- A start on a world another server holds fails fast with Minecraft's `session.lock: already locked`, and overwrites `server.pid` with the dead attempt's pid; put the running server's pid back (`lsof -tiTCP:25571 -sTCP:LISTEN`) before `bb local-server stop`.

## See also
- [[run-locally]]
