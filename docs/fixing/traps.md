# Context traps, catches, failures seen with a worked example, and when you are stuck (sections 15-18)

Part of the bot-failure manual; [AGENTS.md](../../AGENTS.md) is its entry and index. Read only the section a question needs.

## 15. Context traps

A failure that will not reproduce is almost always a context difference, not flakiness. The sim is deterministic by seed in the same context.

- **CI's context is not a scoped probe's.** `test.yml`'s forest job runs `clojure -M:test -v <var>` with no `-n`, so every test namespace loads and `fixtures/instrument!` instruments the reducers' fdefs; a probe with `-n`, or a script, runs uninstrumented and can pass where CI fails. Reproduce a red job with the job's exact command (read it from `.github/workflows/test.yml`), not a narrower one. A selection fdef (`spec/requires`) that rejects a new key or intent shows up only there.
- **The judge runs uninstrumented on purpose** (`--judge` is not a test: spec checks on `sim/step` walk all 625 columns twice a tick and made the old check take 21 minutes). A failure that only an instrumented run shows belongs to `test.yml`'s suite, not the check.
- **`gh` inside the monorepo resolves to the wrong repository.** In a worktree under `mc/upstream/`, a bare `gh run download` or `gh run view` targets `BridgerB/mc` and 404s. Pass `--repo BridgerB/clojurecraft` (the `bb` tasks do) or work in a standalone clone.
- **A shallow clone breaks merge bases.** Never `git fetch --depth` in your working clone; if it happened, `git fetch --unshallow`.
- **`bb` needs the nix shell.** Run `bb fmt`, `bb check` and `git commit` inside `nix develop` (or `nix develop --command <cmd>`); the hooks need the same tools.
- **Artifacts collide on re-download.** `gh run download` refuses an existing directory's files; `bb probe log` clears `/tmp/probe-<id>` first. Probe runs before `run.log` existed wrote `out/probe.log` from both the workflow and the script.
- **A snapshot fetched is a snapshot kept.** `bb issue-check fetch` takes two at most, one a world, and only snapshots with a kept moment (`an-issue-keeps-two-snapshots-at-most` holds the cap).
- **Reports lag the code.** A comment "at <sha>" older than your merge says nothing about your fix.

---

## 16. Catches

1. **The lanes never stop.** A code merge has the fleet cancel and replace every lane within a minute or two; `gh run list` lags behind. Docs-only merges do not restart them. A probe or check that waits gets a runner from the fleet. Never cancel a lane by hand.
2. **Every open issue has evidence.** It is filed only with a snapshot that recurred on its batch's commit, so `sim` is on every issue filed since #299 and the check requires its snapshots; `real` says real runs hit it (its snapshots may be real ones, 2.3). An open issue with no fetchable snapshot (artifacts expire in 7 days) is closed by the filer within 7 days of its last reproduction; one filed before #299 with none is closed by hand, with the same words.
3. **Reproduced, not counted.** One run whose snapshot recurs files its signature; a hundred runs with none file nothing (they are on the page and in the report's "not reproduced" lines). A tally from one round after a merge may be that merge's regression.
4. **The moment can still be late** (9.2): a run keeps six minutes. When the cause is far earlier and the moment hopeless, the evidence is a full run from spawn (8.6).
5. **`:timeout` snapshots are stalls.** The moment is the start of the run's last five simulated minutes with no new stage; the check judges whether the stage is reached in the fifteen minutes from it (`stall-judge-ms`; no new stage and no end: it recurs). Fifteen, not five, because a stage can take longer than five by rights (issue #62: with the fix, iron mined and smelted 8 minutes on). A stall that passes on `main` too over fifteen minutes proves nothing: leave it out (#62's R2-46-1). Nothing replays a run from its landing in the check.
6. **The sim race knows its landing.** Sim runs get `:go/at` like real bots; snapshots from before that are played with their captured world's landing (`simrace/moment`). An old snapshot behaving oddly: check `:plan/go-at`.
7. **The captured world has an edge.** 625 chunks; past them every cell is `:unknown` where a real server would load more. The captured Nether has one too: 32 chunks past the farthest landing's Nether point (issue #353). A failure there is the model's limit: say so in the PR.
8. **Difficulty is Normal** (issue #10): night mobs and hunger on every real server and gym; keep_inventory stays on. The sim lanes run the model at Normal too (`simrace/difficulty`): hunger drains, heals and starves by vanilla's rules (#260, `hunger.clj`, `brain/bot/tooling/sim-hunger.md`); and mobs spawn at night and in the dark, walk at the bot, hit, shoot and blast, and die to its `attack` with their loot (`mob.clj`, `brain/bot/tooling/sim-mobs.md`); a run starts at one of eight times of day by its seed.
9. **Server load is real.** 25 bots a server dropped it to 7 or 8 ticks a second (now 15 a server, #252) (`:sample/server-tick`, `server.log`). Anything the bot times by its own clock against something the server times in ticks breaks under load: digs, window answers. A real-only signature with load: look there first.
10. **"The unmodelled packet."** `packet_test` decodes an id with no spec and expects `:unknown`; adding a spec for that packet breaks it: switch its example.
12. **Stages past the Nether are asides for now.** `:blaze-rods`, `:ender-pearls`, `:eyes-of-ender` and the stronghold rows are `:goal/aside? true` (issue #10's children #262-#267): in the goal table, never in a race's chain, so `plan/current-until` stays `fortress` (`plan_test` asserts it). The race's `:until` goes up one stage only when that stage's gym row passes; a bot-failure at those stages comes only from gyms until then.
13. **Combat facts already paid for.** Vanilla wants `attack` then `swing` (swing first made every hit about 1 damage, #259). A fight's swings are 650 ms apart by the clock (`:intent/next-swing`). An enderman never teleports from a melee hit but teleports away when stared at within 4 blocks; the bot fights it from a burrow (#263). Blaze rows pass in the gym only with Fire Resistance: fire handling is an open gap (#262).
14. **World cache keys.** `bb/clojurecraft/server.clj`, `ci/gym.edn`, `worlds.clj`, `landings.clj`, `fixture.clj` and `capture.clj` are hashed into the world and simworld cache keys: a change to any of them makes every lane miss its cache and fail in 20 s until the worlds are rebuilt (#257 did, #275 undid it). That is tooling: never yours in a bot fix.
15. **Give exact numbers.** Run ids, commits, counts, before and after lines. "Seems fixed" is not evidence.

---

## 17. Failures seen, and one worked example

Patterns, not recipes.

1. **`stale-window` at ingots and portal-kit (#131, #132): a loaded server answers late.** Crafts, equips and pours failed on the first click left unanswered for 3 s; smelting already recovered (resync, restart, fail at the third loss). Moved into `window/lost` and `window/resync` for every window intent. Proven with the sim's `:lag-ticks` 65 (3.25 s). They came back with `not-cooked` (#199, #200): a server far behind cooked past the smelt's deadline, the failed smelt left the furnace open, and the server ignored every window-0 swap after it. `smelt/cooking?` waits past the deadline while the furnace cooks; `place/equip` closes an open container before a swap.
2. **`no-water` at water-bucket (#60): water seen with no route.** `scoop/to-bank` cuts stairs or a tunnel toward the nearest water's bank.
3. **`boxed` at ingots (#37): a climb under water.** `stairs/duck` (one stair down toward the soonest dry head, the pocket recorded in `:plan/boxed-at`), `:intent/visited` (revisited level steps last), `stairs/dry-top?` (ice is the lake's top).
4. **`no-station` at portal (#142):** below.
5. **`timeout` at ingots (#62): a causeway over a lake.** Sideways level steps laid floor 26 blocks over water; every finished retreat reset the failure count, so the run paced until 90 minutes. `stairs/over-fluid?`: no floor over water or lava. Proven by a full run from spawn: ingots at 8.5 minutes, none at 25 on main.
6. **`boxed` at water-bucket (#61): the water was overhead.** `scoop/seen-source` (scoop any seen source in reach), `scoop/from-below` (dig through at most two blocks of bed); a boxed surface climb ends, recording its cell.
7. **`wood: no-log` (#57): a hunt that only set out toward known trees.** The same slots failed every round without moving; the planner now waits in ticks (`plan/wait-ticks`) and the hunt walks back toward remembered trees.
8. **`rejected` at ingots, cobblestone, stone-pickaxe (#88, #144, #64): a slow server.** Digs answered with the block standing; the server ran 130 ms a tick. The bot reads the server's tick length from `set-time` spacing (`game/clocked`, `:server/tick-ms`) and stretches a dig's FINISH and confirm window (`intent/slowdown`).
9. **`rejected` at cast-one (#44): a block into the bot's own body.** `build/reachable-stand` refuses a stand whose feet or head is the cell worked; `build/refused-for-the-body?` approaches again.
10. **`inventory-full` at ingots and portal-kit (#172, #192): 36 full slots.** Mining fills the inventory with cobblestone and stone nothing needs; a craft's result had nowhere to go, no answer came, and the resync found no slot. `window/tossable` throws a `junk` stack first.

**Worked example, #142 `[:portal :no-station]`** (PR #160). The issue: 89 runs in 18 rounds, every last intent `nil`. `grep -rn ':no-station' src/` pointed at `build/refill`. The run maps (`gh run download 38664661309 -n simrace-8ddbb1a...-6`) showed seven runs on five landings, each holding the whole portal kit after 25 to 65 minutes. One probe from `main` over three snapshots printed the build intent, the lava near the origin, `build/station` and `build/refill`: in every world one obsidian cast, two scoops in memory, no lava within 12 blocks. Cause: `build/site-ok?` accepted a site when some stand reached some source, so a lone source passed. Fix, cause half: `build/pool` and `build/spent?` refuse a site whose pool is smaller than the cells left. Moment half: a refill with no station at a spent site ends the build so the planner picks a new site. A third finding came free: `choose-site` filtered 30 000 lava sources per candidate (40 s a call), now only the nearby ones (3 s). Test: `build_test/a-lone-lava-source-is-no-site`. Brain note `brain/bot/plan/build-plan.md`, one `problems.edn` sentence, one snapshot under `test/issues/142/`, the before/after table in the body (`[:portal :no-station]` on main, "left the spent site, stairs down toward lava" on the branch). Green in nine minutes, merged.

---

## 18. When you are stuck

- **The probe crashed.** Read `bb probe log <id>`: a typo in an alias, a private function (call its var), a world file missing because `worlds` did not name its set. Section 8.2's shape keeps one crash from costing every answer.
- **The snapshot recurs and the cause is fixed.** The moment (9.2): print what the bot does on the next tick there, and make that failure a re-plan.
- **The snapshot passes but you doubt it.** "Still active at the end of the window" proves little: read its STORY lines, follow it longer (8.4's stepper) or run from spawn (8.6) and report the stage reached.
- **Your fix makes another issue's snapshot recur.** The two rules conflict; read that issue's PR and notes; the right fix keeps both.
- **Real-only and no cause in sight.** Fetch its real snapshots first (section 7) and judge them like any other (8.4). Without one: download the round's race artifact (section 7), read the failed slots' RESULT lines, the server's tick time and the bot's log lines, and replay the recording on a runner at its commit (8.5).
- **A CI job fails and the same thing passes in a probe.** Section 15.
- **The cause is in tooling, or the issue is not a bug** (the model cannot represent it, the world's edge): hand off (section 14) and tell the owner. Never close issues by hand.

---
