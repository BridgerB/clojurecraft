---
title: Sim race
description: Real worlds captured from a real server once (capture.clj) and the whole bot played in them against the server model with no Java server (simrace.clj), from empty hands at a landing to the race's target, as many runs as the cores allow; each run a pure function of world, seed and code.
type: reference
tags: [bot, tooling, sim, race]
aliases: [capture, capture.clj, simrace, simrace.clj, sim race, captured world, world file, SIMRUN, SIMRACE, :capture/columns]
status: verified
lastUpdated: 2026-10-14
verifiedAgainst: HEAD
sourceRefs:
  - src/clojurecraft/capture.clj#defn stops
  - src/clojurecraft/capture.clj#defn take-landing!
  - src/clojurecraft/capture.clj#defn chunk-tap
  - src/clojurecraft/simrace.clj#defn run
  - src/clojurecraft/failure.clj#defn signature
  - src/clojurecraft/failure.clj#defn mark
  - test/clojurecraft/simrace_test.clj#a-failure-the-server-caused-is-marked-not-the-bots
  - src/clojurecraft/simrace.clj#defn shard
  - "src/clojurecraft/simrace.clj#A failed run's moment as a value to keep"
  - "src/clojurecraft/simrace.clj#A snapshot played on for budget-ms of simulated time"
  - src/clojurecraft/sim.clj#defn play-on
  - bb/clojurecraft/issue_check.clj#defn fetch!
  - test/clojurecraft/issues_test.clj#no-issues-failing-moment-fails-the-same-way-again
  - test/clojurecraft/simrace_test.clj#a-failed-runs-moment-round-trips-and-plays-on-to-the-same-stop
  - src/clojurecraft/simrace.clj#defn jobs
  - bb/clojurecraft/failures.clj#defn report-runs!
  - bb/clojurecraft/churn.clj#defn counted
  - bb/clojurecraft/failures.clj#defn with-tally
  - bb/clojurecraft/win.clj#defn winners
  - .github/workflows/simrace.yml#bb churn batch sim
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
`simrace.yml` runs on every push to main but a docs-only one, beside `race.yml`, and both restart with the new code and run without end (each `next` job dispatches the next round on main when the runners end; `RACE_CONTINUOUS=false` stops it): `ci/simrace.edn` gives `:simrace/runners` (8) runners, the sets (R1-R4), the target, the simulated minutes a run may take and the wall budget a runner plays (330 minutes). `world.yml` builds each set's server world once; `simworld.yml` captures each set's worlds once from it (`clojure -M:capture`, radius 12: 25x25 columns a landing) and caches them under a key of the world's files and `capture.clj`; both are dispatched by hand when a key changes, never looked up by a race. Each sim runner restores every set (a miss fails the job at once) and plays its share of the 192 worlds (`--runner k --runners n`, `shard`), each world with seed after seed (`jobs`), on every core, until the budget is spent: a core that ends a run takes the next at once, so nothing waits for the slowest run. Every 20 minutes, staggered by runner (75 s off the real race's), the runner reports the runs that ended since its last report (`bb churn batch sim ...`, [[race]]). Its runs are an artifact at the job's end (`runs.edn`, the snapshots), the name a batch's reproductions give.

## Failures become issues
Every failed run is marked (`failure/mark`): `:run/signature` (`failure/signature`: [the target it was on, why it stopped]; one bug met in several intents is one signature, the intents listed inside its issue) and `:run/infra?` (the server closed or disconnected it: not the bot's play); the real race's RESULT lines carry both too. Each runner's batches (`bb churn`, every 20 minutes in `simrace.yml` and `race.yml`) file the runs that ended since the last (`failures/report-runs!`, `bb/clojurecraft/failures.clj`; `bb failures report <runs> <title>` does the same for a directory):
- failed runs grouped by signature, the most frequent first; infrastructure stops (a cut bot among them: one the job's end stopped) and one-offs (fewer than `min-runs`, 3, in the job so far: `counted`) are counted in the output, never filed;
- one issue labelled `bot-failure` per signature, found again by a marker in its body (`<!-- signature [...] -->`), open or closed: a closed one whose signature comes back in runs of code committed after its closing is reopened and its section says regressed (`regressed?`; the report gets the commit's date as its third argument), while runs of older code add their section and leave it closed, since they never ran the fix: race 38477665442, on code from before #76 merged, reopened #36 with 66 bots that had run the old planner;
- the issues are read again right before one is opened, so two reports finishing together do not open the same one twice;
- each batch adds one comment to the issue (how many runs, sim or real, the last intents, how many landings, up to five reproductions: the sim race's `--world f --seed s --seeds 1` at the commit, or the real bot's recording; the run's page) and brings the body's tally up to date (`with-tally`: rounds, runs, first and last seen, kept in a `<!-- tally -->` marker; issues from before keep their old sections below it), so every batch notifies, and labels it `sim`, `real` or both: a failure only the model produces may be the model's, not the bot's; when a real run wins, the report tags the commit and releases the winners' recordings (`bb win`, `win/winners`), the proof kept past the artifacts' seven days (issue #116);
- at most ten new issues a batch.

## The issue check: a validation over a recorded moment
A fix's check (issue #77) never runs from spawn; beside it a fix/<n>-* pull request runs `unit.yml`, every test but the forest property and the static checks, the same job `test.yml` runs on every push, so a fix that breaks an older assertion is seen before main is (#98: the bank test asserted the mechanism #75 replaced, and main was red after the merge). The moment a sim run failed is a value: when a run fails, `simrace/play!` writes a **snapshot** beside its runs (`<out>/snapshots/<set>-<n>-<seed>.edn.gz`, `simrace/snapshot`): the bot's world and the model's state one tick before the stop (`sim/run+`'s `:before`, the plan still running), the captured world's file and the chunk keys held (chunks and columns are reattached from the file, never copied: 40 KB for a 13-minute run), the facts as `d/serializable`, the seed, the target, the commit and the signature. `simrace.yml` uploads them; `bb failures` names each sim reproduction's snapshot and artifact in the issue; `bb issue-check fetch <n>` brings an issue's snapshots into `test/issues/<n>/`, which the fix's pull request commits, as `test/recordings` holds recordings. `issues_test` plays every snapshot on (`simrace/play-snapshot`: `sim/play-on` for two simulated minutes until the plan is done or failed) and fails when the signature recurs (`simrace/recurs?`), naming the issue; still running at the budget, or failed otherwise, both pass: the run got past this moment. The check is only as good as its moments: #101 closed 33 issues with nothing under `test/issues/` to play, so `issue.yml` now reads the request's `Fixes #n` lines and fails when an issue labelled `sim` carries no snapshot (a real-only issue is warned about: it closes on the recordings' word) (issue #116). `issue.yml` on a `fix/<n>` pull request restores the captured worlds and runs that test alone, seconds of play; `test.yml` runs it on main too, so a fixed issue's snapshot stays and a bug that comes back fails it (Simulant's rule: write the validation, run it against past results, never re-run the system). `clojure -M:simrace --snapshot f` plays one by hand (one SNAPSHOT line). Before this, `issue.yml` replayed ten reproductions from spawn to the Nether: 11 minutes, nearly all of it history the sim race had already run.

## First result
The local 49-column world, two runs to the Nether, 16 s wall: both reached wood, kit, pickaxe, cobblestone, stone pickaxe and furnace in about 15 simulated minutes, then failed `[:ingots :no-pickaxe]`, the reason five of runner 13's bots stopped with in race 38340947798.

## Gotchas
- `clojure -M:test -n ns -v var` runs the whole namespace and the var: the runner takes the union. Name vars alone.
- A world's edge is where its capture stopped: past it `block-at` is nil, unloaded to the bot. A run that needs more than the radius gave it stops at the edge.
