---
title: Sim race
description: Real worlds captured from a real server once (capture.clj) and the whole bot played in them against the server model with no Java server (simrace.clj), from empty hands at a landing to the race's target, as many runs as the cores allow; each run a pure function of world, seed and code.
type: reference
tags: [bot, tooling, sim, race]
aliases: [capture, capture.clj, simrace, simrace.clj, sim race, captured world, world file, SIMRUN, SIMRACE, :capture/columns, capture-nether, capture_nether.clj, sim-nether, captured Nether, :capture/nether, :snapshot/nether-file]
status: verified
lastUpdated: 2026-10-17
verifiedAgainst: HEAD
sourceRefs:
  - src/clojurecraft/simrace.clj#defn fortress-start
  - test/clojurecraft/simrace_test.clj#a-run-may-start-at-the-fortress-spawner-nearest-its-landing
  - src/clojurecraft/capture.clj#defn stops
  - src/clojurecraft/capture.clj#defn take-landing!
  - src/clojurecraft/capture.clj#defn chunk-tap
  - src/clojurecraft/simrace.clj#defn run
  - src/clojurecraft/failure.clj#defn signature
  - src/clojurecraft/failure.clj#defn mark
  - test/clojurecraft/simrace_test.clj#a-failure-the-server-caused-is-marked-not-the-bots
  - src/clojurecraft/simrace.clj#defn shard
  - "src/clojurecraft/simrace.clj#A failed run's moment as a value to keep"
  - src/clojurecraft/sim.clj#defn play-from
  - src/clojurecraft/sim.clj#defn kept
  - src/clojurecraft/simrace.clj#defn decision
  - src/clojurecraft/simrace.clj#defn chosen-moment
  - src/clojurecraft/simrace.clj#defn moment-of
  - src/clojurecraft/simrace.clj#defn judge!
  - bb/clojurecraft/issue_check.clj#defn fetch!
  - test/clojurecraft/issues_test.clj#an-issue-keeps-two-snapshots-at-most
  - src/clojurecraft/simrace.clj#defn judged
  - src/clojurecraft/simrace.clj#defn stage-until
  - test/clojurecraft/simrace_test.clj#a-moment-plays-on-exactly-as-the-run-did
  - test/clojurecraft/simrace_test.clj#the-moment-is-kept-from-before-the-decision
  - test/clojurecraft/simrace_test.clj#a-failed-runs-moment-round-trips-and-is-judged-in-its-window
  - test/clojurecraft/simrace_test.clj#a-stall-recurs-until-its-stage-is-reached
  - src/clojurecraft/simrace.clj#defn jobs
  - bb/clojurecraft/failures.clj#defn report-runs!
  - bb/clojurecraft/churn.clj#defn counted
  - bb/clojurecraft/failures.clj#defn with-tally
  - bb/clojurecraft/win.clj#defn winners
  - .github/workflows/simrace.yml#name: Last report
  - src/clojurecraft/simrace.clj#defn prepared
  - bb/clojurecraft/churn.clj#defn evidence-tag
  - bb/clojurecraft/churn.clj#defn asset-name
  - bb/clojurecraft/churn.clj#defn evidenced
  - bb/clojurecraft/churn.clj#defn upload!
  - bb/clojurecraft/issue_check.clj#defn sources
  - bb/clojurecraft/issue_check.clj#defn release-file!
  - "src/clojurecraft/sim.clj#The model as a server keeps a player's chunks"
  - src/clojurecraft/sim.clj#defn column-of
  - src/clojurecraft/sim.clj#defn tick-rand
  - test/clojurecraft/capture_test.clj#the-stops-see-every-column-of-the-square
  - test/clojurecraft/simrace_test.clj#a-run-walks-into-the-next-column-for-its-log
  - test/clojurecraft/simrace_test.clj#a-run-is-the-same-run-every-time
  - src/clojurecraft/capture_nether.clj#defn region
  - src/clojurecraft/capture_nether.clj#defn nether-tap
  - src/clojurecraft/capture_nether.clj#defn take-region!
  - src/clojurecraft/capture_nether.clj#defn reach
  - src/clojurecraft/simrace.clj#defn nether-file
  - src/clojurecraft/simrace.clj#defn sim-nether
  - src/clojurecraft/simrace.clj#defn moment-of
  - src/clojurecraft/sim.clj#defn arrival
  - .github/workflows/simworld.yml#nether:
  - test/clojurecraft/capture_nether_test.clj#the-region-holds-every-nether-point-and-the-margin
  - test/clojurecraft/simrace_test.clj#a-moment-past-the-portal-holds-the-captured-nether
  - test/clojurecraft/sim_test.clj#a-portal-leads-into-the-captured-nether
related:
  - "[[bot/tooling/_moc|Tooling]]"
  - "[[server-model]]"
  - "[[race]]"
  - "[[property-tests]]"
  - "[[real-snapshots]]"
---

# Sim race

A real server carries about 25 bots on its one tick thread (capacity runs 38333898855 and 38348283421); the server model carries as many as there are cores, at about 150 times real time each. So the run is played in the model, in worlds a real server made.

## A start at a fortress

With `:simrace/from "fortress"` in `ci/simrace.edn` (or `--from fortress`) a run starts in its set's captured Nether instead of at the overworld landing: `fortress-start` finds the model's spawner nearest the landing's Nether coordinates (an eighth of the overworld's) and ground on its far side (`fortress-rings`, thirty-two blocks first), the sim joins the player there in the Nether with the kit (`:simrace/kit`, default `fortress-kit-default`: the real race's `:race/kit`: a stone pickaxe, an iron sword and iron armour carried, 16 cooked beef, a table, sticks, flint and steel, both buckets, coal and junk stone and loot, all 36 slots full, as `kit->inventory` places it), and the run plays only the stages from the blaze rods up (`fortress-goals`), so a failure before them is not its to file. Worlds whose Nether holds no spawner are left out. Absent, a run starts from spawn as before.

## Capture: a real world, once
`clojure -M:capture --port --rcon-port --rcon-pass --landings <set>/landings.edn --set R1 --out cache/sim-worlds/R1 [--radius 10]`. For each landing, a client of its own (main's loop and reducer, with `chunk-tap` keeping every `level-chunk-with-light` packet's bytes) joins, is made a spectator and is teleported over `stops`, a view apart, until every column within radius chunks of the landing has arrived (`complete?`); then it leaves. One world file a landing, gzipped EDN: `{:capture/set :capture/landing [x z y] :capture/radius :capture/columns {[cx cz] bytes}}`, bytes as the recordings print them. Nothing is translated: the bot decodes the server's bytes as it decodes any server's. A local run took 49 columns of radius 3 in 20 s, 270 KB.

## The Nether: a real one, once a set
Issue #353. Before it a sim run's portal kept the overworld's columns (`sim/arrive` with no `:sim/nether`), so a bot "in the Nether" tunnelled the landing's stone and aquifers for bricks that were not there, and every sim-only `[:fortress ...]` failure was the model's (#341, #352). `clojure -M -m clojurecraft.capture-nether --port --rcon-port --rcon-pass --landings <set>/landings.edn --set R1 --out cache/sim-nether/R1.edn.gz [--margin 32]` takes one region of the set's Nether: the chunk square round every landing's Nether point (its x/8, z/8, `nether-point`, as `sim/exit-point` scales a portal) and `default-margin` (32) chunks wider each side, `fortress/sight-radius` (512 blocks), the farthest a brick on record is a target (`region`). capture.clj's client (its tap's waits, `settle!`, its stops' spacing) is made a spectator and toured over the region in the Nether (`execute in minecraft:the_nether run tp`), touring again the stops nearest any column the server was still generating (`again`, three tours at most); `nether-tap` keeps only chunks that arrive while the client's world is in the Nether, since the overworld's round the spawn it joined at share their keys. After the tour the bricks the region holds at or over `fortress/brick-min-y` are measured from each Nether point (`reach`: a NETHER line a landing, a NETHERSUM of how many have one within 96, 192, 256 and 512 blocks). One file a set: `{:nether/set :nether/landings :nether/region :nether/columns}`. The first captures (simworld runs 39017470658, 39017472094, 39017473403, 39017474789; 4 to 5 minutes a set, one tour plus a second over 123 columns for R1 and R3) took 6,888 to 7,395 columns a set, 17 to 22 MB gzipped, and every one of the 185 landings had bricks within 512 blocks of its Nether point: within 96 (what a server shows on arrival) R1 28 of 46, R2 4 of 48, R3 0 of 45, R4 11 of 46; within 192 R1 46, R2 12, R3 16, R4 36; within 256 R2 33, R3 19, the rest all. So the margin is the sight radius: R2's and R3's nearest bricks lie up to 512 out. Probes 39075960802, 39075974336, 39075990274 and 39076004444 (each set's worlds, seed 1, to the fortress, 40 wall minutes) played 119 runs; 70 went through their portals and every one came out on the Nether's own blocks (basalt 27, soul sand and soul soil 27, netherrack 8, gravel 3, glowstone 2, blackstone, magma, nether bricks), 64 with lava at the sea's top round them, 18 with bricks on record on arrival (none in R3, whose nearest lie 192 out), and 6 reached the fortress (R2 and R4). 53 failed `[:fortress :no-pickaxe]` within minutes of arriving, tunnelling toward bricks: issue #296, which the real races file too. They first located each landing's fortress too (`locate structure minecraft:fortress`), and dropped it: its ring search named fortresses 600 to 1100 blocks off for R4 landings whose captured bricks were 0 to 250 away. Its own namespace and cache key (`simworld.yml`'s `nether` job: the server world's files and capture_nether.clj, never capture.clj), so the capture changes no lane's worlds.

In a run (`simrace/run`) the set's Nether, read once a set and decoded once (`sim-nether`, found by `nether-file`: `cache/sim-worlds/R1/7.edn.gz` → `cache/sim-nether/R1.edn.gz`), is the model's `:sim/nether` with no `:at`: the player arrives where the server would place the linked portal (`sim/arrival`, [[server-model]]), and the Nether streams `nether-view` (6) chunks round it as a race server's view does, never the whole region at once, which would show the bot fortresses no server showed it. A set with no captured Nether keeps the old behaviour. A snapshot names the Nether's file (`:snapshot/nether-file`) and keeps the model's Nether without its columns; `moment-of` gives them back, and a moment past the portal holds the Nether's columns (the bot's chunks among them) where one before it holds the overworld's (`a-moment-past-the-portal-holds-the-captured-nether`). `world-for!` reads the named Nether and fails without it: judged without it, the portal would lead into the overworld's columns again.

## The model holds the world whole
`sim/init :columns` is the world (`:column` is the world of one column [0 0]), `:decoded` the same columns decoded once when the world was read (`simrace/prepared`), shared by every run of it. The whole world goes to the client at login and stays: a run from spawn to the portal lives in a few hundred columns, tens of megabytes, one value. Each chunk packet carries the shared decoded column (`:chunk/column`, as the socket's reader thread attaches it), so no run decodes. `:view-distance r` streams and forgets columns as a server does instead (`stream`, `in-view`).

## A run
`simrace/run world seed until max-ms` (and `food`, the bar the run starts with, `--food`; full without it, [[sim-hunger]]): the bot from empty hands on the landing's surface, `:go/chain? true` with `plan/through until` and `:go/at` the landing, as `race/land-bot!` gives a real bot (before issue #37 a sim run had no landing, so every landing-relative choice differed from the real race's: no climb home from deep, 32 level steps for a climb under a lake where a real bot has the way home too), against the model, rolling loot and tick randomness from seed (`:seed`, `:tick-seed`, `sim/tick-rand`). Its RESULT is `main/result`, the same map a real run prints, with `:run/world`, `:run/seed`, `:run/ms` and `:run/violations`. A run is a pure function of world, seed and code: never saved, reproduced with `--world f --seed s` at the commit. `clojure -M:simrace --worlds <dir> --seeds n --threads t` plays every world's seeds on a pool, a SIMRUN line per run, `runs.edn`, and the SIMRACE tally: failures grouped by `signature`, [the target it was on, why it stopped, the last intent's kind].

## On the runners
`simrace.yml` runs as lanes beside `race.yml`, dispatched, given back and replaced by the fleet ([[race]]: `fleet.yml`, a code merge restarts both with the new code, `RACE_CONTINUOUS=false` stops new lanes); lane k is runner k of `:simrace/runners` (8) shares, the sets (R1-R4), the target, the simulated minutes a run may take and the wall budget a runner plays (330 minutes). `world.yml` builds each set's server world once; `simworld.yml` captures each set's worlds once from it (`clojure -M:capture`, radius 12: 25x25 columns a landing) and caches them under a key of the world's files and `capture.clj`, and its `nether` job each set's Nether under a key of the world's files and `capture_nether.clj` (above); both are dispatched by hand when a key changes, never looked up by a race. Each sim runner restores every set and its Nether (a miss fails the job at once) and plays its share of the 192 worlds (`--runner k --runners n`, `shard`), each world with seed after seed (`jobs`), on every core, until the budget is spent: a core that ends a run takes the next at once, so nothing waits for the slowest run. Every 20 minutes, staggered by runner (75 s off the real race's), the runner reports the runs that ended since its last report (`bb churn batch sim ...`, [[race]]). Its runs are an artifact at the job's end (`runs.edn`, `simrace.log`, the snapshots); the snapshots a batch's comments name are in the evidence releases (below).

## Failures become issues
Every failed run is marked (`failure/mark`): `:run/signature` (`failure/signature`: [the target it was on, why it stopped]; one bug met in several intents is one signature, the intents listed inside its issue) and `:run/infra?` (the server closed or disconnected it: not the bot's play); the real race's RESULT lines carry both too. Each runner's batches (`bb churn`, every 20 minutes in `simrace.yml` and `race.yml`) file the runs that ended since the last (`failures/report-runs!`, `bb/clojurecraft/failures.clj`; `bb failures report <runs> <title>` does the same for a directory):
- failed runs grouped by signature, the most frequent first; a signature is filed only when a run of the batch reproduced it (`reproduced`: its snapshot recurred against the model on the batch's commit, judged by the churn at batch time, `churn/judge!` for a sim run, `churn/convert!` for a real one), one such run being enough (issue #299: an issue is open only while it has a reproducible snapshot); infrastructure stops (a cut bot among them: one the job's end stopped) and signatures no run reproduced are counted in the output (`summary-lines`), never filed;
- one issue labelled `bot-failure` per signature, found again by a marker in its body (`<!-- signature [...] -->`), open or closed: a closed one whose signature comes back in runs of code committed after its closing is reopened and its section says regressed (`regressed?`; the report gets the commit's date as its third argument), while runs of older code add their section and leave it closed, since they never ran the fix: race 38477665442, on code from before #76 merged, reopened #36 with 66 bots that had run the old planner;
- the issues are read again right before one is opened, so two reports finishing together do not open the same one twice;
- each batch adds one comment to the issue (how many runs, sim or real, the last intents, how many landings, up to five recurring snapshots and nothing else (`repro`, `snapshot-note`: a sim one with the sim race's `--world f --seed s --seeds 1` at the commit, a real one with its bot and server directory, [[real-snapshots]]); the run's page) and brings the body's tally up to date (`with-tally`: rounds, runs, first and last seen, when last reproduced, kept in a `<!-- tally -->` marker; issues from before keep their old sections below it), so every batch notifies, and labels it `sim`, since the model reproduces it and the fix's check can judge it, and `real` when real runs hit it (`labels`); an open issue last reproduced more than `retention-days` (7) ago (its evidence a week old) is closed as not planned with `closing-text` (`stale?`, `sweep!`), and `regressed?` reopens such an issue for any batch that reproduces it; when a real run wins, the report tags the commit and releases the winners' recordings (`bb win`, `win/winners`), the proof kept past the artifacts' seven days (issue #116);
- at most ten new issues a batch.

## Evidence that lasts
The snapshots a batch files on are uploaded before its comments are written (`churn/upload!`): to the release of the hour, `evidence-<UTC day>-<hour>` (`evidence-tag`; a prerelease, never the latest, made by the hour's first batch that needs it), each as `<run>-<file>` (`asset-name`: a snapshot's file name repeats in every run of a lane). A reproduced run whose asset the release does not hold afterwards loses `:run/recurs?` (`evidenced`), so no comment names evidence that may vanish; the batch's log and the job's summary say how many went up (`evidence-text`). The comment's line is "snapshot `R4-35-2.edn.gz` as `<run>-R4-35-2.edn.gz` in release `evidence-2026-10-16-23` (moment ...)" (`failures/snapshot-note`), and `bb issue-check fetch` reads both that and the older "in artifact" form (`sources`), downloading a release's asset (`release-file!`) or, for an old comment, the runner's artifact. Why: an artifact is uploaded only at the job's end, every merge to `main` cancels every lane, and GitHub kills a cancelled job about four minutes on, so a lane whose last report ran long never uploaded (race 38962622402, cancelled 2026-10-16 23:05 UTC, has no artifact: issue #37's evidence was gone and the Copilot session on it had nothing to judge, PR #310); artifacts also expire after seven days. An hour a release, not a day: GitHub holds 1000 assets a release, and the comments of 2026-10-16 named about 2,000 snapshots. The last report, on a cancel too, stops its conversions or judges after 120 s (`bb churn batch ... 120`), inside those four minutes, and `simrace.yml` stops the sim race before it (Stop the sim race), as `race.yml` stops its races: a cancel leaves them running on every core, and the judges, then at nice 19, got nothing done (sim lane 38986998175). Judges run at nice 0 on a one-core JVM (`sim-nice`, `one-core`), as conversions do ([[real-snapshots]]).

## The issue check: a validation over a kept moment
A fix's check (issue #77) judges a moment and never reruns the system from spawn (Simulant's rule: write the validation, run it against past results). A run keeps its **moments** (`sim/play-from`, `kept`): every `moment-every-ms` (30 s simulated) the whole of what the next tick depends on, the bot's world, the model's state, the packets the bot has sent and the model not taken, the tick seed's state, whether `:go` was sent and the packets in flight, the last `moment-keep` (12) of them, six simulated minutes; worlds and models share structure, so the ring is pointers. Playing on from a moment is the run itself, packet for packet (`a-moment-plays-on-exactly-as-the-run-did`); the one tick before the stop, which snapshots used to keep, lost the bot's last packets and restarted the tick seed. When a run fails, `simrace/play!` keeps a moment from before the choice that failed (`decision`, `chosen-moment`): an intent that failed three times in a row went wrong when the first of them started (its `:started` intention fact); a wait that ran out, when it began (`:plan/waiting-since`); a `:timeout` is a stall, judged over its last `stall-window-ms` (5 simulated minutes); the moment is the newest kept at least `decision-lead-ms` (30 s) before that, and `:snapshot/moment` says why, when and the window it is judged over (from the moment to a minute past the stop). The snapshot (`simrace/snapshot`, `<out>/snapshots/<set>-<n>-<seed>.edn.gz`) holds that moment (`:snapshot/next` the next tick's inputs), the captured world's file and the chunk keys held (reattached from the file, never copied), the facts as `d/serializable`, the seed, the target, the commit and the signature: 80 KB to 1 MB gzipped, nearly all of it the facts. `bb failures` names each sim reproduction's snapshot, its release asset and moment in the issue's comment; `bb issue-check fetch <n>` reads the comments newest first and brings at most two into `test/issues/<n>/`, one a world (two seeds of one world are one moment), only snapshots with a kept moment. `simrace/judged` plays a snapshot on from its moment (`moment-of`) over its window and no longer, stopping when the plan ends or reaches the signature's stage; `recurs?`: a failure recurs with the same signature, a stall when the window ends with its stage not reached. `clojure -M:simrace --judge f1,f2` (`judge!`) judges uninstrumented and prints a JUDGE line a snapshot (signature, moment, window, outcome, simulated and wall ms) and its STORY (the intention facts from the moment on), then SCENE and SLICE lines for the moment (`start`) and where the play ended (`end`): the bot's position, health, food, items and intents, and the blocks round its feet layer by layer (`scene/facts`, `scene/slice`), which the Copilot session's brief carries so a fix starts without a probe; `--expect recur|pass` sets the exit code. `bb verify <n>` runs the judge on the working tree, the suite without the forest property and `bb check` as one command with one verdict. `issue.yml` on a `fix/<n>` pull request judges only the issues it names (the branch and its `Fixes #n` lines), restoring only the captured worlds those snapshots name, on the base commit (each moment must reproduce its failure, else it proves nothing) and on the PR's code (none may), in parallel, and writes both to the step summary; an issue labelled `sim` with no snapshot fails it (#116). Older issues are not judged again on every pull request: the races are the regression guard, and the filer reopens an issue whose failure comes back on newer code. Before this (PR #183, 2026-10-15) the check judged all 40 snapshots of 19 issues on every PR, instrumented (`sim/step`'s spec walked all 625 columns twice a tick), replayed the three `:timeout` snapshots from their landings for up to 90 simulated minutes, and took 21 minutes; earlier still, `issue.yml` replayed ten reproductions from spawn to the Nether. A real race's failure becomes the same value ([[real-snapshots]]): a self-contained snapshot whose columns ride in it (`:snapshot/columns`), which `judge!` prepares itself (`own-world`, `world-for!`) with no captured world restored. `clojure -M:simrace --from-landing f` still replays a snapshot's run from its landing to its stage (`replay`, `stage-until`), a probe's tool for the whole road. Beside the check a fix/<n> pull request runs `unit.yml`, every test but the forest property and the static checks (#98).


## First result
The local 49-column world, two runs to the Nether, 16 s wall: both reached wood, kit, pickaxe, cobblestone, stone pickaxe and furnace in about 15 simulated minutes, then failed `[:ingots :no-pickaxe]`, the reason five of runner 13's bots stopped with in race 38340947798.

## Gotchas
- `clojure -M:test -n ns -v var` runs the whole namespace and the var: the runner takes the union. Name vars alone.
- A world's edge is where its capture stopped: past it `block-at` is nil, unloaded to the bot. A run that needs more than the radius gave it stops at the edge. The Nether's edge is its region's: 32 chunks past the farthest landing's Nether point, so a tunnel exploring more than about 512 blocks out meets unloaded cells (`fortress/safe?` refuses them) where a real bot would go on.
- Not modelled in the Nether: the linked portal's frame (the player arrives on the cell the server would build it over, with no obsidian or portal round it), and a portal home.
