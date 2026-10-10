---
title: Gym on runners
description: How gym.yml runs a goal on real terrain on GitHub runners - one server and one landing per run, paired landings from a cached pregenerated world, the fixture piped into the bot, a judge that needs both RESULT and the server's truth, and a report folded from every run.
type: reference
tags: [bot, tooling, ci, gym]
aliases: [gym.yml, gym-world.yml, test.yml, wood.yml, clojure -M:gym, GYMRESULT, result.edn, landing set, landings.edn, truth command, outcome, Wilson, paired landings, CI]
status: verified
lastUpdated: 2026-10-10
verifiedAgainst: 78b853c
sourceRefs:
  - src/clojurecraft/gym.clj#def gyms
  - src/clojurecraft/gym.clj#defn outcome
  - src/clojurecraft/gym.clj#defn report
  - src/clojurecraft/gym.clj#defn land!
  - src/clojurecraft/gym.clj#defn judge!
  - src/clojurecraft/landings.clj#defn landings!
  - src/clojurecraft/landings.clj#defn landing-for
  - ci/gym.edn#{:landing-sets {"A" {:center [20000 20000] :n 20 :spacing 640 :biome "#minecraft:is_forest" :pregen-r 96}}}
  - scripts/ci/gym-run.sh#disconnected: running once more on the same landing
  - scripts/ci/prepare-world.sh#a forceload was left behind
  - ".github/workflows/gym.yml#fail-on-cache-miss: true"
  - ".github/workflows/gym.yml#max-parallel: 20"
  - test/clojurecraft/gym_test.clj#outcome-is-judged-twice
  - test/clojurecraft/recordings_test.clj#every-recorded-run-replays
related:
  - "[[bot/tooling/_moc|Tooling]]"
  - "[[harness-landing]]"
  - "[[record-replay]]"
  - "[[result-line]]"
---

# Gym on runners

Three workflows. `test.yml` runs `clojure -M:test` and the brain checker on every push. `gym.yml` runs real runs of goals on real terrain: on push every goal once (the smoke test `wood.yml` used to be), on dispatch one goal `runs` times (`goal`, `runs`, `landing-set`, `label`). `gym-world.yml` builds a landing set's world once and caches it.

## How a batch runs
1. **plan**: refuses when more than 20 jobs are already running in the repository, then `clojure -M:gym plan --goals ... --runs N` prints the shard matrix; only goal, run number and job minutes cross the job boundary.
2. **world** (`gym-world.yml`): restores the world cache for the set, or on a miss builds it with `scripts/ci/prepare-world.sh` and saves it. The key hashes `ci/gym.edn`, `scripts/server.sh`, the prepare script, `landings.clj` and `harness.clj`. A weekly run restores it to keep it from eviction.
3. **shard** (one runner per run, up to 20 at once, `fail-fast: false`): restores the world with `fail-on-cache-miss` (shards never build terrain), starts the server, insists `forceload query` is empty, then `scripts/ci/gym-run.sh`: `clojure -M:gym land` (the fixture: wait for the bot, survival, clear, give prerequisites, teleport onto `landings[run]`, spawnpoint, print `:go` with `:go/at`) piped into the bot (`--events stdin --record`), and `clojure -M:gym judge` (wait for RESULT, run every truth command while the bot holds, write `result.edn`, print one `GYMRESULT` line). A `:disconnect` is run once more on the same landing. The recording is compressed with zstd and uploaded with the result, logs and the forceload checks.
4. **aggregate**: downloads every shard's artifact, `clojure -M:gym report` folds the `result.edn` files into markdown (per goal: pass k/n with a 95% Wilson interval, a row per run with its truth replies, the outcome and reason tallies), writes it to the run summary and comments it on the branch's open PR.

## Judging
`gym/gyms` is the registry, data like the goal table: goal, `--until`, prerequisites, timeout, and truth commands that count items without removing them (`clear <name> <item> 0` answers "Found N matching item(s)"). `gym/outcome` is one pure function: `:harness` when the landing failed, `:disconnect` when the bot never printed RESULT or lost its socket, `:death`, `:timeout`, `:pass` only when RESULT is ok and every truth passes, else `:fail`. The bot saying ok while the server disagrees is `:fail`, with the server's reply in the row. `:harness` and `:disconnect` are not counted.

## Landing sets
`ci/gym.edn` names each set: a centre far from world spawn (20000, 20000), n grid cells spacing apart, the biome cells snap to, and a pregeneration radius. `landings/landings!` snaps each cell with `locate biome`, finds the surface with a marker entity, keeps it when y >= 55 and no harness landing check passes (else tries the harness's nearby offsets), and pregenerates the chunks around it. Run k always lands on `landings[k]` (`landing-for`, wrapping), so batches are paired.

## Recordings as regression inputs
A gym run's recording can be promoted into `test/recordings/<name>/` (`run.edn.gz`, gzip because `record/entries` reads it natively, plus the run's `result.edn`); the corpus stays under 10 MB. `recordings_test` folds each one: every intermediate world must satisfy the world spec, and the fold must reach exactly the RESULT the run printed, which must also be the one the gym judged. The first entry is run 3 of batch wood-a1 (1.2 MB). A replay is open-loop, so when the bot's behaviour changes on purpose a diverging recording is re-promoted from the next batch.

## Gotchas
- The recording ends at RESULT and stores it ([[record-replay]]), so a downloaded `run.edn.zst` replays to exactly the RESULT in `result.edn` (`:replay/result :identical`).
- `--strict true` (on push) makes the judge exit 1 for `:fail`, `:timeout` and `:death`, so the push smoke goes red; dispatched batches report instead.

## See also
- [[harness-landing]] - the fixture the gym's `land` builds on.
