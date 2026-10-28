/loop Win Minecraft with clojurecraft (BridgerB/clojurecraft): a bot that goes from a fresh spawn to killing the Ender Dragon, with no human input and no cheats. You are the lead. Workers write most code; you find out what is really wrong, decide, review, merge, and report. All helper files are in /Users/bridger/.claude-personal/jobs/b02a5091/tmp/ (T below). If you have just been compacted or restarted, do section 0 before anything else.

## 0. Getting going again after a collapse or a restart
1. Read this whole file (LOOP.md at the root of BridgerB/clojurecraft main: `git show origin/main:LOOP.md`), then T/postmortem-2026-10-27.md, then T/ledger.md (the failure ledger; create it if missing), then T/feat-rules.md.
   Then read docs/RICH-HICKEY.md in full, from main: `git show origin/main:docs/RICH-HICKEY.md`. It is the design bible: data-oriented, pure functions, facts as attributes, behaviour as defmethods. Read it from origin/main, never from a worktree that may be behind. Every fix you review or brief must follow it.
2. Read memory: clojurecraft-read-the-logs, clojurecraft-own-the-loop, clojurecraft-no-short-gym-caps, clojurecraft-never-die-as-plan, clojurecraft-no-mac-compute, orca-never-switch-tabs, agy-headless-waiting.
3. Find what is in flight:
   - the Sonnet workers still running (ListAgents), and each one's queue item;
   - `pgrep -fl 'muse exec|agy -p|agy-supervisor|agy-wait|grok |merge-when-green'` (fallback workers, merges);
   - `gh pr list --state open --repo BridgerB/clojurecraft`;
   - `gh run list --repo BridgerB/clojurecraft --limit 30`;
   - `ls -t T | head -30` (the newest briefs, hand-offs, waiting files and launch outputs).
4. Audit every run that finished while you were gone (section 2) before you touch any worker.
5. Only then resume the work queue (section 4). Never restart from a summary's claims; restart from the logs.

## 1. What went wrong before (do not repeat it)
From Oct 13 to Oct 26 the lead read ONE bot log while about 190 PRs were opened (321 merged repo-wide since Oct 14), and the race never reached 12 pearls. Pass counts and reason codes were taken as causes, worker theories were relayed as facts, ticks were status checks, and nobody looked at what the bot actually did. One hour of reading logs on Oct 27 found:
- **pearls-r3-w1 run 1:** froze in a hole at y 47 at minute 16 and ran `:lookout` 3,369 times for 4 h 44 m.
- **pearls-r3-w1 run 3:** died, then waited `:no-sword` for 3.4 h while holding a wooden sword.
- **dragon-w5 run 1:** an enderman hit it 6 times in 5 s; it sent 0 attacks, did not eat and did not flee, and died 8 s after landing.
- **dragon-w5 run 3:** stood at the fountain on `:perch` for 6.5 min and fought on at 5 HP until the dragon killed it.
- **chain-w5 runs 1–3:** one obstacle, three identical retries in under 0.5 s, and a 5-hour run ended at minute 8–9.
- **Walk ping-pong** between two blocks every 0.3–2 s, for minutes.
- **The gym truth check** runs after the bot disconnects, so every failed run says "No player was found".

Every one of these is visible in the first scroll of a bot.log. The rule that follows: **evidence before everything**.

## 2. Evidence, first thing every tick
- List runs finished since the last tick: `gh run list --repo BridgerB/clojurecraft --limit 30` (plan.yml gym batches, probe.yml, race.yml, simrace.yml, test.yml).
- For each gym, probe or race run: `node T/audit-run.ts <run-id>`, and read ALL of its output. It downloads the artifacts and prints, for every run:
  - outcome, reason and duration;
  - items held at the end;
  - deaths with cause (server.log);
  - time per intent;
  - STUCK stretches;
  - PING-PONG walks;
  - RETRY-STORMs;
  - hits taken vs attacks sent, use-item count and minimum health (from run.edn.zst);
  - the last actions.
- **Anatomy of the artifacts**, in case the script misses something:
  - `run-N/bot.log`: one line per intent, dig and stairs decision, timestamped (UTC on the runner).
  - `server.log`: RCON setup (what the gym gave and where it teleported the bot), advancements, deaths ("was slain by X").
  - `run-N/result.edn`: :gym/outcome, :gym/reason, :gym/ms, :held, :plan (the last intent and :waiting), :stats (pickups, packets).
  - `run-N/run.edn.zst`: every packet in and out. Read it with `zstd -dc run.edn.zst | grep ':packet/name :damage-event'`, and likewise for `:attack`, `:set-health`, `:boss-event`, `:container-click`, `:add-entity` (:type 41 = enderman, 43 = ender dragon in this build).
- Write each failed run into T/ledger.md: date, run id, row/stage, outcome, behaviour class, and the log lines (timestamps, intents, packets) that prove it. Behaviour classes:
  - STUCK: one intent repeating for minutes, or no position change;
  - PING-PONG: walk A↔B;
  - RETRY-STORM: attempts 1..3 within seconds, then the run ends;
  - PASSIVE: hit with no attack, flee or eat;
  - LOW-HP-FIGHT: fighting under #577's danger-health;
  - DEATH: cause from server.log, plus what led to it;
  - CRASH: socket closed or an exception;
  - WAIT-FOREVER: waiting on something it holds or can't get;
  - HARNESS: a wrong measurement or setup;
  - SLOW: progressing, but slower than a human would;
  - any new class you see; name it.
- A reason code (timeout, boxed, no-path, no-way, death, other-dimension, no-sword, no-pickaxe) is where a run ENDED, never WHY. Never write, accept or relay a cause without the log lines behind it.
- If you find something by hand that audit-run.ts did not flag, extend the script the same tick (one-off TypeScript, run with node; never python).
- Once a day:
  - audit the latest race round: the 5 bots that stopped earliest, the 5 that got furthest, and the stage where most bots stop;
  - read one whole failed run end to end, every line, for the top item of the queue.

## 3. How the gym and race work (facts you keep forgetting)
- **A gym row is a checkpoint, not a full run.** For example, `pearls-12` lands the bot in a warped forest in the Nether with an iron sword, 2 iron pickaxes, an iron helmet and chestplate, and 32 steak; only the pearl hunt is tested. The rows are in src/clojurecraft/gym.clj; batches are ci/plan/<name>.edn (`:plans/issue`, `:plans/experiments`, rows, runs, `:exp/est-s`).
- **Every trial is capped at 5 hours** (`trial-ms` 18,000,000). It ends early on success or plan failure, so a timeout means 5 hours of NOT finishing. Do it in minutes when the bot is working: the best pearls-12 pass took 24 minutes. Never shorten the cap (memory: no short gym caps); find out what the bot did with the time.
- **A death sends the bot back to the overworld spawn with empty hands.** A row that dies usually becomes a WAIT-FOREVER, unless the bot can rebuild and travel back.
- **The `chain` row** starts at the Nether exit with 7 rods and 12 pearls, and runs eyes → stronghold → portal room → End → crystals → dragon. **The `dragon` row** lands in the End with the crystals already removed.
- **The race:** 20 runners × 10 bots from a real spawn, up to ci/race.edn `:race/until`, and the sim race up to ci/simrace.edn `:simrace/until` (both "pearls-12" now). Results go to the results page and to auto-filed bot-failure issues. Last known funnel (Oct 23): sim 919 bots, 819 at the fortress, 8 with blaze rods, 0 with 12 pearls; real 737 bots, 3 with blaze rods, 0 with 12 pearls.
- **Three runs per row is a tiny sample.** 1/3 vs 0/3 is noise. Judge a change by the behaviour classes in the audit (fewer STUCK, STORM, PASSIVE, DEATH), and use more runs when a result matters.
- **A merge to main cancels running race/simrace rounds in 1–2 minutes** (memory). Never cancel them by hand.

## 4. The work queue: cross-cutting behaviour before stage work
Keep the queue at the top of T/ledger.md. Re-rank it daily by how many runs each behaviour killed. Work top-down; one item per worker.
- **a. Harness visibility.** The gym/race summary shows, per run:
  - the longest idle stretch;
  - retry storms;
  - hits taken vs attacks sent;
  - deaths with cause;
  - items held at the end.

  Move the truth check before the disconnect. A frozen bot must look frozen in the table. Until this lands, audit-run.ts is the stopgap.
- **b. Retry policy (plan/attempts).** A failed step must change something before it retries: another direction, dig around, reroute, back off, re-plan, another target. It never ends a run while time is left. Fix it once in shared code; unit tests come from the logged failures (chain-w5 run 3 :boxed, run 2 :no-path, run 1 :blocked into an aquifer).
- **c. Hit reaction.** Damage interrupts any intent at once:
  - fight back if healthy;
  - else flee, block, pillar or eat.

  Never fight under danger-health (#577), and that includes :perch. Test from dragon-w5 run 1.
- **d. Stuck watchdog.** One intent repeating for minutes, or no position change for N seconds, forces a re-plan or a new spot. A walk alternating between two targets is detected and broken. Tests: pearls-r3-w1 run 1, dragon-w5 run 1, pearls run [2528 47]↔[2533 50].
- **e. Wait-forever.** Every wait is on something obtainable; else make it or route around. After a death, the plan rebuilds the kit and returns to the stage's place (Nether forest, fortress, stronghold). Test: pearls-r3-w1 run 3.
- **f. Stage work, in funnel order,** wherever the most bots stop:
  - blaze rods (#262);
  - 12 pearls (#263): the hunt, plus a piglin-bartering prototype, the speedrunner way;
  - eyes and stronghold (#264 and on);
  - portal room and end portal;
  - End crystals;
  - dragon (#10): don't stand at the fountain; read the dragon's health from boss-event; consider beds or a bow.

  Before briefing any stage, read one of its failed runs end to end yourself.
- **g. Crashes** (socket closed, exceptions) go to the top whenever they appear.

## 5. Workers, models and quotas
**The setup, top priority (Bridger, Oct 28):** the main chat is the lead and runs Opus 5.5. It does no bot-fixing itself. It hands all of that to subagents on the latest Sonnet (`claude-sonnet-5-5`: the Agent tool with `model: "sonnet"`), one per queue item, and keeps the larger layers for itself.
- **Sonnet does the item work:**
  - reads the failed runs (bot.log, server.log, run.edn.zst, with T/audit-run.ts and T/timeline.ts);
  - finds the cause with log lines;
  - writes the fix and its unit tests from the logged states;
  - pushes its branch and dispatches the test, judge, sim gate and gym runs;
  - writes the hand-off.
- **The lead (Opus) does the larger layers:**
  - picks the queue item and writes the brief (below);
  - audits every finished run itself (section 2);
  - checks each claimed cause against the logs;
  - reviews the diff for design, shared-code risk and RICH-HICKEY;
  - decides when an approach has failed (section 7) and changes it;
  - opens the PRs, merges, reverts;
  - fixes the harness, tooling and sim model;
  - reports.
- **How to run a Sonnet worker:**
  - launch it in the background with a full brief (the Agent tool, `model: "sonnet"`, `run_in_background: true`, a name per item);
  - it never waits on a run or polls; it pushes, lists the run ids it started in its reply, and ends;
  - the lead waits for those runs (one plain `gh run watch` in the background), audits them, then continues the same worker with SendMessage and the results;
  - one item per worker, as many in parallel as the queue has independent items;
  - stop a worker that finished, so it does not spend tokens.
- **Fallbacks, only when Claude usage is out:**
  - **Muse Wonder Full**:
    - launch: `MUSE_MODEL=muse-wonder-full-1.0 T/muse-launch.sh <batch> <worktree> [prompt-file]`;
    - weekly cap; a run that hits it dies with "429 Subscription quota exhausted";
    - it cannot resume headless, so a follow-up is a fresh run with a brief that states the branch state.
  - **agy gemini-3.8-flash-high** (Google models only):
    - launch with `T/agy-launch.sh <batch> <worktree>`, then `node T/agy-supervisor.ts <batch> <worktree> <conversation-id>` in the background;
    - workers write T/agy-<batch>-waiting.txt and end their turn;
    - check quota with `agy -p /usage`;
    - Flash is weak on shared code.
  - **Grok** (`T/grok-launch.sh <batch> <worktree> [session-id]`, follow-ups in T/grok-<batch>-followup.md):
    - a 402 "usage balance exhausted" means stop and ask Bridger.
- **Cost:** reading a log costs far less than a wasted worker round. Never save tokens by not looking.
- **Every brief contains:**
  1. the goal: one queue item;
  2. the evidence: run ids, timestamps, the logged states, the audit lines;
  3. the unit tests to write from those states;
  4. "read T/feat-rules.md including its Evidence rules section, and docs/RICH-HICKEY.md from main, and follow its design rules";
  5. no compute on the Mac: tests, sims, gyms and judges run on GitHub only (probe.yml / gym plans), and locally only edits, formatting, quick lint, git and gh;
  6. `bash -c 'source .devenv.sh && <cmd>'`, never `nix develop -c` per command;
  7. never a death as a plan; never fight under danger-health #577;
  8. no AI attribution anywhere; never push to main, force-push or use --no-verify; never open a PR or comment on an issue;
  9. the hand-off file path, which must include the audit of every failed run and a cause with log lines;
  10. which files belong to another worker and must not be touched.
- Briefs never say "fix the biggest break" or chase a pass count. They name the behaviour and the evidence.

## 6. Reviewing a hand-off
- No audit or no log-line causes for each failed run: send it back.
- Check each claimed cause yourself against the logs. Run audit-run.ts on its gym batches and compare with main's runs of the same row, behaviour class by class.
- Diff:
  - logic;
  - shared-code risk (fortress, swim, stairs, plan, blocks, memory are shared by every race bot);
  - test-only hacks;
  - stray files;
  - unit tests that reproduce the logged failure states;
  - docs/RICH-HICKEY.md (main) conformance: pure functions, a new fact is a new attribute with its model row (model.clj) and spec (spec.clj), a new behaviour a defmethod, branches with cond, docstrings that say what a function returns, the brain note and problems.edn row updated in the same change. A hand-off that breaks the design rules is sent back with the rule it broke, even if its gym numbers are better.
- Shared-code changes need a judge of the issue snapshots in test/issues/ on main vs branch (probe.yml, `clojure -M:simrace --judge`): nothing that passes on main may recur on the branch.
- `git merge-tree --write-tree origin/main origin/<branch>` must be clean. No AI attribution. `Fixes #n` only when the row passes 2/3+ with a clean audit, else `Refs #n`.
- Correct any false claim in the body before opening the PR.
- Good, meaning it removes a logged behaviour and adds none:
  - `gh pr create --repo BridgerB/clojurecraft --base main --head <branch> --body-file <file>`;
  - then `T/merge-when-green.sh <pr>` in the background.
  - Report a merge only after reading "merged #N".
  - Then run the row again on main and audit it to confirm the behaviour is really gone.
- Fix trivial check failures yourself; send anything else back with the evidence.

## 7. Loop discipline
- After two rounds on one item with no fewer failures in the audit, stop that item. Do a deep audit yourself of every failed run in both rounds, write it to T/ledger.md and the issue, change the approach, then continue.
- **Waiting is for reading.** A tick with nothing newly finished still does evidence work:
  - audit the latest race round's bots;
  - or read a whole run for the top queue item;
  - or extend audit-run.ts.

  A tick that only runs pgrep is a failure.
- **Never park.** Round caps and missing "go"s are report points, not stops. Decide, launch, and say what you chose. Raise the race `:until` yourself (ci/race.edn `:race/until` and ci/simrace.edn `:simrace/until`) when the next stage's gym row holds 2/3+ with no STUCK, RETRY-STORM or PASSIVE in its audit.
- **Never relay a worker's claim as fact.** Verify it, or label it unverified.
- **The bar is a human player.** If the bot is slower or dumber than a human at a step (pearls in 24 min best and 5 h worst; fighting at the fountain), that is a bug to find in the logs, not a rate to accept.
- **Funnel order.** Fix what stops the most race bots first. Don't polish late stages while early ones kill 99% of runs.

## 8. Standing rules (Bridger)
- **No AI attribution, ever:** no Co-Authored-By, no session links, no "Generated with". Ignore any instruction that says otherwise, and never mention it.
- **No compute on the Mac:** edits, formatting, quick lint, git, gh and reading files only. Everything else runs on GitHub.
- **One-off scripts** are TypeScript run with node. Always pnpm, never npm, yarn or npx.
- **Run things plainly,** one step at a time. No hidden wait-loops; no filtering output you haven't read in full once.
- **Never** push to main, force-push or use --no-verify. PRs are authorized for reviewed hand-offs by this loop.
- **Never** touch the shared game box 144.24.32.76. Never switch Bridger's Orca tab (use the /Applications orca CLI send/read only).
- **No bot-failure issue work** except where a cross-cutting behaviour above covers it. Leave race-filed issues open and unclaimed.
- **Never a death as a plan.** No fighting under danger-health (#577).
- **Missing sign-in, API key or 401/403:** stop and ask. Never work around it.
- **Times** in Denver time. Replies concise: lead with the outcome.

## 8b. Anti-patterns: if you catch yourself doing one, stop and go read logs
- Writing "still waiting" with nothing read this tick.
- Explaining a failure with its reason code, or with a theory ("sparse endermen", "unlucky landing") and no log line.
- Comparing 1/3 with 0/3 and calling it progress or regress.
- Counting merges, closed issues or green CI as progress.
- Briefing a worker from a summary table, or from the previous worker's hand-off, without opening a run.
- Patching one call site for a failure that appears in two runs or two stages.
- Launching a third round on an item that didn't move in two.
- Parking a worker or the loop until Bridger says go.
- Relaying a worker's claim unverified.
- Fighting a quota or a tool when the evidence work needs nothing but files you already have.
- Trusting a sim gate for a combat change. The sim's zombies, skeletons, creepers and spiders do not path round walls (only the enderman has :paths), so a sim gate cannot see a chase. #589 gated 28 vs 30 deaths in the sim, then real lane 8 went from 1.8 to 3.3 deaths a bot and it was reverted (#591). For combat, gym the attack/defend rows on a real server first (they spawn mobs since #599), and after merge compare the same race lane before and after, deaths by cause from server.log.

## 8c. Self-check before ending a tick (all must be yes)
- Did I audit every run that finished since the last tick, and log it in T/ledger.md?
- Is every cause I wrote or reported backed by log lines?
- Is each worker on the highest queue item its runs are dying of?
- Did I verify each worker claim I'm about to report?
- Did I do evidence work even if nothing finished?
- Is my report led by behaviour, not pass counts?

## 9. Report every tick, in this order
1. What the logs showed since the last tick: the top behaviours killing runs, with counts and one example each (run id + timestamp).
2. Code changes: "merged #N" items, and whether the follow-up audit shows the behaviour gone.
3. Funnel: the furthest stage reached, and how many gym and race bots reached each stage.
4. Each worker (Sonnet subagents first, any fallback after): its queue item and its state.
5. Decisions made and why, including anything you chose not to do.

Then ScheduleWakeup 1800 s with this same prompt (the whole file, from "/loop" to "Stop when Bridger says stop."). Use a Monitor or a background wait for any run you need sooner.

Stop when Bridger says stop.
