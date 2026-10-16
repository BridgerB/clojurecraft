---
title: Real snapshots
description: A real race's failure as the same snapshot value a sim run's is: the recording folded at its own commit, a moment kept before the choice that failed, the server model joined to the bot's world there, kept only when it recurs against the model.
type: decision
tags: [bot, tooling, sim, race, snapshot]
aliases: [real snapshot, --from-recording, real.clj, sim/joined, joined model, model gap, :snapshot/columns, :snapshot/source]
status: verified
lastUpdated: 2026-10-16
verifiedAgainst: HEAD
sourceRefs:
  - src/clojurecraft/real.clj#defn fold-step
  - src/clojurecraft/real.clj#defn joinable?
  - src/clojurecraft/real.clj#defn model
  - src/clojurecraft/real.clj#defn day-time-of
  - src/clojurecraft/real.clj#defn convert
  - src/clojurecraft/real.clj#defn nether-of
  - src/clojurecraft/real.clj#defn from-recordings!
  - "src/clojurecraft/sim.clj#is that whole Nether instead, decoded once on arrival"
  - test/clojurecraft/real_test.clj#a-portal-at-the-moment-leads-to-the-real-nether
  - src/clojurecraft/real.clj#defn gap
  - src/clojurecraft/real.clj#defn from-recording!
  - src/clojurecraft/sim.clj#defn joined
  - src/clojurecraft/sim.clj#defn player-eid-of
  - src/clojurecraft/simrace.clj#defn own-world
  - src/clojurecraft/simrace.clj#defn world-for!
  - src/clojurecraft/record.clj#defn reduce-entries
  - src/clojurecraft/record.clj#defn input-of
  - bb/clojurecraft/churn.clj#defn convert!
  - bb/clojurecraft/churn.clj#defn convertible
  - bb/clojurecraft/churn.clj#defn converted
  - bb/clojurecraft/churn.clj#defn conversion-text
  - bb/clojurecraft/failures.clj#defn snapshot-note
  - bb/clojurecraft/failures.clj#defn labels
  - bb/clojurecraft/issue_check.clj#defn world-of
  - bb/clojurecraft/churn.clj#defn judge!
  - bb/clojurecraft/failures.clj#defn stale?
  - src/clojurecraft/simrace.clj#def slow-ms
  - test/clojurecraft/real_test.clj#one-recording-converts-to-one-snapshot-and-one-verdict
  - test/clojurecraft/real_test.clj#a-runs-own-recording-judges-as-its-sim-snapshot-does
  - test/clojurecraft/real_test.clj#a-nether-moment-plays-in-a-nether-model
  - test/clojurecraft/real_test.clj#the-model-joins-holding-what-the-server-held
  - test/clojurecraft/real_test.clj#a-moment-is-kept-only-where-the-model-can-join
  - test/clojurecraft/real_test.clj#a-dimension-change-drops-the-columns-sent-before-it
related:
  - "[[bot/tooling/_moc|Tooling]]"
  - "[[sim-race]]"
  - "[[record-replay]]"
  - "[[server-model]]"
  - "[[race]]"
---

# Real snapshots

Issue #299. A real race's failure used to leave a recording and the line "replay its recording". A replay folds the same events with the same code, so it shows what happened but cannot judge a fix: the server's half of the conversation is frozen in the file. The real-only issues (#37, #284, #286, #287 when it was written) had no evidence a pull request's check could judge. A recording already holds everything a sim snapshot needs, so a real failure becomes the same `:snapshot/*` value a sim run's is, and `clojure -M:simrace --judge` and `issue.yml` judge it with nothing new.

## The problem, as problems.edn states one
- **Statement.** Turn a real run's failure into a snapshot the model can play on: the bot's world at a moment before the choice that failed, and a server model in the state the real server was in then, as far as the bot was told.
- **Needs.** The bot's world at any moment (the recording folded with the reducer that made it); the terrain (every `level-chunk-with-light` the server sent is in the file byte for byte, which is all a captured world is, `capture.clj`); the blocks changed since each column arrived (the bot's overlay, `:world/blocks`); the server's view of the player (window 0, health, food, the held place, the state id, its entity id); the entities the bot tracks.
- **Risks.** A fold by other code is another run (#111); a moment inside a container window the model never opened; what the bot never heard (the time of day, a closed furnace's contents, the hunger clock, the respawn point, entities the model does not model); a snapshot over GitHub's 100 MiB.
- **Done.** A recorded real failure turns into a snapshot on a runner that recurs on `main`'s code with JUDGE and STORY lines that match the recording; the real rounds' comments list real snapshots and `bb issue-check fetch` takes them.

## Decisions
- **The model joined mid-play** (`sim/joined`): a constructor from data, not from the bot's world, so `sim.clj` keeps owning its own keys and `real.clj` owns the translation. No handshake: `:sim/phase :play`, the clock at the bot's `:time/now` (deadlines are absolute in the world, so the model's clock continues the bot's), position, look, window 0 (`real/window-0`: the inventory at the slots window 0 shows it, armour head to feet, the grid's cells), the held place, the state id, health, food, saturation, air, difficulty `:normal` (`simrace/difficulty`). The player's entity id is the real one (`:sim/player-eid`, `sim/player-eid-of`): the bot counts a pickup only when the collector is its own id.
- **Columns by bytes, decoded by the bot** (`real/model`): the model holds the columns the recording had sent by the moment, in the bot's dimension (a dimension change drops them, `fold-step`), within `capture/default-radius` (12) chunks of the bot: the same square a captured world has. The held ones are handed over as the bot decoded them, the rest decoded once; nothing is re-encoded. The server's changes since a column arrived are the bot's confirmed overlay, given as the model's `:sim/placed` (a guess of the bot's own dig, `:world/unconfirmed`, is not the server's word). `:sim/view` is what the bot held and `:sim/view-distance` how far it held (`reach`), so the model streams the rest as the server did. Rejected: re-encoding the bot's decoded sections (a chunk encoder the bot does not need, and a second place the format could go wrong).
- **Entities under their own ids** (`mobs-of`, `items-of`): every mob of a kind the model has (`mob/kinds`) with its told health, every dropped item whose stack was told, ready to pick up; everything else the bot tracks (other bots, bats, squid, piglins) is told removed with the first tick's packets, because a model that never moves them would leave the bot fighting a statue (`:ghost`). Every model column counts as populated: the real animals are the mobs given.
- **When the model can join** (`joinable?`): in play, given its goals, loaded, alive, socket open, no container open, nothing on the cursor. A window lives in the server the model never was.
- **The moment, exactly as a sim run's**: `fold-step` keeps a joinable moment every `sim/moment-every-ms` (the last `sim/moment-keep`, `sim/kept`), and `convert` chooses with `simrace/decision` and `simrace/chosen-moment`. Its next tick: nothing pending (the real server had taken the bot's packets), `:go` long sent, the tick seed at the snapshot's seed.
- **Self-contained**: `:snapshot/columns` and `:snapshot/landing` stand in for `:snapshot/file`, so `simrace/own-world` prepares the world from the snapshot itself and `issue.yml` restores nothing (its world grep finds no `cache/sim-worlds`). `:snapshot/source :real`, `:snapshot/recording`, `:snapshot/bot`, `:snapshot/round` say where it came from.
- **Fold at the recording's commit.** A fold is the run again only with the reducer that recorded it. The churn converts on the race's runner, at the commit that played the race, so the fold is exact there; the judge of the result on later code is what the check does with every snapshot.
- **The Nether is real.** The Nether's columns from the recording (16 sections from y 0; `chunk/decode` reads the height from the column) make a Nether in the model where the sim's own is one column (`:sim/nether`). `:sim/dimension` comes from the bot's, so the model's ultrawarm water, its mob rules and its column height follow (`a-nether-moment-plays-in-a-nether-model`). A moment still in the overworld of a run that ended in the Nether gets that Nether as its portal's destination (`nether-of`: the Nether columns the recording holds by its end round where the bot last arrived, `:nether-at`; `sim/arrive` takes them as `:nether {:columns :at}`, decoded on arrival, the overworld's entities left behind): the fortress failures came seconds after arrival, so their moments stood at the portal (`a-portal-at-the-moment-leads-to-the-real-nether`).
- **The time of day is a guess** (`day-time-of`): set-time is decoded fieldless, so the bot never knows it; midnight when an undead stands unburnt under the open sky, else noon.
- **The honest gate** (`from-recording!`, `gap`): the snapshot is written, read back as the check reads it, and judged at once with the same code. Kept (a `REAL` line) only when it recurs; otherwise it goes under `gaps/` and a `GAP` line names how the bot got past: `:reached`, `:other` (failed otherwise), `:active`, `:crashed`, `:halted` (out of wall time: the check would count that as recurring, but it is no sight of the failure), `:no-moment`, `:no-signature`. A gap is a finding about the model, never evidence.

## In the races
Every 20 minutes a real lane's batch (`bb churn batch real`, [[race]]) converts its failures before it files them (`convert!`). `convertible` takes the failed runs of real bots, the first `real-per-signature` (2) of each signature, the most a fix's check judges; one `clojure -J-Xmx3g -M:simrace --from-recording` folds their recordings (`<data-dir>/<s>/<bot>.edn`, closed before the bot's RESULT line was printed, `main/run-bot!`) on the race's runner at the race's own commit, `nice -n 19` under the servers, stopped after `real-budget-ms` (10 minutes, so the job's last report still uploads), the snapshots under `<data-dir>/snapshots/real-<runner>-<s>-<bot>.edn.gz` (`snapshot-name`, unique in the artifact: both servers' bots have the same names) and its lines in `snapshots/convert-<n>.log`, both in the round's artifact. Its REAL lines become each run's `:run/snapshot` and `:run/moment` (`converted`), so the issue's comment leads that run's reproduction with "snapshot `f` in artifact `a` (moment ...)" (`failures/snapshot-note`), the words `bb issue-check fetch` reads, and names its recording under its server's directory (`:run/dir`); a real snapshot's file is its own world for fetch's one-a-world rule (`issue-check/world-of`). A run whose snapshot was kept is `:run/recurs?`, and since #299's filing rule only such runs file anything: an issue is open only while it has a reproducible snapshot (a sim batch judges its snapshots the same way, `churn/judge!`), labelled `sim` (`failures/labels`): the check requires and judges its snapshots, and the Copilot sessions' setup fetches them. The batch's log and the job's summary say how many were kept and the gaps by kind (`conversion-text`).

A verdict must hang on the code alone, never on the runner: `s2/Clj_r13` recurred after 220 s of judge in the first probe and halted at 240 s in the second, the same snapshot to the byte, so a recurring snapshot judged in more than `simrace/slow-ms` (half `judge-wall-ms`) is a gap, `:slow`, and a JUDGE line says `:slow?` (`one-recording-converts-to-one-snapshot-and-one-verdict`).

## First results
Probe 38940300595, then 38942963330 with the real Nether, on five recordings of race 38929353563 runner 4 (code 6bad315), folded at that commit with this conversion laid over it and judged again with the branch's code (main's bot):
- `s2/Clj_r17` `[:fortress :no-path]` (#284) and `s2/Clj_r05` `[:fortress :blocked]` (#286): recur on both, 5 to 7 s of wall a judge, their STORY lines the recording's last intents (`:tunnel-to [485 37 447]` failing `:no-path`; `[536 70 386]`, `[441 70 387]`, `[536 70 387]` failing `:blocked`). Before the real Nether both were `:other`, `[:fortress :rejected]` in a copy of the overworld.
- `s2/Clj_r13` `[:water-bucket :no-water]`: recurred in the first probe after 220 s of wall for 50 simulated seconds, then `:halted` at the 240 s budget in the second: the bot's own ticks are that slow there (a fold of its recording took 240 to 350 s).
- `s1/Clj_r03` `[:ingots :boxed]` (#37): `:active`. The real bot backed off and fought a mob (`:back-off`, `:fight`) before its climb boxed; the model's mobs spawn by their own rolls and the bot mined on.
- `s1/Clj_r02` `[:portal :no-stand]`: `:active`, the build still running at the window's end where the real one failed three times in a second.

Folds took 29 to 350 s on a probe runner; each snapshot is about 2 MB gzipped. Live, the race lanes at 6eba8ee, cancelled by a merge 25 minutes in, converted their failures in their last reports: 15 conversions, 5 kept (race 38948145791 runner 8: "churn: real snapshots: 1 kept of 2"), the gaps `:active` 7 and `:reached` 3, each about half a minute on the race's runner.

## Known gaps
The time of day (a guess); a closed furnace's contents (the model's furnaces start empty); the hunger clock (exhaustion and the heal timer start at zero); the respawn point (a death respawns on the landing, where the real server uses the world spawn); entities the model does not model (told gone); fluids already flowing (the model wakes a fluid only when a neighbour changes); a portal out of the Nether (the model goes nowhere). Each is a way a real snapshot can fail to recur, which the gate prints rather than files.

## See also
- [[sim-race]] - the sim snapshot this one copies, and the issue check.
- [[record-replay]] - the recording, and why a fold needs its own commit.
- [[server-model]] - the model joined here.
