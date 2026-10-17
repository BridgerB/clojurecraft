# The bot and how its failures become issues (section 2)

Part of the bot-failure manual; [AGENTS.md](../../AGENTS.md) is its entry and index. Read only the section a question needs.

## 2. The bot and how its failures become issues

### 2.1 The bot in one page

- `game/step` folds events (`:tick`, `:packet`, `:go`, ...) into the world value: one flat map of namespaced attributes. The planner `plan/step` has the same signature and is composed after it.
- The planner walks a table of goals (`resources/clojurecraft/goals.edn`); each goal's act (`plan/act`, `plan/next-intent`) reads the world and returns the next intent or `{:plan/wait reason}`. A wait fails the plan after `plan/wait-ticks` (400 planner ticks, 20 s at full speed; `:plan/waiting-ticks` counts them) with its reason. A failed intent counts an attempt; `max-attempts` (3) in a row fail the plan with the intent's reason; a finished intent resets the count.
- Intents are maps (`{:intent/kind :dig ...}`) run a tick at a time by `intent/run` methods (`intent.clj`, `stairs.clj`, `scoop.clj`, `place.clj`, `build.clj`, `craft.clj`, `smelt.clj`, ...), and may run a child (`intent/run-child`).
- A failed run's RESULT becomes a signature `[target reason]` (`failure/signature`), e.g. `[:ingots :boxed]`. One signature is one issue.
- `sim.clj` is the server model: the packets a 26.1.2 server sends, over captured worlds. The sim race plays the real bot against it.
- Every key in the world or an intent has a row in `model.clj` and a spec in `spec.clj`; the docs and spec tests fail otherwise.
- `resources/clojurecraft/problems.edn` holds one row per stage: statement, needs, risks, done, and `:problem/last-failure`, a running record every fix appends to.

### 2.2 The rounds

- `race.yml`, the real race: up to eight lanes (a lane is one run, one runner), each with 26.1.2 servers at Normal difficulty on a pregenerated landing set (R1 to R4), 15 bot slots a server (#252), a slot refilled the moment its bot ends. Every run plays the chain to `:fortress` (`:race/until`); a real bot has no time limit of its own but its lane's job (about 5.5 h).
- `simrace.yml`, the sim race: up to eight lanes playing the bot against the model in captured worlds (`cache/sim-worlds/R1..R4/<n>.edn.gz`), seed after seed, at Normal (mobs and hunger), to `:fortress`, 330 simulated minutes a run (#252). The model's Nether is a single column, so no sim run finds a fortress: a sim failure there is the model's limit.
- The fleet (`fleet.yml`, `ci/fleet.edn`) keeps the runners full of lanes and gives one back the moment any other job has waited 90 s for a runner (GitHub has no preemption of its own), never fewer than two lanes a kind; a merge that touches code cancels and replaces every lane, docs-only merges (`brain/`, `docs/`, `*.md`) do not. So a check, a probe or a gym waits about two minutes at most, never hours.
- **An issue is open only while it has a reproducible snapshot** (#299). Every 20 minutes per runner (`bb churn batch`, then `bb failures report`), the runs that ended since the last report are checked against the model on that batch's own commit (a sim run's snapshot judged; a real bot's recording converted into a real snapshot, 2.3) and a signature is filed, commented or reopened only when at least one of its runs has a snapshot that recurs there: one such run is enough, however few. Runs with no recurring snapshot (and infrastructure stops such as a closed socket) are counted in the report and on the page, never filed. Labels: `bot-failure` and `sim` (every filed issue has snapshots the check can judge), plus `real` when real runs hit it. The body holds a hidden marker with the signature and a tally (rounds, runs, first and last seen, when it was last reproduced). Each report adds one comment: run count, how many reproduced, last intents, landings, up to five recurring snapshots (nothing else: no replay lines), the run link. Every snapshot a comment names was uploaded before the comment was written, to the hour's evidence release (`evidence-<UTC day>-<hour>`, a prerelease, the asset `<run>-<file>`), so it outlives the lane: a merge cancels every lane, and an artifact is uploaded only at a job's end, if at all, and expires after 7 days. An open issue not reproduced for 7 days (its evidence a week old) is closed as not planned with "closed: no reproducible snapshot; reopens when a batch reproduces it", and a batch that reproduces it reopens it.
- A closed issue whose signature comes back in runs of code committed after it closed is reopened, its comment saying "(regressed: this issue was closed)". Runs of older code only comment.
- Results also go to https://bridgerb.github.io/clojurecraft/.

### 2.3 What a reproduction line says

Sim:

```
- snapshot `R4-35-2.edn.gz` as `<run>-R4-35-2.edn.gz` in release `evidence-2026-10-16-23` (moment 1:30 before the stop, :intent-failed, judged over 2:30); or `clojure -M:simrace --world cache/sim-worlds/R4/35.edn.gz --seed 2 --seeds 1` at <sha>, landing [2360 3640 103]
```

The snapshot is a moment the run kept from before the choice that failed (an intent that failed three times: when the first started; a wait: when it began; a `:timeout`: its last five minutes), with everything the next tick depends on and the window it is judged over: a value you can play on (8.4). The release holds the snapshot for good; comments from before 2026-10-17 say "in artifact `simrace-<sha>-<runner>`" instead, gone after 7 days. The runner's artifact `simrace-<sha>-<runner>` (when the job lived to upload it) holds that runner's `runs.edn` (one run map a line: `:run/signature`, `:pos`, `:held`, `:plan`, `:run/ms`, `:run/moment`), `simrace.log` and `snapshots/`. The command reruns the whole run from spawn (up to 330 simulated minutes, #252).

Real:

```
- snapshot `real-4-s2-Clj_r17.edn.gz` as `<run>-real-4-s2-Clj_r17.edn.gz` in release `evidence-2026-10-16-23` (moment 1:02 before the stop, :intent-failed, judged over 2:02); real race: bot Clj_r17 in s2
```

A real snapshot (issue #299, `real.clj`, brain `real-snapshots`) is the same value as a sim one: the batch folded the bot's recording on the race's runner, at the commit that played it, kept a moment from before the choice that failed exactly as a sim run does, and joined the server model to the bot's world there (its position, inventory, health, the blocks the server changed, the mobs and items it saw, the columns it held; a portal at the moment leads to the recording's own Nether). Its columns ride in it, so it needs no captured world: `--judge` and the check play it as they play a sim one. It is kept only when it recurs against the model with that code, judged in under half the judge's wall-clock budget (a slower verdict would flip on a slower runner). A real failure the model does not reproduce (a model gap: a mob the model never spawned, a time of day it guessed wrong, a planner too slow for the judge) is not filed; the round's `snapshots/convert-<n>.log` says how it got past (`GAP` lines, and `snapshots/gaps/` holds the snapshot for a look). A sim batch's judges are in `snapshots/judge-<n>.log` of its artifact.

The artifact `race-<sha>-<runner>` (uploaded at the job's end, when a cancelled job lives that long) holds `s1/` and `s2/` (one per server), each with `race.log`, `server.log`, `samples.edn.zst`, `milestones.edn.zst` and one recording per bot, `Clj_rNN.edn.zst`, and `snapshots/` with the real snapshots. A name is one bot of one server's job (`race/bot-name`: a serial per server), so `s1/Clj_r08` and `s2/Clj_r08` are two bots; the line names the directory. The runner number is in the comment's heading ("Race 38665691517 runner 1 batch 1 at 0d036cb").

---
