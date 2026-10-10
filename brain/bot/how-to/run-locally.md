---
title: Run locally
description: Toolchain via nix, start the local server, run the wood goal with the RCON landing, judge it over RCON, replay the recording.
type: how-to
tags: [bot, how-to, run]
aliases: [getting started, run the bot, local run]
status: verified
lastUpdated: 2026-10-09
verifiedAgainst: 5c7d6c1
sourceRefs:
  - CLAUDE.md#nix shell nixpkgs#jdk25 nixpkgs#clojure
  - local-server.sh#export PORT=25571 RCON_PORT=25581
related:
  - "[[bot/how-to/_moc|How-to]]"
  - "[[local-server]]"
  - "[[record-replay]]"
---

# Run locally

## Steps
1. `nix shell nixpkgs#jdk25 nixpkgs#clojure` (this Mac has neither on PATH).
2. `clojure -M:test` - the unit, property and sim tests, no server needed.
3. `./local-server.sh start` - first boot generates the world; wait for "server up".
4. `clojure -M:run --port 25571 --rcon-port 25581 --rcon-pass "$(cat data/local-server/rcon.pass)" --hold-ms 20000 --record data/runs/x.edn`.
5. While it holds: `clojure -M:rcon --port 25581 --pass "$(cat data/local-server/rcon.pass)" data get entity Clj_wood Inventory` should show a `_log`.
6. `clojure -M:replay data/runs/x.edn` reproduces the RESULT with no server.
7. For the crafting goal use `--until table` (and `--name Clj_table`); judge with `data get entity Clj_table Inventory` containing `minecraft:crafting_table` ([[result-line]]).
8. `clojure -M:fmt fix src test dev` before committing; `clojure -M:brain` keeps this brain at zero.

## See also
- [[local-server]]
