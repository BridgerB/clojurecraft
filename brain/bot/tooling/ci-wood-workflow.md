---
title: CI wood workflow
description: The GitHub workflow matrix (wood, table, pickaxe): boot a vanilla server per row, run the bot with --until, judge by the RESULT line and an independent RCON inventory read, upload logs and the recording.
type: reference
tags: [bot, tooling, ci]
aliases: [wood.yml, gym job, RESULT line, RCON judge]
status: verified
lastUpdated: 2026-10-09
verifiedAgainst: 5c7d6c1
sourceRefs:
  - .github/workflows/wood.yml#name: Independent judge over RCON
  - .github/workflows/wood.yml#grep -q '^RESULT {.*:ok true' bot.log
  - src/clojurecraft/main.clj#defn result
  - ".github/workflows/wood.yml#max-parallel: 3"
related:
  - "[[bot/tooling/_moc|Tooling]]"
  - "[[local-server]]"
  - "[[record-replay]]"
---

# CI wood workflow

`.github/workflows/wood.yml` runs on every push (and by hand) as a matrix of three gym rows, at most three in parallel: `wood` (bot `Clj_wood`, judge string `_log`), `table` (bot `Clj_table`, judge `minecraft:crafting_table`) and `pickaxe` (bot `Clj_pickaxe`, judge `minecraft:wooden_pickaxe`). Each row: Temurin 25, the Clojure CLI from the official installer, cached deps and server jar, `clojure -M:test`, `scripts/server.sh start` on 25565/25575 with a masked random RCON password, the bot in the background with `--until <row> --timeout-ms 200000 --hold-ms 30000 --record run.edn`, a poll (210 s) for the `RESULT` line, `grep` for `:ok true`, then `clojure -M:rcon ... data get entity <bot> Inventory` must contain the row's judge string. `bot.log`, `run.edn`, the inventory read and the server logs are artifacts; the server is stopped in `always()`. A newer push cancels a running one (concurrency group per ref).

## Key files
- `wood.yml` - the job and its matrix.
- `main.clj`, `result` - what the RESULT line contains: `:ok`, `:reason`, the game summary, the plan summary.

## How it works
1. The bot prints RESULT when the goal is reached, then holds the connection so the judge can read the live player's inventory (offline players cannot be read).
2. The first two green `wood` runs took about 1m40s each, server up in 17 s.
3. Every row uploads its recording, so a failed CI run replays locally with `clojure -M:replay run.edn` ([[record-replay]]).

## Gotchas
- `RESULT` map key order is arbitrary; grep for `:ok true` anywhere on the line.
- The first run needed two collect retries ([[drop-under-the-trunk]]); fixed by `trunk-target`.
- The matrix comment budgets clojurecraft at 5 concurrent runners in total.

## Limits
Three fixed rows; no prerequisites over RCON, no landing sets, no repeated trials. The full gym is issue #3.

## See also
- [[record-replay]] - add `--record` to turn a CI run into a replayable artifact.
