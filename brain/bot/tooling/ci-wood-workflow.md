---
title: CI wood workflow
description: The one-job GitHub workflow: boot a vanilla server, run the bot, judge by the RESULT line and an independent RCON inventory read, upload logs.
type: reference
tags: [bot, tooling, ci]
aliases: [wood.yml, gym job, RESULT line, RCON judge]
status: verified
lastUpdated: 2026-10-09
verifiedAgainst: 60b624e
sourceRefs:
  - .github/workflows/wood.yml#name: Independent judge over RCON
  - .github/workflows/wood.yml#grep -q '^RESULT {.*:ok true' bot.log
  - src/clojurecraft/main.clj#defn result
related:
  - "[[bot/tooling/_moc|Tooling]]"
  - "[[local-server]]"
  - "[[record-replay]]"
---

# CI wood workflow

`.github/workflows/wood.yml` runs on every push: Temurin 25, the Clojure CLI from the official installer, cached deps and server jar, `clojure -M:test`, `scripts/server.sh start` on 25565/25575 with a masked random RCON password, the bot in the background with `--hold-ms 30000`, a poll for the `RESULT` line, `grep` for `:ok true`, then `clojure -M:rcon ... data get entity Clj_wood Inventory` must contain `_log`. Logs and the inventory read are artifacts; the server is stopped in `always()`.

## Key files
- `wood.yml` - the job.
- `main.clj`, `result` - what the RESULT line contains: `:ok`, `:reason`, the game summary, the plan summary.

## How it works
1. The bot prints RESULT when the goal is reached, then holds the connection so the judge can read the live player's inventory (offline players cannot be read).
2. Two green runs so far: whole job about 1m40s, server up in 17 s.

## Gotchas
- `RESULT` map key order is arbitrary; grep for `:ok true` anywhere on the line.
- The first run needed two collect retries ([[drop-under-the-trunk]]); the fix is on feat/pr2.

## Limits
One job, one goal. The matrix gym is issue #3.

## See also
- [[record-replay]] - add `--record` to turn a CI run into a replayable artifact.
