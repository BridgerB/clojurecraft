---
title: Run locally
description: Toolchain from the flake (direnv loads it on cd), start the local server, run the wood goal with the RCON landing, judge it over RCON, replay the recording.
type: how-to
tags: [bot, how-to, run]
aliases: [getting started, run the bot, local run]
status: verified
lastUpdated: 2026-10-11
verifiedAgainst: HEAD
sourceRefs:
  - .envrc#use flake
  - flake.nix#jdk = pkgs.jdk25;
  - lefthook.yml#pre-commit:
  - bb/clojurecraft/server.clj#(def local-ports {:port 25571 :rcon-port 25581})
  - src/clojurecraft/harness.clj#defn -main
related:
  - "[[bot/how-to/_moc|How-to]]"
  - "[[local-server]]"
  - "[[record-replay]]"
---

# Run locally

## Steps
1. `cd` into the repo: direnv loads the flake's shell (`.envrc`, `use flake`), with Java 25, Clojure, clj-kondo, clojure-lsp, lefthook and zstd on PATH and the git hooks installed. Once per clone, `direnv allow`; without direnv, `nix develop`.
2. `clojure -M:test` - the unit, property and sim tests, no server needed.
3. `bb local-server start` - first boot generates the world; wait for "server up". `bb tasks` lists every command.
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
8. Committing runs the pre-commit hook (`lefthook.yml`): tidy the ns forms, format with zprint, then clj-kondo, Splint and the unused-var check refuse anything they find; the commit-msg hook refuses AI attribution. `clojure -M:brain` keeps this brain at zero; CI runs it, and the hooks do not.

## See also
- [[local-server]]
