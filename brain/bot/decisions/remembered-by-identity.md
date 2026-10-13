---
title: Remembered by identity
description: Why the planner's costliest questions are remembered by the identity of the world's parts (memo/by), why a search reads each cell once (path/cells), and why the overlay keeps its identity when a chunk arrives; with the race profiles that asked for each, and why a replay is unchanged.
type: decision
tags: [bot, decision, performance, race]
aliases: [memo, memo/by, memoize, path/cells, cell-key, capacity, CPU per bot, bots per runner, identity, superseded]
status: verified
lastUpdated: 2026-10-12
verifiedAgainst: HEAD
sourceRefs:
  - src/clojurecraft/memo.clj#defn by
  - src/clojurecraft/memory.clj#def positions-in
  - src/clojurecraft/stone.clj#def exposed-cells
  - src/clojurecraft/path.clj#def plan-on
  - src/clojurecraft/path.clj#defn cells
  - src/clojurecraft/path.clj#defn cell-key
  - src/clojurecraft/terrain.clj#defn superseded
  - "docs/hickey.md#type hints and primitive arrays only where a profiler pointed"
  - test/clojurecraft/memo_test.clj#a-remembered-function-answers-as-the-function-does
  - test/clojurecraft/memo_test.clj#the-overlay-keeps-its-identity-when-a-chunk-arrives-it-never-touched
  - test/clojurecraft/path_test.clj#the-cells-of-a-world-answer-as-classify-does
related:
  - "[[bot/decisions/_moc|Decisions]]"
  - "[[sets-over-arrays]]"
  - "[[pathfinder]]"
  - "[[race]]"
---

# Remembered by identity

## The problem
How many bots a runner carries is set by CPU (the capacity ramp, [[race]]): the bots' JVM was at 185% of the runner's four cores with twenty racers, and a bot short of CPU falls below 20 ticks a second. The planner re-derives everything every tick (docs/hickey.md: nothing is remembered that the world can tell you), and a JFR profile of a ten-bot local race showed what that cost: `plan-tick` was 49% of all samples, of which the walk to remembered blocks (`make/near` → `memory/positions-now`, a Datalog query over every fact) was 19%, the exposed-ore scan (`stone/exposed-of-states`) 25% and the route search 15%. Each was asked again every tick about a world whose parts had not changed.

## The choice
**Ask the parts, and remember by them.** A world's parts are values: the facts, the chunks, the block overlay. `memo/by` remembers a function's answers keyed by its arguments, a part by identity (`identical?`, one pointer compare) and a small argument (a cell, a goal, a set of states) by value. The costly questions are asked of their parts: `memory/positions-in [facts states dim]`, `stone/exposed-cells [chunks blocks feet-chunk states blacklist lo hi]`, `path/plan-on [chunks blocks traps from goal max-nodes]` (traps filtered to those in force first, so the clock is not an input). The cache is a fixed array of slots shared by the bots of a JVM; a race between threads replaces a whole slot, never mixes two.

**A search reads each cell once.** Remembering helps only while the parts stay the same; a dig changes the overlay and a chunk changes the chunks. The next profile (prof3, after the memo) had the route search at 20% and stone's exposure test at 10%. Nearly all of it was `terrain/block-at` (17%): every cell hashes two fresh vectors, one for the overlay and one for the chunk key. `classify-id` itself was 5.6%. So the answer was not arrays for the predicates ([[sets-over-arrays]] still holds); it was one read per cell. `path/cells` makes, for one search or one scan, a function from a cell to its class that holds each answer in a map keyed by one long (`cell-key`). Every cell predicate takes a world or its cells, so callers are unchanged.

**The overlay keeps its identity.** `put-column` rebuilt the whole overlay on every chunk load to drop the blocks the fresh column supersedes (5.5% of the profile). It also made every memo keyed on the overlay miss on every chunk. `terrain/superseded` returns the same map when the column holds none of its blocks.

## Why a replay is unchanged
Each remembered answer is the function's answer for those inputs, so a run remembered and a run recomputed are the same run. `a-remembered-function-answers-as-the-function-does` holds it over call sequences that collide in the slots, with equal parts that are not the same object. `the-cells-of-a-world-answer-as-classify-does` holds `cells` to `classify` in any order, loaded or not. Nothing here reads a clock or the atom.

## Measured
On the Mac, the bots' JVM went from 12.7% to 4.6% of the machine for the same ten-bot race after the memo, and to 4.3% after the chunk-load scans (confounded: the machine was busy). The runner measure is the capacity ramp (`capacity.yml`), not a laptop.

## What would change the answer
A profile in which a remembered function's misses dominate. Then the key is too coarse: key it on the region it reads, not the whole part.
