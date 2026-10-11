---
title: Test fixtures
description: The shared helpers every reducer test uses (fold, sent, names, ticks, packet, instrumented), the chunk-column builder that makes worlds from a block map, and the runner that runs every namespace and var in parallel.
type: reference
tags: [bot, tooling, tests]
aliases: [fixtures.clj, world.clj, fold, column-bytes, test helpers, runner.clj, eftest, clojure -M:test, parallel tests, instrument!]
status: verified
lastUpdated: 2026-10-11
verifiedAgainst: HEAD
sourceRefs:
  - test/clojurecraft/fixtures.clj#defn fold
  - test/clojurecraft/fixtures.clj#defn ticks
  - test/clojurecraft/world.clj#defn column-bytes
  - test/clojurecraft/runner.clj#defn -main
  - test/clojurecraft/runner.clj#defn selected
  - test/clojurecraft/fixtures.clj#defn instrument!
  - .github/workflows/test.yml#FOREST_TRIALS: '250'
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
- `fixtures.clj`, `instrumented` / `instrument!` - see [[specs-and-instrumentation]]. Instrumentation is never undone: namespaces run side by side, and an unstrument at one namespace's end would strip the checks from another mid-test.
- `runner.clj` - `clojure -M:test` is eftest over the test directory with `:multithread? true`: every namespace and every var in parallel on all cores, the reducers instrumented once first. `-n ns`, `-v ns/var` and `-x ns` select; the exit code is the verdict. The tests are pure functions over values, so they share nothing; the serial suite (cognitect's runner) took nine minutes on a runner, nearly all of it the sim namespace. `test.yml` runs the suite in one job with `FOREST_TRIALS=0` and the 2,000 forests as eight jobs of 250 with a seed from the run id: the suite is about a minute on one runner and the forests about a minute and a half each, against nine minutes serial.
- `world.clj`, `column-bytes` / `column` - real chunk-column bytes from `{[lx y lz] state-id}`: stone below y 64, air above, and any section containing a listed block written as a 15-bit direct container.

## How tests use them
A reducer test is a fold over hand-written events, then assertions on the world and on `names`. End-to-end tests instead hand `column-bytes` to `sim/init` and let `sim/run` drive the bot ([[server-model]]). Because `:event/rand` is fixed at 0.5 in both, a detour always turns +70 degrees in tests.

## See also
- [[reducer-and-effects]] - what is being folded.
