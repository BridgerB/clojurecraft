---
title: Run locally
description: Toolchain via nix, start the local server, run the wood goal with the RCON landing, judge it over RCON, replay the recording.
type: how-to
tags: [bot, how-to, run]
aliases: [getting started, run the bot, local run]
status: verified
lastUpdated: 2026-10-10
verifiedAgainst: 96ac5ed
sourceRefs:
  - CLAUDE.md#nix shell nixpkgs#jdk25 nixpkgs#clojure
  - local-server.sh#export PORT=25571 RCON_PORT=25581
  - src/clojurecraft/harness.clj#defn -main
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
4. Land the bot in a forest and run it, the fixture piped into the bot ([[harness-landing]]):
   ```bash
   P="$(cat data/local-server/rcon.pass)"
   clojure -M:harness --rcon-port 25581 --rcon-pass "$P" --name Clj_wood --until wood 2> data/runs/harness.log \
     | clojure -M:run --port 25571 --name Clj_wood --until wood --events stdin --hold-ms 20000 --record data/runs/x.edn
   ```
   Without the harness, `clojure -M:run --port 25571 --until wood` plans from wherever the bot spawned.
5. While it holds: `clojure -M:rcon --port 25581 --pass "$(cat data/local-server/rcon.pass)" data get entity Clj_wood Inventory` should show a `_log`.
6. `clojure -M:replay data/runs/x.edn` reproduces the RESULT with no server.
7. For the crafting goal use `--until table` (and `--name Clj_table`) on both commands; judge with `data get entity Clj_table Inventory` containing `minecraft:crafting_table` ([[result-line]]).
8. `clojure -M:fmt fix src test dev` before committing; `clojure -M:brain` keeps this brain at zero.

## See also
- [[local-server]]
