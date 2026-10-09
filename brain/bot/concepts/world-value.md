---
title: The world value
description: What the one world map holds, how it is keyed, and what is deliberately absent from it.
type: explanation
tags: [bot, concepts, state]
aliases: [world map, the atom, game state, flat namespaced world]
status: verified
lastUpdated: 2026-10-09
verifiedAgainst: 5c7d6c1
sourceRefs:
  - src/clojurecraft/game.clj#defn init
  - src/clojurecraft/spec.clj#(s/def ::world
  - src/clojurecraft/main.clj#defn apply-event!
related:
  - "[[bot/concepts/_moc|Concepts]]"
  - "[[reducer-and-effects]]"
  - "[[memory-sightings]]"
---

# The world value

Everything the bot knows is one immutable map held in one atom, written only by the loop thread. Keys are namespaced attributes, flat (no nesting): `:bot/phase`, `:player/pos`, `:world/chunks`, `:plan/intent`, `:stats/teleports`. An attribute the bot does not know is absent, never nil-filled: `:player/pos` does not exist until the first teleport.

## Key files
- `game.clj`, `init` - the initial map; every attribute it contains is listed there.
- `spec.clj`, `::world` - the spec; required keys are only `:bot/phase :bot/effects :time/now :time/tick`.
- `main.clj`, `apply-event!` - the only write: `swap!` with the reducer, then drain `:bot/effects`.

## How it works
1. Namespaces group attributes by owner: `bot/*` (identity, phase, effects), `conn/*`, `time/*`, `player/*`, `window/*` (the player's own screen: grid, cursor, state id, open container; see [[window-zero-model]]), `world/*`, `plan/*`, `net/*` (last sent position), `stats/*`.
2. `:world/chunks` maps `[cx cz]` to a decoded column; `:world/blocks` is an overlay of block updates and the bot's own breaks; `:world/sightings` is memory.
3. `:bot/effects` is a vector the reducer appends to and the loop clears after performing; it is part of the value on purpose, not a hidden key.
4. `summary` in `game.clj` is what `RESULT` prints; `stats/*` keys are collected by namespace.

## Gotchas
- `:player/controls` is written by the planner and read by physics on the next tick; it is state, not an effect, because it persists.
- The chunk sections contain a Java `long[]` that is never mutated after decode; treat it as a value.

## Limits
Does not cover the plan keys in depth; see [[goals-and-intents]].

## See also
- [[reducer-and-effects]] - how the value changes.
