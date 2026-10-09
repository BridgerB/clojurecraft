---
title: Test fixtures
description: The shared helpers every reducer test uses (fold, sent, names, ticks, packet, instrumented) and the chunk-column builder that makes worlds from a block map.
type: reference
tags: [bot, tooling, tests]
aliases: [fixtures.clj, world.clj, fold, column-bytes, test helpers]
status: verified
lastUpdated: 2026-10-09
verifiedAgainst: 5c7d6c1
sourceRefs:
  - test/clojurecraft/fixtures.clj#defn fold
  - test/clojurecraft/fixtures.clj#defn ticks
  - test/clojurecraft/world.clj#defn column-bytes
related:
  - "[[bot/tooling/_moc|Tooling]]"
  - "[[property-tests]]"
  - "[[server-model]]"
---

# Test fixtures

## Key files
- `fixtures.clj`, `fold` - folds events through a step function and returns `[final-world [[now effect] ...]]`, clearing `:bot/effects` after each step exactly as `main/apply-event!` does.
- `fixtures.clj`, `sent`, `names`, `packets` - filter the effect log to `[now packet]`, packet names, or packets.
- `fixtures.clj`, `ticks` - tick events every 50 ms over a range, all with `:event/rand 0.5`.
- `fixtures.clj`, `packet` - wraps a packet map as an event; `opts` is the standard `game/init` argument.
- `fixtures.clj`, `instrumented` - see [[specs-and-instrumentation]].
- `world.clj`, `column-bytes` / `column` - real chunk-column bytes from `{[lx y lz] state-id}`: stone below y 64, air above, and any section containing a listed block written as a 15-bit direct container.

## How tests use them
A reducer test is a fold over hand-written events, then assertions on the world and on `names`. End-to-end tests instead hand `column-bytes` to `sim/init` and let `sim/run` drive the bot ([[server-model]]). Because `:event/rand` is fixed at 0.5 in both, a detour always turns +70 degrees in tests.

## See also
- [[reducer-and-effects]] - what is being folded.
