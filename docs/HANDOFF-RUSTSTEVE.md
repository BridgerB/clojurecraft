# Handoff: clojurecraft closes, ruststeve v2 begins

This is the last change to clojurecraft. Work stops here on 2026-11-12, and everything moves to a new Rust bot, ruststeve v2: a from-scratch, backend-only Rust project that ports mostly from clojurecraft (not from the old ruststeve), with steve (the SvelteKit app) as its only front end. This document is the handoff:

- what clojurecraft built, and how far it got;
- what was learned on stage 18, where it stalled for three weeks;
- what is shut down, and what is left lying around;
- how Rich Hickey's way of building programs carries into Rust;
- what ruststeve v2 should be, and its first 20 issues.

Read it before writing the first line of ruststeve v2. The single most useful thing in it is section 4 (what stalled us): almost every mistake listed there will be just as easy to make in Rust.

## 1. What clojurecraft was, in numbers

clojurecraft was a Minecraft Java Edition bot in Clojure whose job was to go from a fresh spawn to a dead Ender Dragon with no human input and no cheats. It was the third bot in the `mc` monorepo, after steve (TypeScript) and ruststeve (Rust), and it was built deliberately the other way round from them: no mutable bot object, no framework, one world value, pure reducers.

- **Span:** 2026-10-09 (initial commit) to 2026-11-12, 32 days with commits.
- **Size:** 578 merged pull requests, 256 issues (13 open at the end), about 101 namespaces and 45,000 lines of Clojure under `src/`, plus about 30 generated EDN tables under `resources/clojurecraft/`.
- **Server:** vanilla 26.1.2, protocol 775, offline mode, Java 25, seed `typecraft`.
- **Compute:** all tests, sims and races ran on GitHub Actions (up to 20 runners). The Mac only edited code.

### How far it got

The run was cut into about 25 stages from spawn to dragon. The pass rule (LOOP.md) was that a stage passes when about 5% or more of the bots that start it complete it, in both the real race (real vanilla servers on runners) and the sim race (the bot against a pure Clojure model of the server).

| Stage | Result |
|---|---|
| 1–16: wood, tools, stone, furnace, iron, buckets, water, flint and steel, obsidian cast, portal, Nether entry, fortress | passed (proven repeatedly from spawn by Oct 31) |
| 17: blaze rods (7) | passed: real 184 of 2,518 bots (7.3%), sim 90 of 688 (13%) on 0ace52e |
| 18: ender pearls (12) | never passed. First real pass ever on 2026-11-09 (Clj_r28). At shutdown, the newest code: real rods start ~0–1%, real forest start ~3–4%, sim rods start ~5%, sim forest start ~30% |
| 19–25: eyes of ender, stronghold, end portal, the End, dragon | code exists for most (eyes, stronghold triangulation, end portal fill, a dragon fight), never reached in a race |

Stage 18 had two checkpoint starts: the "rods" start (the race kit plus 7 blaze rods, at the fortress pad) and the "forest" start (the same kit, placed at a warped forest). The forest start passes in the sim; neither passes in the real race. The real race is the honest metric, and it is the gap.

## 2. How we got there: the progress steps

### The first ten issues (Oct 9–12): the bones

Issues 1 to 10 were written on day one as a plan, and each was closed with one or a few PRs. They are the reason the first 16 stages came up in about a week. Each was a small, coherent capability with a clear done-condition:

1. **#1 Collect one natural log, the data-oriented way.** The whole architecture in one milestone: one world value in an atom, `game` and `plan` as pure reducers over event maps, effects as data, packets/intents/goals as open multimethods, timed memory, record and replay, a pure server model (`sim.clj`) running the bot end to end in a test.
2. **#2 Crafting as data.** Planks, sticks, a table in the 2x2 grid, then the recipe graph from generated `recipes.edn`. The container-click protocol with state ids, verified by reading the window back, never by predicting it.
3. **#3 The gym on GitHub runners.** One job per goal run, prerequisites granted over RCON, pregenerated landing sets, judged by the bot's RESULT line plus a server-side truth command, recordings as artifacts.
4. **#4 A real pathfinder as a pure function.** A* over the chunk value with a node budget; moves and goals as data; waypoints consumed by the walk intent.
5. **#5 Water and lava survival.** Vanilla fluid physics as pure code, a priority-zero escape goal, liquid-aware memory and path costs, fall damage, respawn.
6. **#6 Furnace, coal, iron, buckets, flint and steel.** The iron kit, with smelting as a pure plan over the furnace window and fuel as a need in the goal table.
7. **#7 Place the table, open it, craft a wooden pickaxe; recipe-driven goals.** Placement, the 3x3 window, and goals stated as wants with needs derived from recipes.
8. **#8 Portal cast.** Obsidian cast from lava and water (never mined), the frame as a build plan in data with a done-predicate per op, light it, step in.
9. **#9 Dig with tools and real hardness.** `hardness.edn` and tool tags from datagen, a pure dig-time formula, tool selection, a safe stairs-down intent, the stone-pickaxe goal.
10. **#10 Roadmap: blaze rods, ender pearls, eyes, stronghold, the End, the dragon.** Still open at shutdown.

Issues 1–9 worked because each was one capability with one test that said done. Issue 10 did not, because it was nine stages in one issue with no done-condition of its own. Everything after the Nether grew out of it and out of issues filed against it, and that is where the plan stopped being a plan (section 4.1).

### Infrastructure (Oct 12 to Nov 1)

After the bones, most of the effort went into measuring the bot at scale:

- **The race** (`race.yml`): real vanilla servers on runners, many bots per server, refilled as bots end, every bot recorded.
- **The sim race** (`simrace.yml`): the same bot against the pure server model, in worlds captured from real servers (`capture.clj`, `simworld.yml`), thousands of runs.
- **The fleet** (`fleet.yml`): keeps all 20 runners busy with race and sim lanes, gives runners back when another job waits.
- **Churn and issues** (`bb churn`, `bb failures`): every 20 minutes a lane reports its ended runs, files each failure signature as a GitHub issue (one per signature), and publishes the batch to the results page.
- **Real snapshots** (`real.clj`): a real failure, folded from its recording at its own commit, becomes a sim snapshot that a fix is judged against (`simrace --judge`: must recur on base, must not on the fix).
- **Observability** (Nov 6): every ended run leaves a case file (killer, first hit, what it was doing and why), an event ledger, one senses line a second, a moment snapshot and its full recording. Packets the bot used to drop (damage types, sounds, hurt animation, equipment, attributes) were decoded so deaths could be named.
- **Checkpoint starts** (Nov 5–7): the stuck stage gets its own start (kit plus place) so every lane plays it; before that, stage 18 got only 30–150 attempts per commit (section 4.3).
- **The results page** on gh-pages: every batch, by commit and lane kind, with stage-reached counts.

### Stage 17 (late October)

Blaze rods passed once the bot stopped fighting in the open: a sealed pocket by the spawner, attacks only on blazes in reach, and fixes to the fortress tunnel-out. That was the last stage to pass.

### Stage 18 (Oct 16 to Nov 12)

Issue #263, "Ender pearls by hunting endermen", opened on Oct 16 and was never closed. Section 4 is the story.

## 3. What worked: keep all of it

These are the parts of clojurecraft that ruststeve v2 should copy in spirit, almost one to one.

1. **One world value, one writer.** The world is a flat map of namespaced attributes (`:player/pos`, `:world/entities`, `:plan/intent`), written only by the loop. Everything else reads values. Observers (telemetry, the viewer, the senses line, the recorder) never slowed or corrupted the loop.
2. **Pure reducers, effects as data.** `(step world event) -> world'` with outgoing packets and log lines as `:bot/effects` the loop drains. The protocol, the planner and every executor are testable with canned events and no socket.
3. **Time and randomness are inputs.** The tick event carries `:event/now` and `:event/rand`. Nothing in a reducer reads a clock. This made record-and-replay exact, and exactness is what made every later debugging tool possible.
4. **Every run is a file.** `--record` writes every event and effect; `bb replay` folds the reducer over it with no server and verifies every effect. Real failures were diagnosed by folding recordings to an exact millisecond.
5. **The pure server model.** `sim.clj` and its siblings (fluid, hunger, mobs, the End) let the whole bot run in-process against a model, thousands of runs a day, with 2,000 generated worlds in the unit suite.
6. **Specs and properties.** Every attribute and packet specced; reducers instrumented in tests; generative properties (every keep-alive answered, the phase only moves along the transition table, step is total over decodable packets).
7. **Goals and thresholds as data.** `goals.edn` (the goal table), `problems.edn` (a written problem statement per stage), `pearls.edn` and `threats.edn` (thresholds). The planner re-derives "done" from the world every tick, so regressions re-plan by themselves.
8. **Memory as facts with time.** A DataScript store of sightings, intentions and server answers, never retracted. "The nearest log I have seen and not confirmed gone" and "what was I doing at time t" are queries.
9. **Datagen from the game itself.** Packet ids, blocks, items, recipes, hardness, damage types and sounds come from the vanilla server's reports and jar, never hand-typed.
10. **No compute on the dev machine.** Everything that runs code goes to a runner (`probe.yml` for one-off questions). It kept the laptop free and made every result reproducible from a run id.
11. **Checkpoint starts and the real race as the metric** (section 4.3).
12. **Case files for every death.** Once each ending carried its killer, its first hit and the intent with its "why", causes could be counted instead of guessed.

## 4. What stalled us on stage 18, and the lessons

Stage 18 took about four weeks and never passed. It wasn't for lack of fixes (over 100 stage-18 PRs merged). The fixes kept being local answers to local symptoms while four structural problems sat underneath. In rough order of cost:

### 4.1 Too much in too few issues, and too many issues

Issue #10 held nine stages. #263 held the whole pearl hunt. Meanwhile the churn filed one issue per failure signature, automatically, so the tracker filled with symptom issues (256 by the end) that no one could plan against. Agents worked the issue list from the bottom up and fixed symptoms in parallel, often in the same code. The pearl hunt grew into a rule table of about 60 rows (`pearls-rules.edn`) whose interactions nobody could hold in their head. A fix to one row routinely moved deaths to another (heal-in-cover cut fireball hits while healing from 22 to 4 and moved the deaths to magma cubes).

**Lesson:** issues are a plan, not a log. Keep a small set of planned issues, each one capability with one measurable done-condition. Put failure signatures on the results page and in case files, never in the issue tracker.

### 4.2 The sim did not match the real server, and we found out late

The sim forest start passed at about 30% while the real one sat at 3%. Differences found only in the last days:

- **Simulation distance.** Race servers run simulation distance 3, so the vanilla server freezes every mob beyond about 3 chunks (48 blocks): no movement, no attacks, no teleports. The sim ran everything out to 6. Real bots watched frozen endermen for hours; frozen ghasts at 62 blocks held one bot in a "heal under fire" loop for two hours (#858, #864, #869).
- **Magma cubes hit through ceilings in the sim.** The model lifted a cube's hop through rock; vanilla stops it at a ceiling and needs line of sight to strike (#867).
- **No skeletons or other archers in the sim's Nether,** so a real bot frozen by an unseen skeleton counting as a "swarm" could not be reproduced (#865).
- **Wall-clock timeouts.** A real server under 14 bots ticks at 150–300 ms instead of 50, so a 60-minute wall-clock cap gave real bots a fifth of the game time sim bots got, until the cap was counted in server ticks (#812).
- **Real servers starve.** The bot JVM on the same 4-vCPU runner took about 3.3 cores, and the server thread got 30–53%.

**Lesson (ML principle 1 in LOOP.md, honoured too late):** sim and real must agree before a sim number counts. Build a parity table early (the same moments, real against sim) and treat every gap as a sim bug until shown otherwise. Make the model's knobs (simulation distance, tick rate) match the real race's config, read from one shared file.

### 4.3 Throughput: measure the stuck stage directly

For weeks every race bot started at the fortress with no rods, had to win stage 17 first, and only 5–13% got through, so stage 18 got 30–150 attempts per commit. Fixes were judged on a trickle. Checkpoint starts (a kit and a place for the stuck stage, on every lane) fixed it in a day. Even after that, sim comparisons were often 9 runs a side, where one seed diverging moves the result, and two of our "fixes" were identical to main run for run, meaning they never fired.

**Lesson:** from the first day, every stage has a checkpoint start, and every lane can play any stage. Judge a change on at least tens of paired runs, and check that the new code path actually ran (count its firings) before reading the result.

### 4.4 The bot burned its own resources in loops

Late analysis of real recordings (folded at the commit that made them) found the real causes of many timeouts and "no pickaxe" endings. None of these were visible from counts.

- **Break-out tunnels dug squares.** The cage-break picked its direction from a 30-second clock, a tunnel took about 25 s, so each tunnel turned 90° and four closed a square. 877 of one bot's 1,244 digs, about 6 stone pickaxes (fix/263-cage-line, unmerged).
- **Heading legs paced an old corridor.** On a 1-wide causeway over the lava sea, every step toward the target was refused (no filler, no way up), so the stepper walked back and forth along cells it had already cut. Each leg lasted a minute and gained nothing (fix/263-heading-no-pace, unmerged).
- **Building blocks were spent sealing and unsealing.** The heading and the fortress exit dragged the bot back and forth across the fortress surface height, placing a block behind it each pass and digging it up the next. One bot spent about 600 blocks by minute 40, then stood on a causeway with nothing to bridge with for 37 minutes (fix/263-filler-keep, unmerged).
- **A craft and the hunt preempted each other 410 times.** Items laid in an open crafting table's grid didn't count as held, so "spare pickaxes" read as done mid-craft and the hunt took over (fix/263-craft-flip, unmerged).
- **Exit targets flipped** between two cells or two heights (#786, and the r67 case, still open).

**Lesson:** loops that make no progress are the most expensive bug class in an autonomous bot. Build a progress invariant into every long intent (distance to target must shrink, or digs must open new cells) and fail fast when it doesn't. Measure resources spent per unit of progress (digs per block of distance, blocks placed versus kept), not just endings.

### 4.5 Replays must run at the recording's own commit

A recording folded with newer code drifts. The bot's own position follows its own choices, so once the new code chooses differently, the fold leaves the world the server saw. Several diagnoses were wrong for a day because of this. The fix is to fold at the recording's commit and call any new function as a pure function on those worlds.

### 4.6 The planner was the CPU

A JFR profile of a real lane showed about 93% of the race JVM's CPU in the planner. One `near-fortress?` check scanned 5,675 fortress bricks several times a tick (33% of all samples), fixed by chunk buckets (#863). Five of 14 bots each pegged a core, starving the server on the same machine.

**Lesson:** give each bot a CPU budget per tick and measure it in CI. Index spatial data (chunk-column buckets) from the start. A planner that rescans the world every tick does not scale to 14 bots on 4 cores.

### 4.7 Smaller lessons

- **Parallel tests that redefine globals race.** A `with-redefs` in one test leaked into a sim test in another thread and made replays flaky for days (#861, plus a style guard).
- **Agents report confidently and are sometimes wrong.** Verify a claimed cause against one bot's log lines before acting on it. Some reports described runs as finished that `gh` still showed running.
- **Model-only fixes grounded in the vanilla jar are cheap and safe.** Read the server's classes (`javap`) before guessing mob behaviour.

### 4.8 Vanilla facts learned the hard way (26.1.2)

These came from the server jar or from real logs, and every one cost at least a day.

- An enderman with a target keeps its anger forever: the timer restarts every tick, and it doesn't need to see the target.
- An enderman more than 16 blocks from its target teleports toward it after 30 ticks, and away when stared at within 4. A teleport lands only on a floor with three free cells, so a bot in a 2-high rock tunnel cannot be reached.
- A melee hit knocks a mob back about 3 blocks over 11 ticks. The bot ignores its own knockback packets, so the real bot is never knocked back.
- Magma cubes come in sizes 1, 2 and 4, and hop. The hop stops at a ceiling, and the strike needs line of sight.
- A fireball's damage event carries no entity (the projectile is gone), so "was I shot" must read the damage type, not the attacker.
- Through iron armour, one enderman hit takes about 4 health, so any threshold below 16 never fires after a single hit (#868).
- Race servers at simulation distance 3 freeze mobs beyond about 3 chunks.
- 26.x gamerules are snake_case (`keep_inventory`).
- Protocol 775 split `attack` into its own packet.

## 5. What is shut down, and what is left

### GitHub Actions (BridgerB/clojurecraft)

Disabled at shutdown (`gh workflow disable`, reversible with `gh workflow enable`):

| Workflow | What it did |
|---|---|
| `fleet.yml` | kept every runner busy with race and sim lanes |
| `race.yml` | real-server race lanes (14 bots, one server per lane) |
| `simrace.yml` | sim race lanes against the model |
| `probe.yml` | one command on a runner for a branch |
| `test.yml` | unit suite, brain checker and a forest shard on every push |
| `unit.yml` | the unit check on pull requests |
| `issue.yml` | judged a fix PR's snapshots on base and branch |
| `gym.yml` | N trials of one goal on pregenerated landings |
| `world.yml` | built a landing set's world once and cached it |
| `simworld.yml` | captured sim worlds and the Nether from real servers |
| `pages.yml` | built the results site |

Still enabled but idle: `pages-build-deployment` (GitHub's own, serves the existing results site) and the Copilot agent workflow. Older workflows named in CLAUDE.md (`timer.yml`, `plan.yml`, `full.yml`) are no longer in `.github/workflows` on main.

Every running and queued run was cancelled at shutdown: 8 race lanes, 7 sim lanes, the fleet and 3 probes. One ancient queued pages run (42069483013) could not be cancelled and can be ignored.

Because `test.yml` and `unit.yml` are disabled, this PR has no checks.

### Data that remains

- **gh-pages:** the results site and `data/batches/<day>.ndjson`, every batch since late October. It's the best record of what each commit did.
- **Releases:** `botlogs-<day>-<hour>` (case files, ledgers, recordings of failed runs), `jfr-<run id>` (lane 1 profiles), win releases. These hold real recordings of stage-18 failures that are worth mining for ruststeve's parity tests.
- **Artifacts:** probe and lane artifacts expire on GitHub's retention.
- **Repository variables:** `COPILOT_CAP`, `COPILOT_FIX_MODEL` (unused now).
- **The OCI game box** (144.24.32.76) was never used by clojurecraft and is untouched.

### Unmerged branches at shutdown

All pushed, none merged, with each one's state at shutdown. Each is a lead for ruststeve, not a promise.

| Branch | State |
|---|---|
| `fix/263-cage-line` | break-out tunnels hold one direction instead of a 30 s clock; unit green; cage-break digs fell from 877 to 76–111 per bot on R1/17 in the sim; baseline comparison unread |
| `fix/263-heading-no-pace` | a heading leg fails after two steps that neither dig nor get closer; awaiting a sim gain that was never measured |
| `fix/263-filler-keep` | keep 32 building blocks unspent and re-stock from the tunnel wall before a heading leg; judge never finished |
| `fix/263-heading-floor` | heading legs never sit under the fortress exit's surface; not proven on a snapshot |
| `fix/263-heading-blocked` | the heading remembers blocked directions and climbs; did not clear r51's snapshot (the real cause was 4.4's corridor pacing) |
| `fix/263-craft-flip` | items in an open table's grid count as held; confirmed on r04's recording; sim pair unread |
| `fix/263-frozen-angry` | angry endermen count even when frozen; built as a guard against a possible regression from #864 (real forest-start enderman deaths rose on the newest code); unconfirmed |
| `fix/263-retrace-forward` | fortress retrace follows the inbound trail; level with main in the sim; the exact r67 test said it does not fix r67 |
| `fix/263-heal-in-cover` | heal under fire only behind cover; cut hits while healing 22→4 but deaths rose by one before #867; re-measure unread |
| `fix/263-blaze-range`, `fix/263-shot-tunnel` | hold cover after being shot; never fired in the sim |
| `fix/263-boxed-expiry`, `fix/263-burrow-churn`, `fix/263-forest-level`, others | parked |
| `probe/*` | probe-only branches, safe to delete |

## 6. Rich Hickey and functional programming, carried into Rust

`docs/RICH-HICKEY.md` is the design brief clojurecraft was built on. Its commitments are about information and time, not about Clojure, and almost all of them translate into Rust. Some get easier, a few get harder.

### The commitments, in one line each

- **Simple means not braided.** Each piece is about one thing. Never interleave identity with value, computation with effect, logic with conveyance.
- **Values, not places.** State is a succession of immutable values. A process is a function from the past to the future. Observers read values and need no coordination.
- **Programs are about information.** Information is sparse and open: what you don't know is absent, not nil-filled. Name things by meaning, not by position.
- **Design is taking things apart,** so they can be put back together.
- **Systems are processes speaking data over bounded queues.** No unbounded buffers, no logic in handlers.
- **Grow, never break.** Accrete, relax or fix. Never change the meaning of a name; add a new name beside it.
- **The hammock.** Write the problem statement before the code.

### How each maps to Rust

| Hickey / clojurecraft | Rust in ruststeve v2 |
|---|---|
| One world value in an atom, written only by the loop | One `World` struct owned by the loop thread. Each tick: `fn step(world: &World, event: &Event) -> Step { world: World, effects: Vec<Effect> }`, or `&mut World` inside the loop with the same purity at the function boundary (no I/O, no clock, no globals). Publish snapshots to observers as `Arc<WorldView>`. |
| Persistent data structures (cheap copies) | Keep the big parts in `Arc` (chunk sections are immutable once decoded; a block change replaces one section's `Arc`). Use `im` or `rpds` only where a whole history matters. Don't clone the world per tick by hand. |
| Flat, namespaced, open maps | Rust structs are closed. Get openness from small structs per area (`Player`, `Inventory`, `Entities`, `Plan`, `Memory`) with `Option` fields for what may be unknown, and from data tables (goals, thresholds) loaded from files. Never one giant struct with 200 fields; never positional tuples for domain data. |
| Effects as data | `enum Effect { Send(Packet), Log(LogLine), ... }` returned from reducers. Only the loop performs them. |
| Time and randomness as inputs | `Event::Tick { now_ms, rand_seed }`. No `Instant::now()` or `rand::thread_rng()` anywhere outside the loop. Enforce with a lint (`clippy::disallowed_methods`). |
| Open dispatch (multimethods) | Rust enums are closed, and that's usually fine: exhaustive `match` is a feature. Use `enum Intent` and `match`. Where openness truly matters (goal predicates by name), use a registry: `HashMap<&'static str, fn(&World) -> bool>` keyed from the data table. |
| Goals, thresholds, problems as EDN data | RON or TOML files under `data/`, loaded at start, `serde`-derived. Thresholds never in code. |
| Specs and generative tests | Types plus `proptest` for properties (every keep-alive answered, step total over decodable packets, the phase follows its table) and `serde` round-trips. |
| Every run is a file; replay verifies effects | Record `(Event, Vec<Effect>)` with `serde` + `bincode` or MessagePack, compressed with zstd. `cargo run --bin replay <file>` folds and diffs effects. Pin the commit in the file's header and refuse to replay with different code unless asked (lesson 4.5). |
| DataScript memory (facts with time) | Append-only fact log per kind (`Vec<Sighting>`, `Vec<Intention>`) with spatial indexes (chunk-column buckets, lesson 4.6). A Datalog engine isn't needed: write the five queries the bot actually asks as indexed functions. |
| core.async bounded channels | `crossbeam-channel` or `std::sync::mpsc::sync_channel` with bounds. One reader thread decodes packets and chunks; one loop thread steps; observers get snapshots over a bounded channel and drop rather than block. Keep the core synchronous; `tokio` only at the edges (HTTP/WebSocket to steve, Postgres) if at all. |
| I/O fenced below `;;;; I/O ;;;;` | Crate boundaries: core crates (`protocol`, `world`, `plan`, `sim`) have no I/O dependencies at all. Only `net`, `rcon`, `record` and the binaries touch sockets and files. |
| Grow, never break | Packet structs only gain fields. A goal that changes gets a new id. A recording's format is versioned. |
| The hammock (`problems.edn`) | A `docs/problems/<stage>.md` per stage, written before its code: needs, risks, done in world terms, the last recorded failure. |

### What Rust makes easier

- **Speed and memory.** One bot core per thread, 14+ bots per runner without starving the server, if the planner is indexed (lesson 4.6).
- **Exhaustive matches.** A new packet or intent the planner forgets is a compile error, not a silent drop.
- **No `with-redefs`.** No global mutable state to leak between parallel tests (lesson 4.7), as long as the code stays free of `static mut` and global singletons.

### What Rust makes harder

- **Openness.** Adding a field to a struct touches every literal of it. Use `#[derive(Default)]` and builder-style construction in tests, and keep structs small.
- **Ceremony.** Ownership pushes toward `&mut` everywhere. Keep the discipline anyway: functions take what they read and return what they change. Write `fn decide(view: &PlanView) -> Intent`, not `fn decide(&mut self)`.
- **No REPL.** Make replay plus a small `why` binary (fold a recording to time t and print every planner branch's answer) the REPL substitute from week one.

## 7. What ruststeve v2 should be

### Scope

- **Rust only, backend only.** No HTML, no viewer and no dashboard in ruststeve. steve (the SvelteKit app) is the only front end and reads ruststeve over a narrow interface.
- **A fresh start, ported mostly from clojurecraft.** Not from the old ruststeve. Port the information model, the protocol tables, the sim model's rules and the vanilla facts in section 4.8. Do not port the 60-row pearl rule table or the stage-18 patch pile. Re-derive the hunt from a written problem statement with progress invariants.
- **Same server target to start:** vanilla 26.1.2, protocol 775, so clojurecraft's datagen, captured worlds and recordings stay usable as fixtures.

### The interface to steve

Recommended order:

1. **Postgres as the shared surface.** steve already reads telemetry from Postgres. ruststeve writes runs, batches, case files and per-second senses rows; steve renders them. That's the least coupling, and it survives either side restarting.
2. **A read-only WebSocket or SSE stream** of world snapshots, for a live view of one bot (JSON or MessagePack). Snapshots only, never commands.
3. **No ruststeve code in steve, and no steve code in ruststeve.** A shared schema file (SQL plus a JSON schema for snapshots) is the whole contract.

### Crate layout (a starting point)

```
ruststeve/
  crates/
    data/        generated tables (packets, blocks, items, recipes, hardness, damage types) + loaders
    protocol/    packet specs as data, decode/encode, state transitions; no I/O
    world/       World, Event, Effect, the protocol reducer, chunk sections, entities, inventory
    physics/     vanilla movement as a pure function, collision, fluids
    memory/      append-only facts with time, spatial indexes
    plan/        goal table, needs planner, intents, executors; progress invariants
    sim/         the server model: blocks, fluids, mobs, combat, simulation distance
    record/      record and replay, versioned format, commit-pinned
    net/         socket, framing, compression, reader/writer threads (I/O)
    rcon/        RCON client (I/O)
    store/       Postgres writer for steve (I/O)
  bins/
    bot          one bot against a server
    race         many bots, one server, claims, case files
    simrace      many bots against the model, parallel
    replay       fold a recording, verify effects
    why          fold to time t and explain the planner's choice
    datagen      read the vanilla jar and reports into data/
  data/          goals.ron, thresholds.ron, starts.ron, problems/
  .github/workflows/  test, race, simrace, probe, fleet (rebuilt lean)
```

### Rules to carry over

- **No compute on the Mac.** Edit, `cargo fmt`, `cargo clippy` and git locally. Tests, sims, races and probes run on GitHub runners.
- **The real race is the metric.** Report every rate as k/n.
- **A sim number counts only after a parity table** against real at the same moments (lesson 4.2).
- **Every stage has a checkpoint start from day one** (lesson 4.3).
- **Caps in game ticks, not wall time.** Model knobs match the race config from one shared file.
- **Every ending has a case file:** the killer, the first hit, the intent and its why.
- **Replays fold at the recording's commit** (lesson 4.5).
- **Never a death as a plan.** Never fight under danger health.
- **No AI attribution anywhere.** Never push to main, force-push or skip hooks.

## 8. The first 20 issues for ruststeve v2

clojurecraft's first nine issues worked because each was one capability with one done-condition. Its tenth failed because it was a roadmap, and the auto-filed symptom issues buried the plan (lesson 4.1). So ruststeve v2 opens with 20 planned issues, each small enough for one or a few PRs, each with a done-condition a runner can check. No roadmap issue, and no auto-filed issues: failure signatures go to the results page, not the tracker.

The order matters. Issues 1–8 build the machine (protocol, world, record, sim, CI, metric) before any gameplay depends on it. Issues 9–20 climb the stages, each with its checkpoint start built in.

1. **Datagen and the protocol tables.** Read the 26.1.2 server reports and jar into `data/` (packets, blocks, items, tags, recipes, hardness, damage types, sounds). *Done:* every table generated by `cargo run --bin datagen`, committed, with a test that decodes a captured handshake-to-play packet stream with zero unknown ids.
2. **Connect, log in, stay in-world.** Handshake, login, configuration, play, keep-alive, compression, the teleport ack. The core reducer is pure, the socket is in `net`. *Done:* a bot holds a session for 10 minutes on a runner's vanilla server, and a proptest shows every keep-alive is answered exactly once.
3. **The world value and chunk sections.** Paletted sections kept as received, `block_at`, entities, inventory, health. Open-ended fields as `Option`. *Done:* a captured clojurecraft recording's chunk stream decodes to the same block states clojurecraft saw at 100 sampled positions.
4. **Record and replay, commit-pinned.** Every event and effect to a versioned file; `replay` verifies every effect; `why` folds to time t. *Done:* a 10-minute run replays with zero effect mismatches, and replay refuses a file from another commit without `--force`.
5. **Vanilla physics.** Walking, jumping, collision, fluids and fall damage as a pure function, matched against the server. *Done:* position error under 0.01 blocks over a recorded 5-minute walk, compared against the server's position corrections.
6. **The server model (sim) and its parity table.** Blocks, digging, item drops, fluids, mobs, simulation distance and tick rate read from the race config. *Done:* a parity report runs the same 50 recorded moments in sim and real and shows the outcome classes matching within a stated tolerance.
7. **CI: test, sim race, real race, probe, and the results data.** Lean workflows, runner-only compute, every batch written to one ndjson per day plus Postgres. *Done:* a push runs the suite; a dispatch runs N real and N sim bots and publishes k/n per stage and commit.
8. **Case files and the steve contract.** Killer, first hit, intent and why for every ending; per-second senses rows; the Postgres schema steve reads. *Done:* every ended run in a batch has a case row (coverage 1.0), and steve renders one run's timeline from Postgres.
9. **Walk with a pathfinder.** A* over the chunk value with a node budget, moves as data, a progress invariant (distance must shrink or the walk fails fast). *Done:* 95% of 200 sim landings reach a log 30+ blocks away; 0 walks run over 60 s without progress.
10. **Wood and the 2x2 grid.** Find, dig and collect logs; planks, sticks and a table, verified by reading the window back. *Done:* checkpoint start "spawn", 90% of real bots hold a crafting table in 5 game minutes.
11. **Stone tools.** Place and open the table; wooden pickaxe; stairs down safely; cobblestone; stone pickaxe; dig time from hardness. *Done:* checkpoint start "table", 90% real.
12. **Survival basics.** Escape water and lava as priority zero, eat, defend against one melee mob, never fight under danger health. *Done:* deaths under 5% over 1 game hour of stone-tool play, real.
13. **Iron kit.** Furnace, fuel, iron ore, smelting, a bucket, flint and steel, with fuel as a need. *Done:* checkpoint start "stone", 50% real reach the kit in 30 game minutes.
14. **Water, lava and the obsidian cast.** Fill a bucket, find lava, cast the frame as a build plan with a done-predicate per op, light it. *Done:* checkpoint start "kit", 50% real light a portal.
15. **Nether entry and the fortress.** Enter, survive spawn, find and reach a fortress with tunnels that never sit beside lava, and keep a building-block reserve. *Done:* checkpoint start "nether", 30% real reach a fortress; the median bot keeps 32+ building blocks.
16. **Blaze rods.** The pocket by the spawner, fight blazes in reach, cover from fireballs read by damage type. *Done:* checkpoint start "fortress", 10% real reach 7 rods. This is clojurecraft's stage 17, which passed at 7%.
17. **Getting to endermen.** From the fortress, reach a warped forest (or reachable endermen) with a long heading that holds direction, never paces, and restocks filler. Endermen beyond simulation distance are frozen. *Done:* checkpoint start "rods", 60% of real bots are within 32 blocks of a ticking enderman within 15 game minutes. This is the part clojurecraft never solved.
18. **Ender pearls.** Provoke from cover, fight one enderman at a time, honour the vanilla anger and teleport rules (4.8), and treat an angry frozen enderman as present. *Done:* checkpoint start "forest", 5% real and 5% sim reach 12 pearls.
19. **Eyes of ender and the stronghold.** Blaze powder, eyes, two throws triangulated as a pure function, dig to the portal room. *Done:* checkpoint start "pearls", 20% real stand in the portal room.
20. **The End and the dragon.** Fill the frames, enter, destroy crystals, fight the dragon, detect the win. *Done:* checkpoint start "portal room", 1 real win. That's the goal.

How to run the list: one issue at a time. Each issue's checkpoint start exists before its gameplay code. An issue closes only when its done-condition holds on the real race at the stated k/n. A failure inside an issue gets a problem statement in `docs/problems/`, not a new issue. If an issue turns out too big, split it into numbered sub-issues under it (17a, 17b), so the plan stays one list.

## 9. Where to read more in this repo

- `docs/RICH-HICKEY.md`: the design brief, with sources. Read it in full.
- `LOOP.md`: the operating loop for the stuck stage, the checkpoint rule, the ML principles and the review bar.
- `brain/`: the verified knowledge base (`brain/_index.md` first). `brain/game/mechanics/mc-enderman-rules.md` and `brain/bot/tooling/` (race, server model, sim mobs, observability) hold most of what stage 18 taught.
- `resources/clojurecraft/problems.edn`: a written problem statement per stage, with the last recorded failure.
- `resources/clojurecraft/goals.edn`, `pearls.edn`, `threats.edn`: the goal table and thresholds.
- `src/clojurecraft/sim/*`, `mob.clj`, `fluid.clj`, `hunger.clj`: the server model to port into `crates/sim`.
- Issues #1–#10 for the shape of a good first plan, and #263 for what happens without one.
