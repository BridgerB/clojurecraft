---
title: Gym on runners
description: How gym.yml runs a goal on real terrain on GitHub runners - one server and one landing per run, paired landings from a cached pregenerated world, the fixture piped into the bot, a judge that needs both RESULT and the server's truth, and a report folded from every run.
type: reference
tags: [bot, tooling, ci, gym]
aliases: [gym.yml, gym-world.yml, test.yml, wood.yml, clojure -M:gym, GYMRESULT, result.edn, landing set, landings.edn, truth command, outcome, Wilson, paired landings, CI]
status: verified
lastUpdated: 2026-10-11
verifiedAgainst: HEAD
sourceRefs:
  - src/clojurecraft/gym.clj#def gyms
  - src/clojurecraft/gym.clj#defn outcome
  - src/clojurecraft/gym.clj#defn report
  - src/clojurecraft/gym.clj#defn landing!
  - src/clojurecraft/gym.clj#defn trial!
  - src/clojurecraft/gym.clj#defn located-in
  - src/clojurecraft/gym.clj#defn judge!
  - src/clojurecraft/landings.clj#defn landings!
  - src/clojurecraft/landings.clj#defn landing-for
  - ci/gym.edn#{:landing-sets {"A" {:center [20000 20000] :n 20 :spacing 640 :biome "#minecraft:is_forest" :pregen-r 96}
  - ci/gym.edn#"W" {:center [26000 20000] :n 20 :spacing 640 :biome "#minecraft:is_beach" :pregen-r 96}
  - bb/clojurecraft/gym_world.clj#a forceload was left behind
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

Three workflows. `test.yml` runs the suite and the brain checker on every push, spread over nine runners ([[test-fixtures]]: the parallel runner, and the forests as eight shards). `gym.yml` runs real runs of goals on real terrain: on push every goal once (the smoke test `wood.yml` used to be), on dispatch one goal `runs` times (`goal`, `runs`, `landing-set`, `label`). `gym-world.yml` builds a landing set's world once and caches it.

## How a batch runs
1. **plan**: refuses when more than 20 jobs are already running in the repository, then `clojure -M:gym plan --goals ... --runs N` prints the shard matrix; only goal, run number and job minutes cross the job boundary.
2. **world** (`gym-world.yml`): restores the world cache for the set, or on a miss builds it with `bb prepare-world` (`bb/clojurecraft/gym_world.clj`) and saves it. The key hashes `ci/gym.edn`, `bb/clojurecraft/server.clj`, `bb/clojurecraft/gym_world.clj`, `landings.clj` and `fixture.clj`. A weekly run restores it to keep it from eviction.
3. **shard** (one runner per run, up to 20 at once, `fail-fast: false`): restores the world with `fail-on-cache-miss` (shards never build terrain), starts the server, insists `forceload query` is empty, then `clojure -M:gym trial` (`gym/trial!`, the same trial the fleet runs): the bot starts as its own JVM (`--events stdin --record`), `landing!` lands it (wait for the bot, survival, clear, give prerequisites, teleport onto `landings[run]`, spawnpoint) and its `:go` with `:go/at` goes down its stdin, and `judge!` waits for RESULT, runs every truth command while the bot holds, writes `result.edn` and prints one `GYMRESULT` line. A `:disconnect` is run once more on the same landing, under `trial/retry/`. The trial directory (recordings compressed with zstd) is uploaded with the server log and the forceload checks.
4. **aggregate**: downloads every shard's artifact, `clojure -M:gym report` folds the `result.edn` files (a retried trial's retry, not its first attempt: `gym/located-in`) into markdown (per goal: pass k/n with a 95% Wilson interval, a row per run with its truth replies, the outcome and reason tallies), writes it to the run summary and comments it on the branch's open PR.

## Judging
`gym/gyms` is the registry, data like the goal table: goal, `--until`, prerequisites, timeout, and truth commands that count items without removing them (`clear <name> <item> 0` answers "Found N matching item(s)"). `gym/outcome` is one pure function: `:harness` when the landing failed, `:disconnect` when the bot never printed RESULT or lost its socket, `:death`, `:timeout`, `:pass` only when RESULT is ok and every truth passes, else `:fail`. The bot saying ok while the server disagrees is `:fail`, with the server's reply in the row. `:harness` and `:disconnect` are not counted.

The iron kit (issue #6) has a row per stage, each given what the stages before it make: `furnace` (eight cobblestone, a table; judged by the cobblestone spent and no furnace held, since `clear` cannot see a placed block), `ingots` (a furnace, four raw iron, a coal), `bucket` (three ingots, a table), `water-bucket` (a bucket, two stone pickaxes, which the fill row holds), `flint-and-steel` (an ingot, a gravel), and `iron-kit` (a stone pickaxe, a table, eight cobblestone, four logs; forty minutes, since batch iron-a5's three runs holding flint and steel and the bucket were still climbing out at twenty). The push runs the four that need no particular terrain; `water-bucket` and `iron-kit` need water near the landing and are dispatched.

## Landing sets
`ci/gym.edn` names each set: a centre far from world spawn, n grid cells spacing apart, the biome cells snap to, and a pregeneration radius. A is forests (20000, 20000); W is beaches (26000, 20000), steve's landing set W for the water trial (`:shore`, [[water-escape]]), whose truth commands are the server's own dry-land test (`execute ... unless block ~ ~ ~ minecraft:water unless block ~ ~1 ~ minecraft:water`) and `OnGround 1b`. `landings/landings!` snaps each cell with `locate biome`, finds the surface with a marker entity, keeps it when y >= 55 and no fixture landing check passes (else tries the fixture's nearby offsets), and pregenerates the chunks around it. Run k always lands on `landings[k]` (`landing-for`, wrapping), so batches are paired.

## Recordings as regression inputs
A gym run's recording can be promoted into `test/recordings/<name>/` (`run.edn.gz`, gzip because `record/entries` reads it natively, plus the run's `result.edn`); the corpus stays under 10 MB. `recordings_test` folds each one: every intermediate world must satisfy the world spec, and the fold must reach exactly the RESULT the run printed, which must also be the one the gym judged. The first entry is run 3 of batch wood-a1 (1.2 MB). A replay is open-loop, so when the bot's behaviour changes on purpose a diverging recording is re-promoted from the next batch.

## Gotchas
- The recording ends at RESULT and stores it ([[record-replay]]), so a downloaded `run.edn.zst` replays to exactly the RESULT in `result.edn` (`:replay/result :identical`).
- `--strict true` (on push) makes the judge exit 1 for `:fail`, `:timeout` and `:death`, so the push smoke goes red; dispatched batches report instead.

## See also
- [[harness-landing]] - the fixture the gym's `land` builds on.
