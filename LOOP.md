/loop Win Minecraft with clojurecraft (BridgerB/clojurecraft): a bot that goes from a fresh spawn to killing the Ender Dragon, with no human input and no cheats. You are the lead. Workers write most code; you find out what is really wrong, decide, review, merge, and report. All helper files are in /Users/bridger/.claude-personal/jobs/b02a5091/tmp/ (T below). If you have just been compacted or restarted, do section 0 before anything else.

**The goal right now: pass stages 17 and 18 in the race (Bridger, 2026-10-31).**
- Stage 17 is blaze-rods (#262): 7 rods from a fortress spawner. Stage 18 is ender-pearls (#263): 12 pearls, hunted in a warped forest.
- "Pass" means the results page (https://bridgerb.github.io/clojurecraft/) shows bots reaching the stage in BOTH the real race and the sim race, at about 5% of the bots that start it or better.
- Every one of the 20 runners works on 17 and 18. No general bug work, no other stage. When 17 and 18 pass, move to 19 (eyes-of-ender) and on, one stage at a time, the same way.
- The long goal is unchanged: one full path, spawn to dragon, all 25 stages; a 1% win rate is enough.

## 0. Getting going again after a collapse or a restart
1. Read this whole file (`git show origin/main:LOOP.md`), then T/ledger.md (the failure ledger), T/fast-loop.md, T/merge-freeze.md (the runner rules and the refocus), then docs/RICH-HICKEY.md in full from main (`git show origin/main:docs/RICH-HICKEY.md`). Every fix you review or brief must follow it.
2. Read memory: clojurecraft-read-the-logs, clojurecraft-own-the-loop, clojurecraft-no-short-gym-caps, clojurecraft-never-die-as-plan, clojurecraft-no-mac-compute.
3. Find what is in flight: the Sonnet workers (ListAgents) and each one's item; T/status-*.md; `gh pr list --state open --repo BridgerB/clojurecraft`; `gh run list --repo BridgerB/clojurecraft --limit 30`; what holds the runners (section 3).
4. Audit every run that finished while you were gone (section 2) before you touch any worker. Never restart from a summary's claims; restart from the logs.

## 1. What went wrong before (do not repeat it)
- Oct 13–26: about 190 PRs, one bot log read, no 12 pearls. Reason codes were taken as causes and worker theories relayed as facts. Rule: **evidence before everything.**
- Oct 30: the race measured nothing for hours, because merges every 30 minutes restarted the lanes, queued gym jobs starved the fleet down to one lane, and a real bot needs ~42 minutes just to reach the fortress. Rule: **protect the race's runtime** (section 3).
- Oct 30–31: gym kits hid race failures. Race bots reached the fortress with 36 of 36 slots full (14–19 rod drops seen, 0–2 held), with no food, with a pickaxe worn out and no table. Rule: **gym rows must carry what race bots really carry**, and the race is the final measure.

## 2. Evidence, first thing every tick
- For each finished or running gym round: `node T/audit-run.ts <run-id>` (it audits a run still in progress from its finished jobs). Read ALL of it: outcome, held items, deaths with cause, time per intent, STUCK, PING-PONG, RETRY-STORM, hits vs attacks, the last actions.
- For blaze rounds also: kills, rod drops, rods picked up, rods held, hp lost per kill (scratchpad blazekills.ts, blaze-rods' s2k.ts). For pearl rounds: pearls held at 30/60/90/120 minutes, longest gap, deaths by cause.
- For the race: per commit, from gh-pages data/batches/<UTC date>.ndjson (T/batches.ts): bots reaching fortress, blaze-rods, ender-pearls, real and sim, failures by signature. When a lane ends, read its artifacts: free slots and food at the fortress, rods dropped vs picked up, pearls, deaths by cause.
- Artifacts: run-N/bot.log (intents, digs, timestamps in UTC), server.log (setup and deaths), result.edn (outcome, held, plan), run.edn.zst (every packet: damage-event, attack, set-health, container-set-slot, add-entity, entity-event).
- Write each failed run into T/ledger.md with its behaviour class (STUCK, PING-PONG, RETRY-STORM, PASSIVE, FLIP-FLOP, LOW-HP-FIGHT, DEATH, CRASH, WAIT-FOREVER, HARNESS, SLOW, or a new one) and the log lines that prove it. A reason code is where a run ended, never why.
- Extend audit-run.ts the same tick when you find something it misses (TypeScript, node).

## 3. Runners: all 20 on stages 17 and 18
- **The races start at the fortress.** Both races (race.yml real, simrace.yml sim) start each bot at its landing's fortress with a race-faithful kit and play to pearls-12, so every race bot plays only 17 and 18 (`:race/from` / `:simrace/from "fortress"`; chain-dragon builds the real side, stuck-retry the sim side). Until that lands, they start at spawn. No issues are filed for stages before 17.
- **Fleet:** ci/fleet.edn keeps at least 6 real lanes (#635); the fleet dispatches lanes only while no other job waits, so the gym must not keep a queue.
- **Gym:** one round per worker in flight, 6 jobs a round, on race-faithful rows (blaze-fortress-race; pearls-12-race-tools). Cancel your superseded round yourself (its logs upload) before dispatching the next.
- **Merges:** a lane on replaced code runs 120 minutes before the fleet replaces it, so merges may land as they go green, but batch them when you can. Docs-only merges replace nothing.
- **Nothing else holds a runner.** No Copilot loop (copilot.yml is disabled), no probes or rounds for other stages. Check each tick: `gh run list --status in_progress`, every run must serve 17 or 18.

## 4. What is known (keep current: update this section when it changes)
- **Stage 18:** a gym pass exists. pearl-fight-k job 1 got 12 pearls in 123 minutes on pearls-12-race-tools (#649: pickaxe reserve, relocation after 8 minutes with no pearl, the unreachable-table fix). Open: deaths walking out of a burrow among endermen, a lava fall fleeing an enderman, freezes in self-dug rock when the pickaxe wears out.
- **Stage 17:** no pass yet. Best: 5 rods held (blaze-race-x job 4); hit-and-run kills 7–10 blazes in 11–16 minutes (z4, z5). Open: deaths in the spawner room when sorties pull many blazes; holds and camps that stall for hours; the tunnel loop at landing 5.
- **Race blockers already fixed on main:** pickaxe table kept (#628), spawner kept after a failed walk (#629), tunnel stand flip (#630, #637), return without food (#631, #640), the defence hold (#632), retry regroup (#633, #646), junk tossed for a rod (#645), 4 free slots (#648), the stuck watchdog (#647, #650, #651). Food at Nether entry is still low (#641 didn't work: the portal is underground; a longer surface search is in test).
- **The sim race** has rarely given rods (3 in 946 at the fortress before Oct 30): check the model's blazes and spawners against the real race before trusting a sim number.

## 5. Workers
- The lead (Opus, main chat) does no bot-fixing. Sonnet subagents do the item work: read runs, find the cause with log lines, write the fix and unit tests from logged states, push, dispatch rounds, write status lines (T/status-<name>.md) and PR bodies (T/pr-<name>.md). They own their loop per T/fast-loop.md: audit partial rounds, fix, push and dispatch the next round in the same session.
- The lead: picks items, writes briefs, audits every round itself, verifies claims against logs, reviews diffs (design, shared-code risk, RICH-HICKEY), opens PRs and merges with T/merge-when-green.sh, resolves overlaps between workers (one owner per file area), reports.
- Every item serves 17 or 18. Current owners: blaze-rods (stage 17 fight, pickup, holds), churn-guard (stage 18 hunt), walk-restart (shared watchdog and retry, for 17/18 stalls), chain-dragon (real race fortress start, race audits), stuck-retry (sim race fortress start; stage 19 is paused).
- Every brief has: the item; the evidence (run ids, timestamps, log lines); the unit tests to write from those states; "read docs/RICH-HICKEY.md from main"; no compute on the Mac; never a death as a plan, never fight under danger-health (#577); no AI attribution, never push to main, force-push or --no-verify; which files belong to another worker.

## 6. Reviewing and merging
- No log-line cause, no merge. Check each claimed cause against the logs yourself.
- Diff: logic, shared-code risk (plan, combat, fortress, stairs, path, memory are shared), test-only hacks, design rules (a new fact is a new attribute with its model row and spec; a new behaviour a defmethod; cond; docstrings; brain note and problems.edn updated).
- A test that is loosened must say why in the commit, with the cause; never accept "couldn't make it pass".
- Changes that run in the sim's chained runs need the whole test.yml green, the forests shard included (merge-when-green only checks unit).
- `git merge-tree --write-tree origin/main origin/<branch>` clean; then `gh pr create ... --body-file`, then `T/merge-when-green.sh <pr>` in the background. Report a merge only after reading "merged #N". Then measure it in the race.

## 7. Loop discipline
- Two rounds on one item with no improvement in the audit: stop, deep-audit both rounds yourself, change the approach.
- Kill what doesn't serve 17/18. A runner idle or on other work is a failure.
- Never park; never relay a claim unverified; the bar is a human player.
- Raise the race :until (ci/race.edn :race/until, ci/simrace.edn :simrace/until) to eyes-of-ender as soon as stage 18 passes in the race.

## 8. Standing rules (Bridger)
- No AI attribution, ever. No compute on the Mac. TypeScript one-off scripts with node; pnpm. Run things plainly. Never push to main, force-push or --no-verify. Never touch the game box 144.24.32.76. Never switch Bridger's Orca tab. Never a death as a plan; never fight under danger-health (#577). Missing sign-in or 401/403: stop and ask. Times in Denver; replies concise, outcome first.

## 9. Report every tick, in this order
1. Stage 17 and 18 numbers: gym (rods held, passes; pearls at 30/60/90/120, passes) and race (bots at fortress, rods, pearls, real and sim, per commit).
2. What the logs showed: the top behaviours stopping 17 and 18, with one run id + timestamp each.
3. Merges ("merged #N") and what the race shows after them.
4. Runner use: what holds each of the 20.
5. Each worker's item and state; decisions and why.

Then ScheduleWakeup with this prompt. Stop when Bridger says stop.
