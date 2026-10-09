---
title: Harness landing
description: The RCON landing sequence the harness runs before :go (survival, clear, locate forest, forceload, wait until loaded, tp onto the surface, unforceload), and how it observes the atom without touching the reducer.
type: reference
tags: [bot, tooling, harness, rcon]
aliases: [land!, land-and-go!, locate biome forest, forceload, wait-for, forest landing]
status: verified
lastUpdated: 2026-10-09
verifiedAgainst: 5c7d6c1
sourceRefs:
  - src/clojurecraft/harness.clj#defn land!
  - src/clojurecraft/harness.clj#defn land-and-go!
  - src/clojurecraft/harness.clj#defn wait-for
  - src/clojurecraft/main.clj#harness failed:
related:
  - "[[bot/tooling/_moc|Tooling]]"
  - "[[natural-tree-not-fixture]]"
  - "[[rcon-client]]"
---

# Harness landing

The harness is a test fixture that lands the bot in a forest and then sends `:go`. It runs on its own `a/thread`, started by `main` only when `--until` names planned goals. It watches the world atom with `add-watch` (perception without coordination) and reaches the loop only through the events channel.

## Key files
- `harness.clj`, `wait-for` - blocks until a predicate over the atom is truthy or a timeout passes, via a watch and a promise.
- `harness.clj`, `land!` - the RCON commands.
- `harness.clj`, `land-and-go!` - the sequence and the `:go`.
- `main.clj`, `-main` - on any harness exception it logs "harness failed:" and sends `:go` anyway.

## Sequence
1. Wait (60 s) for `:player/loaded?`.
2. If an RCON password was given, over one RCON connection: `gamemode survival <name>`, `clear <name>` (an empty inventory every run), `execute at <name> run locate biome minecraft:forest`, parse `[x, ~, z]` from the reply.
3. `forceload add x z`, then poll `execute if loaded x 0 z` every 200 ms (up to 150 times) until it reports passed.
4. `execute positioned x 0 z positioned over motion_blocking_no_leaves run tp <name> ~0.5 ~ ~0.5` - the highest motion-blocking block that is not leaves, so the bot lands on the ground under the canopy, centred in the block.
5. `forceload remove x z`.
6. Back on the bot side: wait (20 s) for `:stats/teleports` to increase, then (20 s) for the chunk at the new position to load, then sleep 1 s, log what the bot stands on, and put the `:go` event on the channel.

## Gotchas
- Without `--rcon-pass` there is no landing: the bot gets `:go` where it spawned.
- The `:go` event is recorded like any other, so a replay never needs RCON ([[record-replay]]).
- `clear` means a `--until table` run always starts from nothing; prerequisites over RCON are issue #3.

## See also
- [[natural-tree-not-fixture]] - why a real forest.
