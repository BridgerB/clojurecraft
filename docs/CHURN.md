# Churn: find the bot's failures on the runners, then fix them one by one

This is a playbook for an agent (Sonnet, Haiku, or anyone) to make the bot better at what it already does, without waiting on a human. There are two halves, and they run in a loop:

1. **Find** failures: run many real games at once on GitHub's runners (up to 20 at a time) and turn every failed run into a GitHub issue.
2. **Fix** a failure: take one issue, replay its recording to the moment it failed, write a test that fails the same way, fix the code, and prove the fix on the runners.

## The operating model

Testing is offloaded; coding is not. The people and agents writing code stay on a small, narrow happy path: the stage being built, made to work end to end, with unit and sim tests of exactly what was built. The churn on the runners is what widens it: batches on many landings, then on landing sets the bot has never seen, turn up the cases nobody thought of, each as an issue. Nobody sits and waits on a batch: dispatch it, keep coding, and when issues have piled up, knock out a handful at once (each its own commit), push, and let the next batch turn up the next handful.

Everything in the fix half was done by hand on 2026-10-11 to take the iron kit from 0 of 5 runs passing to 10 of 15; the worked examples below are from that session.

Read `CLAUDE.md` and `docs/hickey.md` first: the fix must follow the project's style (one change per commit, the brain note updated in the same commit, data over code).

## Ground rules

- **The runners test; you code.** Never run the full suite locally and wait on it. Locally run only the one test you are writing (`scripts/clj -M:test -v ns/test-name`) and the replays. Push, and let `test.yml` and the gym run on the runners. Arm `gh run watch <id> --exit-status` in the background so you are told when a run ends, and keep working meanwhile.
- **Never more than 20 jobs at once** in this repository: the gym's plan job refuses to start a batch when more than 20 jobs are already running (`.github/workflows/gym.yml`), and a push's own CI takes about 9 (`test.yml`) plus about 10 (the push gym).
- **Never touch the shared game box** (`144.24.32.76`) or the steve and ruststeve ports. The runners have their own servers.
- **No AI attribution** in commits, issues, comments or PRs. The commit-msg hook refuses it.

## Part 1: find failures on the runners

### Run a batch

A batch is N runs of one goal, each on its own runner with its own Minecraft server, each landing on its own spot. `gym.yml` takes:

| Input | Meaning |
|---|---|
| `goal` | what to reach: `wood`, `table`, `pickaxe`, `cobblestone`, `stone-pickaxe`, `furnace`, `ingots`, `bucket`, `water-bucket`, `flint-and-steel`, `iron-kit`, `cast-one`, `portal`, `nether`, `shore` (the registry is `gym/gyms` in `src/clojurecraft/gym.clj`: prerequisites given over RCON, the timeout, the truth commands) |
| `runs` | how many runs; run k always lands on landing k of the set, so batches are paired |
| `landing-set` | which landings (`ci/gym.edn`): `A` forests round (20000, 20000), `W` beaches round (26000, 20000); a new set is a new entry there |
| `label` | names the batch's artifacts (`gym-<label>-<goal>-<run>`) and its summary |

```bash
git push origin <branch>                                   # the runners test what is pushed
gh workflow run gym.yml --repo BridgerB/clojurecraft --ref <branch> \
  -f goal=iron-kit -f runs=15 -f landing-set=A -f label=<batch-name>
gh run list --repo BridgerB/clojurecraft --workflow gym.yml --event workflow_dispatch --limit 1 \
  --json databaseId,headSha                                # the batch's run id and the commit it runs
gh run watch <run-id> --repo BridgerB/clojurecraft --exit-status   # in the background: wakes you at the end
```

A trial's job ends "success" whether the bot passed or not (only the push gym fails on a failed run). The verdict is in the artifacts:

```bash
gh run download <run-id> --repo BridgerB/clojurecraft -n gym-<label>-<goal>-<run> -D <dir>/gym-<label>-<goal>-<run>
scripts/clj -M:gym report --dir <dir>          # pass k/n with a 95% interval, one row per run, outcomes, reasons
```

Each trial's artifact holds `trial/result.edn` (the judged row), `trial/bot.log` (the bot's stderr), `trial/run.edn.zst` (the recording), `trial/landed.edn`, and `work/world/logs/latest.log` (the server's own log: deaths, crashes, warnings). Artifacts are kept 3 days.

### Going wide

The same landings every batch make before and after comparable, but passing 15 landings can mean the bot learned those 15 places. So:

- **Batch size.** 5 runs say almost nothing (1 of 5 is anywhere from 4% to 62%). Use 15 to 20 for a decision.
- **Stages.** The iron kit has a gym row per stage (`furnace`, `ingots`, `bucket`, `water-bucket`, `flint-and-steel`) besides the whole `iron-kit`: a stage batch isolates one part and runs in minutes.
- **New landings.** Once a set passes, add a set elsewhere (another `:center`, another `:biome`: plains, taiga, savanna, mountains) to `ci/gym.edn` and run there. Failures on a set the bot has never seen are the honest ones. Changing `ci/gym.edn` rebuilds the cached world once.
- **Determinism.** The terrain is fixed by the seed (`typecraft`), but a run on a landing is not repeatable: loot rolls, mobs and the server's tick timing vary (vanilla has no deterministic mode). Judge by batches, never by one run.

### Turn every failure into an issue (to build)

Today the aggregate job writes a summary and comments it on the branch's PR. The next step is for it to open one issue per kind of failure, so a fixing agent can pick them up with no human in between. The design:

1. **The signature** of a failed run: `[goal outcome reason last-intent-kind]`, from `result.edn` (`:gym/outcome`, `:gym/result :reason`) and the recording's last intent. Runs with the same signature are one issue: a dozen `:timeout`s are one problem until shown otherwise.
2. **Triage by outcome** (from `gym/outcome`), before any issue:
   - `:pass`: nothing.
   - `:harness`, `:disconnect`: infrastructure, not the bot (a landing that failed, a lost socket). Not counted in the score; open no bot issue; repeated ones get one `infra` issue.
   - `:timeout`: the bot never failed, it ran out of time. Label `slow`; a slow run is a performance problem, not a logic bug, and is fixed differently (see below).
   - `:death`: the server log says how (`grep "Clj_g<run> " work/world/logs/latest.log`): "fell from a high place", "drowned", "burned". Label `death`.
   - `:fail`: a logic bug. Label `bug`; the reason (`:boxed`, `:no-water`, `:no-spot`, `:needs-tool`, `:no-path` ...) is the first clue.
3. **One issue per signature**, labelled `gym-failure` plus the outcome label, titled `gym: <goal> <outcome> <reason> (<last-intent>)`. Before creating, search for an open issue with that title; when there is one, comment the new occurrence on it instead. The body holds everything the fix half needs:
   - the batch's run id, the commit (`:gym/commit` in `result.edn`, also the first line of the recording, see below), the goal, the run number and landing;
   - the outcome, the reason and the run's seconds;
   - the last 20 lines of `probe story` (or of `bot.log` when the recording is missing);
   - the artifact name and the commands of Part 2's first steps, filled in;
   - a note that the artifact expires in 3 days (raise `retention-days` for failed trials if fixes lag).
4. **Permissions:** the workflow needs `issues: write`; `gh issue create` and `gh issue comment` with `GH_TOKEN: ${{ github.token }}`.

A sketch of the step, after the aggregate job folds the results:

```yaml
- name: open an issue per kind of failure
  env:
    GH_TOKEN: ${{ github.token }}
  run: |
    clojure -M:gym failures --dir artifacts --run-id ${{ github.run_id }} --commit ${{ github.sha }} > failures.edn
    # failures.edn: one row per signature, {:title .. :labels [..] :body .. :runs [..]}
    bb gym-issues failures.edn     # for each row: gh issue list --search title; comment if open, else gh issue create
```

`gym failures` and `bb gym-issues` do not exist yet; building them is the first task of this playbook's automation. Keep the signature and the triage pure functions in `gym.clj` (a row in, a signature out), tested like `gym/outcome`, with the `gh` calls alone below the I/O fence.

## Part 2: fix one failure

### 1. Read the issue, fetch the run

```bash
gh run download <run-id> --repo BridgerB/clojurecraft -n gym-<label>-<goal>-<run> -D /tmp/f/<label>-<run>
zstd -d /tmp/f/<label>-<run>/trial/run.edn.zst -o /tmp/f/<label>-<run>/run.edn     # .nix/zstd-bin/bin/zstd here
```

### 2. Replay it at the commit that ran it

A recording holds every packet the server sent and every event the bot saw, and the bot is a pure reducer, so folding the recording through the bot gives back its world at any moment. But only with the code that made the recording: newer code decides otherwise and the bot ends up somewhere else. So the recording names its code: its first line is `{:record/commit sha}` (the gym passes `--commit` to the bot; `record/header` reads it, and `probe story` prints it first). Replay in a worktree at that commit:

```bash
git worktree add --detach /tmp/wt-<sha> <sha>
cp dev/clojurecraft/probe.clj /tmp/wt-<sha>/dev/clojurecraft/    # when the probe is newer than the commit
cd /tmp/wt-<sha>
clojure -M:dev -m clojurecraft.probe story /tmp/f/<label>-<run>/run.edn | grep -v :started | tail -n 15
clojure -M:dev -m clojurecraft.probe around /tmp/f/<label>-<run>/run.edn <ms>    # cells round the feet at <ms>
```

`story` is every intent's start, end and reason, from memory's facts: the last failures say what went wrong and when (`:at` is the bot's clock in ms). `around` draws two vertical slices through the feet: `#` solid, `.` clear, `W` water, `L` lava, `~` awkward, `g` a block that falls, `?` not loaded.

For anything sharper, write a few lines against `clojurecraft.probe` and run them with `clojure -M:dev script.clj run.edn`:

```clojure
(require '[clojurecraft.probe :as probe] '[clojurecraft.scoop :as scoop] '[clojurecraft.path :as path])
(def w (probe/fold (first *command-line-args*) #(= :no-water (:plan/waiting %))))  ; the world at the wait
(prn (take 5 (scoop/sources w)) (count (scoop/pools-near w)))                     ; what the act saw
(prn (path/plan w (path/feet-cell (:player/pos w)) {:goal/kind :near :goal/pos [x y z] :goal/range 3}))
```

Ask the function that made the decision, with the world it made it in. Every act and rule is a pure function of the world, so this always works.

### 3. Pinpoint, then write the test that fails

State the cause in one sentence before touching code: *where* (the cell, the height), *when* (the intent and its stage), *why* (the rule that said no, or the missing case). Then reproduce it as small as possible, and run it against the unchanged code to see it fail:

- a **unit test** on the function that decided, with a hand-built world (`standing` in `stone_test.clj` and `scoop_test.clj`, `world/column` for blocks): fastest, and enough when the decision is the bug;
- a **sim test** (`sim_test.clj`: `sim/run` with a goal table, or `intent-run` for one intent) when the bug is in how the bot moves or how the server answers.

Name the test for the behaviour, and give it a comment naming the batch and run it came from.

### 4. Fix, check, commit, push

- Fix in a branch of the main checkout. Run the new test and the namespaces you touched (`-n ns`), the lint (`scripts/clj -M:lint --lint src dev test`) and Splint (`scripts/clj -M:splint src dev test/clojurecraft`), and the brain checker (`scripts/clj -M:brain`, all zeros).
- Update the brain note of the subsystem in the same commit (a sourceRef to the new function and test, a line on the failure), and add the failure to `resources/clojurecraft/problems.edn`'s `:problem/last-failure` of the stage.
- Commit: an imperative subject, a blank line, `-` bullets wrapped at 72. Push. CI runs the whole suite.

### 5. Validate on the runners

Dispatch a small batch (5 to 10 runs) of the same goal and landing set on the pushed commit, watch it in the background, and read the report. The run that failed should pass on its landing; the others should not get worse. Then comment the batch's score on the issue and close it with the commit.

### When it is not a bug

- **`:timeout`, slow passes.** Find where the time went: group `probe story` by minute (what started each minute). In the session below, one run spent 25 of its 40 minutes tunnelling for its last iron, and every block of stone took about 1.75 s; the fix was the dig's timing, not its logic.
- **`:harness`, `:disconnect`, a server crash.** Read `work/world/logs/latest.log` and any crash report: a watchdog kill at a 60 s tick is load, not the bot. Never fix the bot for these.
- **The same signature again after a fix.** Replay the new run: it is often a different cause with the same reason (`:boxed` under a lake, then under the sea, then in a flooded tunnel were three fixes).

## Worked examples (2026-10-11, the iron kit)

| Batch, run | Reason | What the replay showed | Fix |
|---|---|---|---|
| live run 14 | timed out | tunnelling for gravel at y 18 over five gravel blocks in its own floor: a block under a standable cell never counted | a block in the floor counts when digging it leaves a step down |
| iron-a7 run 1 | `:boxed` | climbing at y 44 under a lake; 32 level steps east under it | a climb's level steps head for the nearest dry ground |
| iron-a9 run 4 | `:needs-tool` | a tunnel at y 2 met ore its stone pickaxe cannot drop | a level step refuses a cell too hard for the held tools |
| iron-a9 run 3 | death | `fell from a high place` in the server log; the collect walked after a flint to a ravine's edge | a collect never steps over a drop deeper than `path/max-fall` |
| iron-a9 run 5 | `:no-path` | three walks that never moved: the feet in a cocoa pod the hand list of awkward blocks lacked | awkward blocks read from the game's collision shapes (`shapes.edn`) |
| solid-b1 run 9 | `:no-water` | on a hill at y 70; every pond at y 62 refused as more than 5 under the eye | water under the open sky counts as surface water |
| solid-b1 run 1 | `:boxed` | under the sea 146 blocks from home; its own tunnel home ran out of loaded chunks, the route came back `:none` | a route that runs out of loaded terrain toward an unloaded target is a way |
| solid-b1 run 13 | `:no-water` | a pit at the top of its stairs, no room to jump out, no route anywhere | tunnel out at the feet's height when no hunt has a route |
| solid-b1 run 6 | `:no-spot` | pickaxe broken in a one-wide stairwell: no spot, no niche without a pickaxe | a carried table may stand in the passage, after a niche |
| live run 19 | harness error | the judge polled `bot.log` and read a 44 KB RESULT half written | the gym reads the bot's answer from its stdout pipe |

Each row is one commit on the branch, with its test named after the behaviour; `git log` and `problems.edn :iron-kit` hold the detail.
