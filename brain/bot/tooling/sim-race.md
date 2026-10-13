---
title: Sim race
description: Real worlds captured from a real server once (capture.clj) and the whole bot played in them against the server model with no Java server (simrace.clj), from empty hands at a landing to the race's target, as many runs as the cores allow; each run a pure function of world, seed and code.
type: reference
tags: [bot, tooling, sim, race]
aliases: [capture, capture.clj, simrace, simrace.clj, sim race, captured world, world file, SIMRUN, SIMRACE, :capture/columns]
status: verified
lastUpdated: 2026-10-13
verifiedAgainst: HEAD
sourceRefs:
  - src/clojurecraft/capture.clj#defn stops
  - src/clojurecraft/capture.clj#defn take-landing!
  - src/clojurecraft/capture.clj#defn chunk-tap
  - src/clojurecraft/simrace.clj#defn run
  - src/clojurecraft/failure.clj#defn signature
  - src/clojurecraft/simrace.clj#defn shard
  - src/clojurecraft/simrace.clj#defn jobs
  - bb/clojurecraft/failures.clj#defn report!
  - .github/workflows/simrace.yml#bb failures report
  - src/clojurecraft/simrace.clj#defn prepared
  - "src/clojurecraft/sim.clj#The model as a server keeps a player's chunks"
  - src/clojurecraft/sim.clj#defn column-of
  - src/clojurecraft/sim.clj#defn tick-rand
  - test/clojurecraft/capture_test.clj#the-stops-see-every-column-of-the-square
  - test/clojurecraft/simrace_test.clj#a-run-walks-into-the-next-column-for-its-log
  - test/clojurecraft/simrace_test.clj#a-run-is-the-same-run-every-time
related:
  - "[[bot/tooling/_moc|Tooling]]"
  - "[[server-model]]"
  - "[[race]]"
  - "[[property-tests]]"
---

# Sim race

A real server carries about 25 bots on its one tick thread (capacity runs 38333898855 and 38348283421); the server model carries as many as there are cores, at about 150 times real time each. So the run is played in the model, in worlds a real server made.

## Capture: a real world, once
`clojure -M:capture --port --rcon-port --rcon-pass --landings <set>/landings.edn --set R1 --out cache/sim-worlds/R1 [--radius 10]`. For each landing, a client of its own (main's loop and reducer, with `chunk-tap` keeping every `level-chunk-with-light` packet's bytes) joins, is made a spectator and is teleported over `stops`, a view apart, until every column within radius chunks of the landing has arrived (`complete?`); then it leaves. One world file a landing, gzipped EDN: `{:capture/set :capture/landing [x z y] :capture/radius :capture/columns {[cx cz] bytes}}`, bytes as the recordings print them. Nothing is translated: the bot decodes the server's bytes as it decodes any server's. A local run took 49 columns of radius 3 in 20 s, 270 KB.

## The model holds the world whole
`sim/init :columns` is the world (`:column` is the world of one column [0 0]), `:decoded` the same columns decoded once when the world was read (`simrace/prepared`), shared by every run of it. The whole world goes to the client at login and stays: a run from spawn to the portal lives in a few hundred columns, tens of megabytes, one value. Each chunk packet carries the shared decoded column (`:chunk/column`, as the socket's reader thread attaches it), so no run decodes. `:view-distance r` streams and forgets columns as a server does instead (`stream`, `in-view`).

## A run
`simrace/run world seed until max-ms`: the bot from empty hands on the landing's surface, `:go/chain? true` with `plan/through until`, against the model, rolling loot and tick randomness from seed (`:seed`, `:tick-seed`, `sim/tick-rand`). Its RESULT is `main/result`, the same map a real run prints, with `:run/world`, `:run/seed`, `:run/ms` and `:run/violations`. A run is a pure function of world, seed and code: never saved, reproduced with `--world f --seed s` at the commit. `clojure -M:simrace --worlds <dir> --seeds n --threads t` plays every world's seeds on a pool, a SIMRUN line per run, `runs.edn`, and the SIMRACE tally: failures grouped by `signature`, [the target it was on, why it stopped, the last intent's kind].

## On the runners
`simrace.yml` runs on every push to the race branch, beside `race.yml`, and both restart with the new code: `ci/simrace.edn` gives `:simrace/runners` (10) runners, the sets (R1-R4), the target, the simulated minutes a run may take and the wall budget a runner plays (300 minutes). `world.yml` builds each set's server world once; `simworld.yml` captures each set's worlds once from it (`clojure -M:capture`, radius 12: 25x25 columns a landing) and caches them under a key of the world's files and `capture.clj`; each sim runner restores every set and plays its share of the 192 worlds (`--runner k --runners n`, `shard`), each world with seed after seed (`jobs`), on every core, until the budget is spent. Its runs are an artifact (`runs.edn`).

## Failures become issues
Every failed run carries `:run/signature` (`failure/signature`: the target it was on, why it stopped, the last intent's kind); the real race's RESULT lines carry it too. A report job after each race (`simrace.yml` and `race.yml`) runs `bb failures report <runs> <title>` (`bb/clojurecraft/failures.clj`): the failed runs grouped by signature, the most frequent first; one open issue labelled `bot-failure` per signature, found again by a marker in its body (`<!-- signature [...] -->`); each report adds one section to that issue's body (how many runs, how many landings, up to five reproductions: the sim race's `--world f --seed s --seeds 1` at the commit, or the real bot's recording), and opens at most ten new issues.

## First result
The local 49-column world, two runs to the Nether, 16 s wall: both reached wood, kit, pickaxe, cobblestone, stone pickaxe and furnace in about 15 simulated minutes, then failed `[:ingots :no-pickaxe]`, the reason five of runner 13's bots stopped with in race 38340947798.

## Gotchas
- `clojure -M:test -n ns -v var` runs the whole namespace and the var: the runner takes the union. Name vars alone.
- A world's edge is where its capture stopped: past it `block-at` is nil, unloaded to the bot. A run that needs more than the radius gave it stops at the edge.
