---
title: Harness landing
description: The RCON landing the harness runs before :go (survival, clear, locate forest, then teleport onto the surface and re-try nearby spots until the landing is dry), and how it observes the atom without touching the reducer.
type: reference
tags: [bot, tooling, harness, rcon]
aliases: [land-and-go!, locate-forest, teleport!, wet?, perched?, buried?, bad-landing, landing-offsets, locate biome forest, forceload, wait-for, forest landing, landed in water]
status: verified
lastUpdated: 2026-10-09
verifiedAgainst: 2d7f669
sourceRefs:
  - src/clojurecraft/harness.clj#defn locate-forest
  - src/clojurecraft/harness.clj#defn teleport!
  - src/clojurecraft/harness.clj#defn wet?
  - src/clojurecraft/harness.clj#defn perched?
  - src/clojurecraft/harness.clj#defn buried?
  - src/clojurecraft/harness.clj#defn bad-landing
  - src/clojurecraft/harness.clj#def landing-offsets
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
- `harness.clj`, `locate-forest` - survival, clear, `locate biome minecraft:forest` from the bot.
- `harness.clj`, `teleport!` - forceload, wait until loaded, teleport onto the surface, unforceload.
- `harness.clj`, `bad-landing` - why a landing will not do: `:water` (`wet?`), `:tree` (`perched?`: log or leaves underfoot, or leaves at the feet), `:buried` (`buried?`: solid at the feet or head); nil when the landing is good.
- `harness.clj`, `landing-offsets` - 17 points tried in turn: the located point, then rings of eight at 24 and 48 blocks.
- `harness.clj`, `land-and-go!` - the sequence and the `:go`.
- `main.clj`, `-main` - on any harness exception it logs "harness failed:" and sends `:go` anyway.

## Sequence
1. Wait (60 s) for `:player/loaded?`.
2. If an RCON password was given, over one RCON connection: `gamemode survival <name>`, `clear <name>` (an empty inventory every run), `execute at <name> run locate biome minecraft:forest`, parse `[x, ~, z]` from the reply.
3. For each offset in `landing-offsets`: `forceload add x z`, then poll `execute if loaded x 0 z` every 200 ms (up to 150 times) until it reports passed.
4. `execute positioned x 0 z positioned over motion_blocking_no_leaves run tp <name> ~0.5 ~ ~0.5` - the highest motion-blocking block that is not leaves, so the bot lands on the ground under the canopy, centred in the block.
5. `forceload remove x z`.
6. Back on the bot side: wait (20 s) for `:stats/teleports` to increase, then (20 s) for the chunk at the new position to load, then sleep 1 s. If `bad-landing` names a reason and offsets remain, log "bad landing <reason> - trying another spot" and go to the next offset.
7. Log what the bot stands on and put the `:go` event on the channel.

## Gotchas
- Three bad landings were each seen in CI and each made a run fail before the check existed: on a pond (sank to y=50), on a log inside an oak canopy at y=91 (the heightmap skips leaves but not a trunk top), and inside stone at y=58 (the forest point's heightmap was not ready). All three recurred and recovered in the nine CI jobs that closed issue #7; one run needed seven tries around a pond.
- The `motion_blocking_no_leaves` heightmap counts water as a surface, so a forest point can land the bot on a pond. A CI `wood` run did exactly that: every walk stuck and the bot sank to y=50. Water physics is issue #5; until then the fixture avoids water.
- Without `--rcon-pass` there is no landing: the bot gets `:go` where it spawned.
- The `:go` event is recorded like any other, so a replay never needs RCON ([[record-replay]]).
- `clear` means a `--until table` run always starts from nothing; prerequisites over RCON are issue #3.

## See also
- [[natural-tree-not-fixture]] - why a real forest.
