# The gym on GitHub runners: one job per goal run, prerequisites over RCON, landing sets, judged by RESULT + RCON truth, recordings as artifacts

## Summary

`wood.yml` already proves the shape: a runner boots its own vanilla 26.1.2 server, the bot prints one `RESULT` line, and an independent RCON read judges it. This issue turns that one job into a **gym**: `gym.yml` is dispatched with `{goal, runs, landing-set, label}`, fans out to one runner per run (max 20), each run grants the goal's prerequisites over RCON, lands the bot on a pre-checked landing from a pregenerated world, runs just that goal under its timeout, and prints a `GYMRESULT` EDN line that is judged by the bot's `RESULT` **and** a server-side truth command. Every run uploads its `--record` file; an aggregate job merges the shards into one table (pass rate with interval, outcomes by cause) in the step summary and a PR comment. Failed recordings become replayable inputs for `clojure -M:test`. The pure sim (`sim.clj`) stays the iteration loop; the runner gym is only for truth across real terrain.

## Why now / what the siblings learned

The current CI is fast and honest but measures one thing once. Run 37997802511 took **48 s** wall: server up in 11 s, bot 17 s, judge 2 s (cold caches: ~1m40s). The judge is `clojure -M:rcon ... data get entity Clj_wood Inventory | grep _log` (`.github/workflows/wood.yml:67-68`), independent of the bot's own `RESULT {... :ok true}` (`wood.yml:63`). That is the right pair of signals; it just needs N landings, a per-goal kit, and a report.

steve's gym on runners is the thing to copy, minus its weight:

- **Plan → matrix shards → aggregate.** `gym.yml:50-68` runs `scripts/ci/plan.ts` to emit a matrix; `gym.yml:70-77` runs shards with `fail-fast: false`, `max-parallel: 20`; `gym.yml:197-247` downloads `gym-*` artifacts, merges them (`aggregate.ts:2-4`: "Idempotent: batches rows are keyed by run_id"), prints `shard-summary.ts` to `$GITHUB_STEP_SUMMARY` and `gh pr comment`s the open PR. `plan.ts:3-5`: "Shard k of an arm replays landings [k*size, k*size+runs) of the set, so every arm runs the same ordered landings (paired)." `fleet.yml:4` adds the gotcha: "The matrix carries worker numbers only (job outputs are capped at 1 MB)".
- **Per-run harness.** `gym/run.ts:47-53` resets then grants (`gamemode survival`, `clear`, `give` per prereq); `run.ts:74-101` forceloads the landing chunk, waits `execute if loaded`, teleports with `execute positioned X 0 Z positioned over motion_blocking_no_leaves run tp`, rejects `y < 55`, water, lava; `run.ts:108` pins `spawnpoint`; `run.ts:190-205` races the step against `timeoutMs` and prefers `truthPass` over the client's view; `run.ts:233-235` releases exactly the chunks it forceloaded. Truth tricks: `registry.ts:77-78` counts items via `clear <name> <item> 0` → `Found N` (removes nothing); `registry.ts:521-523` checks `execute as <name> at @s if dimension minecraft:the_nether`.
- **Classification.** `gym-cli.ts:62` prints `GYMRESULT {json}`; `gym-batch.ts:228` sorts runs into `pass | harness | disconnect | death | timeout | fail`; `gym-batch.ts:190-194` reruns a `DISCONNECTED` slot because "A dropped connection ... says nothing about the step"; `gym-batch.ts:282-292` reports pass with a Wilson interval, harness rows excluded and counted.
- **Landing sets.** `scripts/ml/landings.ts:8-13`: a landing passes when the surface is not water/lava/ice, no surface water within 8, no ruined portal within 200; `landings.ts:112-124` snaps each grid cell to `#minecraft:is_forest` or plains ("an ocean cell rejected 34 of 36 candidates"); `landings.ts:155` rejects `y < 55`. `prepare-env.yml:119-138,156-161` pregenerates each set once into one cache entry `gym-env-<hash>`, with a weekly cron (`:15-16`) because "restore keeps the entry from 7-day eviction"; hot workflows restore with `fail-on-cache-miss: true` (`gym.yml:94-99`).
- **Fixtures by RCON fill.** `step-tests/registry.ts:60-72` builds `gather_wood` with `fill x+3 y z x+3 y+4 z oak_log` plus a leaf cap; `step-test.ts:280-287` stands the bot on a thick stone slab (y70-99) because a thin one gets dug through.
- **What hurt them.** `scripts/ci/server.sh:53-55`: "26.x pauses an empty server after 60 s; forceloaded chunks then barely load (a 4-chunk patch took 153 s)" → `pause-when-empty-seconds=0` (ours already has it, `scripts/server.sh:48`). `data/gym/NOTES.md`: b13 lost 7/10 runs to "692 leaked forceload chunks ... heap full"; two batches on one server took tick p99 from 23 ms to 6378 ms → "one gym batch at a time"; b4-5 ran 20 min against a stale world after a silent disconnect; "no good landing" losses were late RCON replies handed to the next command (our `rcon.clj:48-58` already matches ids). `ci/capacity.json`: 8 bots per 4-vCPU runner is healthy, 12 is not; ruststeve caps `MAX_BOTS=6` (`gym.sh:29`) and judges by dimension / `RACE GOAL REACHED` (`LOOP.md:9`).

Skipped on purpose: SQLite batches, D1 telemetry, bandit params, arms, jlink. Our bot is a reducer over recorded events; the recording *is* the telemetry.

## Design

**Workflow `gym.yml`** (`workflow_dispatch` inputs `goal` default `wood`, `runs` default `6`, `landing-set` default `A`, `label` required; `timeout-minutes` per goal from the plan). Three jobs:

1. `plan`: checkout + restore the deps cache, `clojure -M:gym plan --goal $GOAL --runs $RUNS --landing-set $SET` prints `matrix={"run":[1,...,N]}` to `$GITHUB_OUTPUT`. Only run numbers cross the job boundary; the shard recomputes everything from `ci/gym.edn`.
2. `shard` (matrix over `run`, `fail-fast: false`, `max-parallel: 20`, `name: ${label}-${run}`): restore caches (deps `clj-<hash deps.edn>`, jar `server-jar-<sha1>`, world `gym-world-<set>-<hash ci/gym.edn,scripts/server.sh,scripts/ci/prepare-world.sh>` with `fail-on-cache-miss`), `cp -r cache/worlds/$SET work/world`, `scripts/server.sh start`, then `clojure -M:rcon ... forceload query` must say "No force loaded" (abort otherwise), then `clojure -M:gym run --goal wood --landing-set A --run 3 --name Clj_g3 --record work/run.edn`, then `zstd -19 work/run.edn`, upload artifact `gym-${label}-${run}` (`result.edn`, `run.edn.zst`, `bot.log`, `work/world/logs/latest.log`, retention 3 days), stop the server.
3. `aggregate` (`needs: shard`, `if: always()`): `download-artifact pattern: gym-${label}-*`, `clojure -M:gym report artifacts/ > summary.md`, append to `$GITHUB_STEP_SUMMARY`, `gh pr comment` when the branch has an open PR, upload `summary.md` + all `result.edn`.

**`ci/gym.edn`** (the plan file, data): `{:landing-sets {"A" {:center [20000 20000] :n 12 :spacing 640 :pregen-r 96 :biome "#minecraft:is_forest"}} :goals {:wood {...}}}`. **`scripts/ci/prepare-world.sh`** boots a server, runs `clojure -M:gym landings --set A` (RCON: snap grid cells to the biome, reject water/lava/ice at `motion_blocking_no_leaves`, surface water within 8, `y < 55`; forceload the ±pregen-r square until `execute if loaded` passes, release it; write `ci/landings-A.edn` as `[[x z y] ...]`), `save-all flush`, assert `forceload query` is empty, stop, and saves `work/world` with `actions/cache/save`. A separate `prepare-world.yml` runs it on `workflow_dispatch`, on changes to those files, and weekly to keep the entry warm.

**Namespace `clojurecraft.gym`** (I/O, the fifth allowed one; it composes `harness`, `rcon`, `main`). The registry is a table, extensible from another namespace exactly like `plan/goals`:

```clojure
(def gyms
  [{:gym/goal :wood
    :gym/prereqs []                         ; RCON give list, "item n"
    :gym/landing :forest                    ; landing kind in the set
    :gym/timeout-ms 150000
    :gym/truth {:truth/cmd "clear {name} #minecraft:logs 0"   ; removes nothing
                :truth/re "Found ([1-9]\\d*)"}}])              ; string: EDN has no regex literal
```

`run` does: connect; wait `:player/loaded?`; RCON `gamemode survival`, `clear`, `give` each prereq, `spawnpoint` at the landing; land exactly as `harness/land!` does today (`harness.clj:24-42`) but at `landings[run]` instead of `locate biome`; wait for the teleport and loaded chunk; send `{:event/kind :go}`; run the loop until `plan/done?`, `plan/failed?` or the deadline. Then print `RESULT` (unchanged) and, after the truth command, one line `GYMRESULT {:gym/goal :wood :gym/run 3 :gym/landing [x z] :gym/outcome :pass :gym/ms 17210 :gym/reason :goal :gym/truth "Found 1 matching item(s)" :gym/commit "abc1234" :gym/seed "..."}`.

**Judging.** `:gym/outcome` is derived in one pure function from `(result, truth-match?, bot-state)`: `:pass` only when `(:ok result)` **and** the truth regex matches; `:fail` when the bot said ok but the server disagrees (a desync, the most valuable row) or the plan failed; `:timeout` when `:reason :timeout`; `:death` when the world saw a death (new attribute `:stats/deaths`, accreted); `:disconnect` when `:bot/disconnected` or `:bot/closed`; `:harness` when landing or RCON failed before `:go`. `:disconnect` and `:harness` never count against the goal; the shard reruns a `:disconnect` once on the same landing before uploading. This is `gym-batch.ts:228` as data instead of a regex chain.

**Report.** `clojure -M:gym report` folds every `result.edn` into one markdown table (run, landing, outcome, seconds, reason, truth) and a line `pass k/n [Wilson 95%]; harness/disconnect m`, then a tally by outcome and by `:plan/reason`.

**Caches.** Deps and jar keys as in `wood.yml:26-36`; the world key covers the plan file and the two scripts that shape it; restores in shards are never allowed to build (a miss fails in seconds, as `gym.yml:1-4` says).

**Concurrency.** One bot per server per runner by default; a `bots` input may share a server up to 6 (ruststeve's cap, steve's measured 8 on a 4-vCPU runner). Job-level `concurrency: {group: gym-${label}}` and the plan job refuses when `gh run list --status in_progress` already holds 20 jobs.

**Recordings as regression inputs.** `record_test.clj:7-22` already asserts a replay equals the direct fold. A failed run's `run.edn.zst` + `result.edn` is promoted by hand into `test/recordings/` (size-capped: `data/runs/four.edn` is 8.0 MB / 4320 events, 1.1 MB after `zstd -19`), and `recordings_test.clj` folds each: replay must not throw, every intermediate world must satisfy `::spec/world`, and the final `RESULT` must equal the stored one. That is the accretion guarantee from `docs/hickey.md` ("a recorded race from last month still replays"). A recording cannot prove a *fix* (replay is open-loop: the server's replies were to the old bot's actions); a fix is proven by a sim scenario built from the recording's chunk packets (`world/column-bytes`) and then by the next gym batch on the same landing set.

## Steps

1. `clojurecraft.gym` registry + `run` + `GYMRESULT`; `--landing x,z` override in `harness/land!`. Verify: local server, `clojure -M:gym run --goal wood --landing 100,100` prints `RESULT` then `GYMRESULT` with `:gym/outcome :pass`; `--timeout-ms 1` yields `:timeout`.
2. Outcome function + `report` with unit tests on hand-written `result.edn` sets (pass/fail/timeout/disconnect/harness). Verify: `clojure -M:test` green; report prints the Wilson line.
3. `clojure -M:gym landings` + `scripts/ci/prepare-world.sh` + `prepare-world.yml`. Verify: step summary shows `12/12 landings`, `forceload query` empty, cache entry saved with its size; a second dispatch is a hit.
4. `gym.yml` plan/shard/aggregate with `runs: 2`. Verify: two shard jobs, artifacts named `gym-<label>-1/2`, each with `run.edn.zst`, summary table in the run page; `gh api .../timing` shows billable 0.
5. Baseline: `runs: 12` on set A for `wood`. Verify: 12 rows, report posted on the PR, per-job wall ≤ 3 min, no `:harness` rows.
6. Recording corpus: promote one failed `run.edn.zst` + its `result.edn` to `test/recordings/`, add `recordings_test.clj`. Verify: `clojure -M:test` replays it and matches the stored result; corpus stays under 10 MB.
7. Replace `wood.yml`'s push job with `gym.yml` at `runs: 1` on push (keeps the 48 s smoke).

## Acceptance criteria

- `gym.yml` dispatched with `{goal: wood, runs: 12, landing-set: A, label: x}` produces 12 shard jobs, one aggregate summary with `pass k/12 [lo%, hi%]` and the outcome tally, and a PR comment when a PR is open.
- Every shard uploads `result.edn` and `run.edn.zst`; `clojure -M:replay` of a downloaded recording prints the same `RESULT` as `result.edn`.
- `:pass` requires both `RESULT :ok true` and the truth regex; a client/server disagreement appears as `:fail` with the truth text in the row.
- Shards restore the pregenerated world from cache; a cache miss fails the shard in under 30 s instead of generating terrain.
- Landings are paired: run k lands on `landings[k]` on every dispatch of the same set.
- No shard leaves a forceload behind (`forceload query` asserted empty at start and logged at stop).
- `test/recordings/` replays in `clojure -M:test` with spec validation on every intermediate world.
- No AI attribution anywhere; registry rows are data in `clojurecraft.gym`, goals keep dispatching through `plan/goal-done?`.

## References

- Ours: `.github/workflows/wood.yml`, `scripts/server.sh:35-63`, `src/clojurecraft/main.clj:62-111`, `harness.clj:24-61`, `record.clj:17-33`, `plan.clj:12-27,89-93`, `sim.clj:111-128`, `rcon.clj:48-74`, `test/clojurecraft/record_test.clj`, `docs/hickey.md`.
- steve (`/Users/bridger/Developer/mc/upstream/steve`): `.github/workflows/{prepare-env,gym,fleet}.yml`, `scripts/ci/{server.sh,plan.ts,prepare-world.ts,aggregate.ts,shard-summary.ts}`, `scripts/ml/landings.ts`, `src/lib/steve/gym/{registry,run}.ts`, `gym-batch.ts`, `gym-cli.ts`, `src/lib/steve/step-tests/registry.ts`, `src/lib/steve/step-test.ts`, `ci/{env,capacity}.json`, `data/gym/NOTES.md`, `LOOP.md:136-155`.
- ruststeve: `gym.sh`, `scripts/gym-from-snapshot.sh`, `LOOP.md:9,162-192`. Memory notes: `steve-github-runners`, `mc-box-gym-concurrency`.

## Open questions

- Landing-set center: steve uses `(20000, 20000)` so gym sites never meet the race region or world spawn (b1-7 and b12-5 cast at a polluted spawn). Same for set A, even with no race yet?
- Plan job in Clojure (needs the deps cache, ~10 s) or a `seq`-in-bash one-liner, given the matrix is only run numbers?
- `difficulty` stays `peaceful` (`scripts/server.sh:40`) for `wood`; which goal first needs mobs, and is that a per-goal field in `ci/gym.edn` or a dispatch input?
- Recording corpus: cap at 10 MB in git, or keep recordings out of git (release assets restored by a test alias) once there are more than a handful? Promote by hand, or have the aggregate job link them in the PR comment?
- Should `:gym/truth` become a vector (inventory and dimension) once a goal spans dimensions? Patterns stay strings so the registry remains EDN-loadable.
