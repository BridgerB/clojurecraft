# AGENTS.md: keeping the bot-failure issues down

The entry to the manual for fixing the bot's failures, for any agent or person. The rounds on GitHub's runners play the bot without end and file every way it fails, with a snapshot that reproduces it, as an issue labelled `bot-failure`. The orchestrator gives you one (section 3); you find out why the bot stopped, change the bot so it does not, prove it with the issue's evidence, and get it merged. Then you stop.

This page is short on purpose: agents load it into every turn. The rest of the manual is under `docs/fixing/`, split by topic; read only the section a question needs, once, and never this page again.

## 0. Before the first issue

Once per session, before the first issue. A fix in the wrong style is a wrong fix here even when the tests pass.

1. **`docs/RICH-HICKEY.md`, in full.** The design brief. Every review asks first: does the fix follow it? Section 25 says what it means in this code.
2. **`CLAUDE.md`, in full.** Style rules, one line per namespace, the commands, the workflows, the project words.
3. **The brain, in part.** `brain/_index.md`, `brain/rules.md`, then `brain/bot/gotchas/` and `brain/bot/decisions/` (traps already paid for, choices already made). The rest on demand: `grep -rl "<namespace-or-function>" brain/` before touching a namespace. The brain holds decisions, gotchas and game facts; what a function does is its docstring.
4. **The code you change, and what it calls.** Read the namespace you will edit and the registries it plugs into (`plan.clj` for goals and intents, `intent.clj` for executors, `model.clj` for attributes). Add `sim.clj` and the `sim/` part that owns the rule when a fix touches the server model (`sim/world`, `protocol`, `window`, `portal`, `mobs`, `spawns`, `end`). The pearl hunt is rows in `resources/clojurecraft/pearls-rules.edn` read by `pearls/rules` over the facts of `pearls/facts`; what a bot does about a hostile is `threats.edn` and `threat.clj`; what it has learned not to retry is `avoided.clj`; where a race starts is `starts.edn` and `ci/lanes.edn`. Do not read the rest in advance.

Then this file, Part I in full.

---

## 1. Hard rules

Absolute. The reason is not an invitation to argue an exception.

1. **No attribution, anywhere, ever.** No `Co-Authored-By`, no "Generated with", no agent, model, assistant, tool or provider name, no session links, in commits, PR titles and bodies, comments, branch names, code, docs and test names. The repository reads as one author's. The commit-msg hook (`scripts/check-commit-msg`) refuses some of it; the rule is wider. Reread every message and body before `git commit` and `gh pr create`; turn off or strip any trailer your tool adds.
2. **No claims in comments.** Never comment that you took, are on, or are working on something. The owner naming the issue is the whole claim (section 3). The one comment you write on an issue is a hand-off (section 14).
3. **No compute on the local machine.** It is the owner's laptop. Locally only: edit files, `git`, `gh`, read files and downloaded artifacts, `bb fmt`, `bb check`, `bb probe`, the commit hooks. Never a test (not one var), a snapshot judged or played, a replay, a probe or scratch script that loads the bot (`clojure -M:dev|test|simrace|replay|run`, `bb test|ci|replay|gym`), a sim, gym, race or Minecraft server of any length. Code runs only in the branch's `test.yml` run, the PR's checks, or `probe.yml` (section 8).
4. **Never touch the shared game box** `144.24.32.76`: no SSH, no RCON, no connection. It belongs to other projects; one reset destroys their work. The runners start their own servers.
5. **main is protected.** Never push to `main`, force-push a branch someone else pushed, `--admin` merge, bypass or disable a required check, or weaken a test or the issue check to go green. Concurrent agents merge all day and every merge restarts the rounds.
6. **Never merge what does not fix what it says.** `Fixes #n` only when the evidence shows the issue's reproductions no longer stop with that signature; otherwise `Refs #n` and say why. A false close hides a live failure until the rounds reopen it.
7. **Never `--no-verify`.** Fix what the hook says (section 11).
8. **Never cancel a run you did not start**, and never cancel a round: a merge cancels the running round on its own within a minute or two.

---

## 3. Who works which issue

The owner names the issue; nobody picks one for themselves. The owner's naming it is the whole claim. Never #10 (the roadmap), never an issue without `bot-failure`. A partial fix (some snapshots still recur) lands as `Refs #<n>` from `fix/<n>`.


---

## 4. The loop

The same way every time. Work in your own clone or worktree, one per task (`git worktree add ../clojurecraft-<n> origin/main`), never in another agent's.

1. **Read.** Section 0 if not done this session; reread sections 15 and 16 at the start of each session.
2. **Your issue.** The one the owner named. Nothing is chosen here.
3. **Same root cause.** If another open bot-failure issue clearly shares your issue's cause (the same reason family across stages, or the same stage with a symptom of the same thing), fix it in the same change and give it its own `Fixes #<m>` line. When unsure, do not. One root cause, one PR.
4. **Branch.** `fix/<n>` from `origin/main` (`<n>` the lowest number fixed; `fix/<n>-r2` when `fix/<n>` is spent by an earlier merged fix). The pull request body's first line is `Fixes #<n>`; `issue.yml` reads the issues from it.
5. **Work the fix.** Sections 6 to 10: the issue and every comment, the evidence, probes, the cause, the fix with its test, its brain note and its `problems.edn` sentence.
6. **Check locally.** `bb fmt`, then `bb check` (format, lint, idioms, ns forms, unused vars, brain). Nothing else (rule 3).
7. **Commit and push** to your branch (section 11). Every push runs `test.yml`: the unit suite, one forest shard, the workflow lint. That is your test run. It does not judge the issue snapshots (their worlds are not restored there).
8. **Watch without blocking.** Find the run for your head and look at it between other work:

   ```sh
   gh run list --workflow test.yml --branch fix/<n> --limit 3 --json databaseId,headSha,status,conclusion
   gh run view <id> --json status,conclusion
   gh run view <id> --log-failed          # when red: read it in full, then fix and push again
   ```

   Never sit in a sleep loop, never end a turn only because a run is going, never rerun anything locally to confirm.
9. **Judge the snapshots** before the PR, the way the check will: `bb issue-check fetch <n>` (into `cache/issues/<n>/`, never committed), then `bb probe run <n>-judge 'clojure -M:simrace --judge cache/issues/<n>/R4-35-2.edn.gz,cache/issues/<n>/R1-7-1.edn.gz --expect pass' R1,R4` from the pushed fix branch (seconds a snapshot), and the same with `--expect recur` from `main` (each moment must reproduce its failure there, or it proves nothing: take another).
10. **Open the PR when ready** (section 12): fix and test pushed, `test.yml` green, the before/after of each snapshot in hand. Never a draft.
11. **Merge when green** (section 13).
12. **Stopping before the merge,** for any reason: hand off (section 14).
13. **Stop.** One issue a session.

---

## Where the rest is

Section numbers are kept everywhere, so "AGENTS.md section 8.4" is section 8.4 of the file that holds it:

- [`docs/fixing/issues.md`](docs/fixing/issues.md): The bot and how its failures become issues (section 2)
- [`docs/fixing/loop.md`](docs/fixing/loop.md): The loop in full: decisions, reading an issue, evidence, probes, the cause, the fix, committing, the pull request, merging, hand-off (sections 5-14)
- [`docs/fixing/traps.md`](docs/fixing/traps.md): Context traps, catches, failures seen with a worked example, and when you are stuck (sections 15-18)
- [`docs/fixing/world.md`](docs/fixing/world.md): Reference: world, protocol, game, physics, windows, mechanics, the server model, gotchas (section 20)
- [`docs/fixing/ci.md`](docs/fixing/ci.md): Reference: tests you cannot run, the CI map, Hickey patterns (sections 23-25)
