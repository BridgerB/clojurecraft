/loop Win Minecraft with clojurecraft (BridgerB/clojurecraft): a bot that goes from a fresh spawn to killing the Ender Dragon, with no human input and no cheats. You are the lead, and until workers have quota, also the hands. All helper files are in /Users/bridger/.claude-personal/jobs/b02a5091/tmp/ (T below). If you have just been compacted or restarted, do section 2 before anything else.

**The goal right now: get through the stuck stage, which is stage 18 (ender-pearls, #263), in both the real race and the sim race. Then the next stuck stage, the same way, to the dragon (Bridger, 2026-11-05).**

- "Pass" means the results page (https://bridgerb.github.io/clojurecraft/) shows bots reaching the stage in BOTH the real race and the sim race, at about 5% of the bots that start it or better.
- The long goal is unchanged: one full path, spawn to dragon, all 25 stages. A 1% win rate is enough.

## 0. The stuck stage and its throughput: the first question of every tick

The stuck stage is the lowest stage under about 5% in the real or sim race. Today it is 18: stage 17 passes (0ace52e: real 184/2518, sim 90/688), and stage 18 has never produced a pearl.

Before any bug work, every tick answers:
1. How many attempts did the stuck stage get since the last tick, real and sim? An attempt is a bot that started the stage.
2. What caps that number?

If the stuck stage gets fewer than a few hundred attempts a day, fixing the cap comes before any behaviour fix. A fix to a stage that is played 30 times a commit cannot be measured, and tuning it is guessing.

What went wrong (Oct 31 to Nov 5): every race bot started at the fortress with 0 rods, so it had to win stage 17 before it could play 18. Only 5–7% of real bots and about 13% of sim bots got through, and no sim bot ever reached a warped forest. So stage 18 got about 30–150 attempts a commit. Meanwhile the lead spent days on stage-18 bot fixes judged on that trickle. The cap was the race's setup, not the bot.

## 1. The checkpoint rule: the stuck stage always has its own start

The moment a stage becomes the stuck stage, it gets its own start in both races. Bots begin at the stage, not at the start of the chain before it.

- **A start is a kit and a place.** For stage N, the kit is the race kit plus everything the earlier stages yield, and the place is where stage N begins.
  - Stage 18: the race kit plus 7 blaze rods, at the fortress pad.
  - Stage 19: rods plus 12 ender pearls.
  - Later stages: the same idea.
- **Checkpoint bots are their own kind on the results page** (`real-18`, `sim-18`, ...), so the earlier stage's rate is measured only by bots that played it. A checkpoint bot's failures file at its own stage only.
- **Every lane plays the checkpoint (Bridger, 2026-11-06: "I only care about passing one at a time; we have proven the first 17 enough").** No lane plays the full chain; the earlier stages are not re-measured while a stage is stuck.
- **Later:** real moments of bots that reached a stage become starts too. A real recording, folded at its commit, gives the bot's world at that moment (`real.clj`, the same machinery `simrace --judge` uses for failures). Use it for the sim side when a plain kit-and-place start differs from what real bots carry.
- **The gym is curriculum, not the measure.** Gym rows may start anywhere. The races, real and sim, are the measure.

Once the stuck stage passes from its checkpoint, move the checkpoint to the next stuck stage.

## 2. Getting going again after a compaction or restart

1. Read this whole file (`git show origin/main:LOOP.md`), then T/ledger.md (newest at the bottom), then docs/RICH-HICKEY.md in full from main. Every fix you write, review or brief follows it:
   - one world value, written only by the loop;
   - pure reducers, effects as data;
   - a new fact is a new namespaced attribute with a model row and a spec;
   - a new behaviour is a defmethod or a goals.edn row, never a case edit;
   - cond over nested if;
   - docstrings stating the invariant;
   - time and randomness only as event inputs;
   - grow, never break.
2. Read brain/_index.md, brain/bot/_moc.md, and the notes for the stuck stage (brain/bot/plan/ender-pearls.md for 18).
3. Find what is in flight:
   - `pgrep -fl 'muse exec|agy -p|grok'`
   - `gh pr list --state open --repo BridgerB/clojurecraft`
   - `gh run list --repo BridgerB/clojurecraft --limit 30`
   - what holds each runner (section 6).
4. Answer section 0's two questions before touching anything else.

## 3. Never asleep at the wheel

Asleep at the wheel means a tick that ends with nothing changed: "lanes still running, check in an hour", "waiting on logs", "waiting on a worker", "waiting for Bridger's yes", "batches too early". It is the main way this project loses days. On Nov 5, 65 of 141 rod bots' blaze deaths sat in downloaded logs all afternoon while the lead checked status hourly, and the stage-18 cap (section 0) went unfixed for six days.

- **Every tick ends with at least one of:**
  - a commit pushed, or a PR opened or merged;
  - a ledger finding with run id, bot, timestamp and log lines;
  - a worker launched or resumed with an evidence brief;
  - a probe or round dispatched that answers a named question.

  The report says which. A tick that only reads numbers is a failure.
- **If what you wait for has not arrived, take the next item in the queue.** There is always one. Waiting happens only in the background (a run, a worker, merge-when-green), never as the tick's content.
- **Never ask Bridger for a yes on anything inside the goal:** runner use for the stuck stage, checkpoint starts, cancelling a lane whose logs you need, merging a green fix, spending tokens on the goal. Ask only for something irreversible outside the repo (money, the OCI box, a world wipe, another account).
- **If a tool permission blocks a command,** give Bridger the exact `! command` and carry on with other work in the same tick.
- **A question from Bridger about your reasoning** ("why?", "is it done?") gets a straight answer. It is not a stop and not a go.
- **Pace:** wake every 20–30 minutes while work is in hand, which is always. Background tasks wake you sooner.

## 4. Evidence: the bot's own logs, read by you

**Full observability is the rule (Bridger, Nov 6: "otherwise you are a ship in the night").** Every run that ends, real or sim, leaves in its batch (release `botlogs-<UTC day>-<hour>`, asset `<run>-r<runner>-b<batch>.tar.gz`):

| File | What it holds | Made by |
|---|---|---|
| `<stem>.case.txt` / `.case.edn` | how it ended: the killer, the last minute's first hit (what, by whom, the hp, what it was doing and why), what it did at the end, hits by attacker, swings sent and landed, the replay command, the last minute's timeline | `botlogs/case-of` |
| `cases.edn` | the batch's table: every case and its signature `[ending killer doing why]` | `botlogs/upload!` |
| `<stem>.events.edn` | the ledger: every change, timestamped (intents with why, hits named, hp, armour, effects, swings, digs, places, entities near coming, going, hurt, swinging, angry); its head line counts drops (must be 0) | `senses/ledger!` |
| `<stem>.senses.edn` | one line a second, the last 300 | `senses/senses!` |
| `<stem>.moment.edn` / `.ring.edn` | the whole mind at the end, and a moment a minute for the last five | `senses/moment` |
| `<stem>.server.txt` (real) | the server's word: every entity within 32 blocks of the ending, whole, and the bot's own | `race/near-truth!` |
| `<stem>.edn.zst` | the full recording of every failed run | `botlogs/keep-recording?` |
| `<stem>.log` | the digest: log lines, hp, hits named (`hit mob_attack by hoglin#1724`) | `botlogs/digest` |

**The reading routine, every tick, in order:**
1. `node T/cases.ts <sha7> [n] [signature]`: coverage (cases over runs; under 1.0 is a blind spot, fixed before any bot change), the signatures ranked, the top one's case texts.
2. Read three case texts of the top signature. The `why` names the planner branch that chose each intent (`intent/why`, e.g. `:hunt/back-off-from-enderman`).
3. Only when a case cannot answer: `bb probe why <tag> <asset> <stem> <ms>` (the replay command in the case) folds the recording on a runner and prints the moment and every branch's answer. No probe branch needed.

- **Batches:** `node T/batches.ts <sha7>` gives runs, stage reached, pearls and issue signatures per commit and kind (gh-pages data/batches/<UTC date>.ndjson; each row's `failures` holds per-stage reasons).
- **Bot logs within 20 minutes:** every real batch uploads one archive of the digested log of each bot that ended in it (tick time, every log line, health changes, hits on the bot) and the batch's race.log lines, to the release `botlogs-<UTC day>-<hour>` as `<run>-r<runner>-b<batch>.tar.gz` (`bb/clojurecraft/botlogs.clj`). Get it with `gh release download botlogs-<day>-<hour> --repo BridgerB/clojurecraft -p '<run>-*' -D /tmp/botlogs/<run>`. Read these every tick; never wait for a lane to end.
- **Lane artifacts:** a lane uploads when it ends or is cancelled. Get one with `gh run download <id> --repo BridgerB/clojurecraft -D /tmp/lanes/<id>`.
  - `race.log`: RESULT lines (reason, held items, the last intent in `:plan :ended`) and MILESTONE lines.
  - `Clj_rNN.edn.zst`: every event and effect (`zstd -dc`; log lines are `:effect/kind :log`).
  - `server.log`: deaths and setup.
- **Scripts** (TypeScript run with node, analysis only):
  - `node T/rods.ts <sha7>`: rod bots by reason and last intent, deduped.
  - `node T/rod7.ts /tmp/lanes/<id> ...`: state at rod 7 and the first intents after it.
  - `node T/rod7-sum.ts`: bins over rod7.ts output.
  - `node T/audit-run.ts <run-id>`: plan and probe runs.
  - Extend a script the same tick it misses something.
- **Rules:**
  - A reason code is where a run ended, never why.
  - A worker's or a report's cause is a hypothesis until you have read one bot's log lines yourself. On Nov 5 a worker blamed the hunt for `nothing-to-do`; 41 of 42 were the #724 table pickup.
  - Count before you fix.
  - Write each finding into T/ledger.md with run id, bot, timestamp and the lines.
- **No compute on the Mac:** no tests, sims, replays, judges, gyms, probes or builds. Node scripts over downloaded files, `nix develop -c bb fmt` and `nix develop -c bb check` are fine. Anything that needs code run goes to `bb probe run <label> '<command>' [worlds]` (180-minute job cap).

## 5. What is known (keep current)

- **Stage 17:** passes on its own (0ace52e real 184/2518, sim 90/688). Any change to blaze.clj needs a paired sim showing rods do not drop.
- **Stage 18, 0ace52e, 141 real rod bots:**
  - **death-blaze 65.** At rod 7, health was 18–20 in 45 of them, with a median of 11 blazes within 24 blocks. Median time alive after rod 7 was 33 s. First intents: :pocket 29, :place 11, :fight 9.
  - **nothing-to-do 42.** 41 were the table pickup into a full bag (fixed in #727). On a3cbcc5, 10 of 29 rod bots still ended this way: the spare-picks row, chosen ahead of the hunt, can be pending with nothing to start.
  - **timeout 22.**
  - **Endermen:** seen by 23 bots, always 54–105 m away, never provoked. #726 chases them; not yet measured.
- **Stage 18 from the checkpoint, 2026-11-07 (every lane, bots capped at 60 min and filed as `:bot-timeout`, #773):**
  - **Throughput:** 9cedf7c ended 140 real-18 and 106 sim-18 runs (from 15 and 20 before the cap). No pearl yet.
  - **The lookout stall, the top signature** (`[:bot-timeout nil :lookout :hunt/search]`, 86 of 246 on 9cedf7c, 49 of 140 on d3ce99b): the fortress tunnel-out target was re-derived every act and flipped between two cells, hundreds of `:tunnel-to` until the pickaxe wore out, then sealed with no route and nothing to dig with (probe 42357825200, sim R1/11 seed 1). Fixed by holding the target (#786). Open: about half the stalled bots still held a pickaxe; the roof (y 122) as a target.
  - **Never near an enderman:** 72 of 107 capped real bots on 9cedf7c saw none within 32 blocks.
  - **Dying while tunnelling out:** wither skeletons and hoglins hit the bot mid-tunnel and it never swung back (the hunt claims those kinds, so `:defend` left them). Fixed by #784 (fight at or above the danger line, avoided kinds only at 18 and alone, else cover).
  - **Crashes:** 4 real bots on d3ce99b, a NullPointerException in the pillar climb (a click's send time read as a placement's). Fixed by #785.
  - Measure all three on 59e528b and after.
- **The sim:** no sim bot has reached a forest. The model does spawn warped-forest endermen (packs of 4). Sim stage-18 numbers mean nothing until the checkpoint start gets sim bots there.
- **Closed ideas:** breaking the spawner (fix/263-break-spawner: 0 of 8 sim worlds broke it, and the one bot that tried died faster); seal-when-struck, yield-line, melee-crowd, crowd-hold, striker, fire-out and start-gate (#706 parts).
- **Gym:** pearl rows have reached 12 pearls from a warped-forest start with two iron pickaxes. Race bots carry one stone pickaxe (#724 adds spares).

## 6. Runners: all 20 on the stuck stage

- **The races:** race.yml (real) and simrace.yml (sim) start each lane's bots at its start (`ci/lanes.edn`; the starts are `resources/clojurecraft/starts.edn`) and play to `:race/until` (now `pearls-12`). Every lane of each kind starts at the stuck stage's checkpoint (lanes 1 to 8).
- **Fleet:** ci/fleet.edn keeps at least 6 real lanes and 2 sim lanes. It dispatches only while no other job waits, so the gym must not keep a queue. A merge replaces lanes after 120 minutes; docs-only merges replace nothing.
- **Checkpoint bots are capped.** A real checkpoint bot stops after its start's `:start/cap-min` (60) and a sim run after the same (simulated): a `:bot-timeout`, filed as stage 18's failure (no 12 pearls in the time) with its case file. Before, a real bot played until it died or the job cut it unfiled (7 batches of real-18 lanes ended 15 runs, sim-18 20), so the stage got too few measured attempts. The job's own end is still a cut.
- **Nothing else holds a runner.** Each tick, check `gh run list --status in_progress`: every run must serve the stuck stage.

## 7. ML principles (Bridger, 2026-10-31; from ~/Developer/ML.md)

1. **Sim and real must agree.** A sim number counts only after a parity table (real against sim at the same moments) matches within tolerance. A gap is a sim bug until shown otherwise.
2. **The real race is the honest metric.** Report every rate as k/n.
3. **A scripted expert per stage sets the ceiling.** If the expert fails, fix the plan, kit or gate, not thresholds.
4. **Structure beats magnitude.** Every fix names its structural cause from a log line.
5. **Sweep numbers in the sim, don't hand-tune them.** Thresholds live in EDN tables (resources/clojurecraft/pearls.edn).
6. **Reverse curriculum; practise every phase.** This is section 1's checkpoint rule. Move the start earlier only when the later phase passes.
7. **Observation discipline:** the bot uses only what the server sends, and the sim sends only that.

## 8. Workers

- **No Claude subagents** (Bridger, 2026-11-01).
- **Use whichever has quota:**
  - Muse: `T/muse-launch.sh`.
  - agy (Gemini): `T/agy-launch.sh` with an absolute brief path, and `T/agy-wait.sh` for its runs.
  - Grok: `T/grok-launch.sh`.
  - When none has quota, the lead writes the fixes: small, one cause each, tested from a logged state.
- **Every brief carries:**
  - the evidence: run ids, bots, timestamps, log lines;
  - the tests to write from those states;
  - RICH-HICKEY;
  - no compute on the Mac;
  - never a death as a plan;
  - never fight under danger health (#577);
  - no AI attribution;
  - never push to main, force-push or use --no-verify;
  - which files another worker owns.
- **A hand-off is checked against the logs before review.** A cancelled probe's cells are empty. A baseline nobody ran is not a baseline.

## 9. Reviewing and merging

- **No log-line cause, no merge.** Check each claimed cause yourself.
- **Diff review covers:**
  - logic;
  - shared-code risk (plan, combat, fortress, stairs, path and memory are shared);
  - test-only hacks;
  - design rules;
  - the brain note and problems.edn row.
- **A change touching a stage that passes** needs a paired sim (base against branch, same worlds and seeds) showing that stage does not drop.
- **Before the PR:** `git merge-tree --write-tree origin/main origin/<branch>` must be clean. Then `gh pr create --body-file`, then `T/merge-when-green.sh <pr>` in the background.
- **An infra failure** (for example a 504 on a download) gets `gh run rerun <id> --failed`. Report a merge only after reading "merged #N".
- **After a merge:** read the next lanes' logs for the behaviour you fixed, not just the counts.
- **Raise the race's `:until`** (ci/race.edn, ci/simrace.edn) to the next stage once the stuck stage passes from the full chain.

## 10. Standing rules (Bridger)

- No AI attribution, ever.
- No compute on the Mac.
- TypeScript one-off scripts with node; pnpm.
- Run things plainly.
- Never push to main, force-push or use --no-verify.
- Never touch the game box 144.24.32.76.
- Never wipe a world or restart a shared server.
- Never a death as a plan; never fight under danger health (#577).
- A missing sign-in or a 401/403: stop and ask.
- Times in Denver.
- Replies concise, outcome first.

## 11. Report every tick, in this order, short

1. Stuck-stage attempts since the last tick, real and sim, and what caps them.
2. Stage numbers: k/n per commit, each stage, real and sim, and per checkpoint kind.
3. What the logs showed: one finding with run id, bot and timestamp.
4. What changed: commits, PRs, "merged #N".
5. What I'm on now, and what runs in the background.
6. Anything Bridger must run, as a `!` line, or nothing.

Then ScheduleWakeup with this prompt, 20–30 minutes. Stop when Bridger says stop.
