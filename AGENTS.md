# AGENTS.md: keeping the bot-failure issues down

The working manual for fixing the bot's failures, for any agent or person who picks the work up cold. The rounds on GitHub's runners play the bot without end and file every way it fails as an issue labelled `bot-failure`. You take the oldest one off the board, find out why the bot stopped, change the bot so it does not, prove it with the evidence the issue carries, and merge. Then the next one.

Part I (sections 0 to 18) is how to work: read it every session. Part II (19 to 25) is the reference: consult it for the subsystem you are fixing. When the code and this file disagree, the code is right; fix this file in the same PR.

## Contents

0. Before the first issue · 1. Hard rules · 2. The bot and how its failures become issues · 3. The board · 4. The loop · 5. Decisions already made · 6. Reading an issue · 7. Getting the evidence · 8. Probes · 9. Finding the cause · 10. Writing the fix · 11. Committing · 12. The pull request · 13. Merging and after · 14. Handing off · 15. Context traps · 16. Catches · 17. Failures seen, and one worked example · 18. When you are stuck

Part II: 19. Planner and intents · 20. World, protocol, game · 21. Stage chain · 22. Failure reasons · 23. Tests you cannot run · 24. CI map · 25. Hickey patterns

---

## 0. Before the first issue

Once per session, before the board. A fix in the wrong style is a wrong fix here even when the tests pass.

1. **The tree.** `tree -a --dirsfirst -I '.git|.cpcache|.clj-kondo|.lsp|.nix|.direnv|node_modules|cache|data|out|target|issues|recordings|churn-batches'` (without `tree`: `nix shell nixpkgs#tree -c tree ...`). About 360 lines; read it all. `src/clojurecraft/` is the bot, `test/clojurecraft/` its tests, `brain/` the notes, `resources/clojurecraft/` the goal table, problem statements and generated tables, `bb/clojurecraft/` and `.github/workflows/` the tooling (not yours to change).
2. **`docs/RICH-HICKEY.md`, in full.** The design brief and the most important document here. Every review asks first: does the fix follow it? Section 25 says what it means in this code.
3. **`CLAUDE.md`, in full.** Style rules, one line per namespace, the commands, the workflows, the project's words.
4. **The brain, in part.** `brain/` is about 150 verified notes pinned to the source. Read `brain/_index.md`, `brain/rules.md`, `brain/bot/_moc.md`, then every note under `brain/bot/plan/` (the planner and every intent: most fixes land here), `brain/bot/gotchas/` (traps already paid for) and `brain/bot/tooling/` (how the evidence you will read is made). The rest (`concepts/`, `decisions/`, `world/`, `protocol/`, `runtime/`, `crafting/`, `brain/game/`, `brain/siblings/`) on demand: before touching a namespace, read its note (`grep -rl '<namespace-or-function>' brain/`).
5. **Ten source files, in full, top to bottom:** `game.clj` (the protocol reducer, `game/step`, `on-packet`), `plan.clj` (the planner, the goal table, `done-by`/`act`/`next-intent`, waits and attempts), `intent.clj` (intents run a tick at a time, walk/dig/collect, children), `model.clj` (every attribute), `memory.clj` (facts with time, Datalog), `terrain.clj` (`block-at`, `solid-fn`), `physics.clj`, `blocks.clj` (`solid?`, `log?`, `name-of`), `stairs.clj` (an executor of child digs: the kind most fixes change), `build.clj` (a build plan as data). Add `sim.clj` when a fix touches the server model.

Then this file, Part I in full, and Part II's sections 19 to 22 once.

---

## 1. Hard rules

Absolute. The reason is not an invitation to argue an exception.

1. **No attribution, anywhere, ever.** No `Co-Authored-By`, no "Generated with", no agent, model, assistant, tool or provider name, no session links, in commits, PR titles and bodies, comments, branch names, code, docs and test names. The repository reads as one author's. The commit-msg hook (`scripts/check-commit-msg`) refuses some of it; the rule is wider. Reread every message and body before `git commit` and `gh pr create`; turn off or strip any trailer your tool adds.
2. **No claims in comments.** Never comment that you took, are on, or are working on something. The board status and the branch are the whole claim (section 3). The one comment you write on an issue is a hand-off (section 14).
3. **No compute on the local machine.** It is the owner's laptop. Locally only: edit files, `git`, `gh`, read files and downloaded artifacts, `bb fmt`, `bb check`, `bb board`, `bb probe`, the commit hooks. Never a test (not one var), a snapshot judged or played, a replay, a probe or scratch script that loads the bot (`clojure -M:dev|test|simrace|replay|run`, `bb test|ci|replay|gym`), a sim, gym, race or Minecraft server of any length. Code runs only in the branch's `test.yml` run, the PR's checks, or `probe.yml` (section 8).
4. **Never touch the shared game box** `144.24.32.76`: no SSH, no RCON, no connection. It belongs to other projects; one reset destroys their work. The runners start their own servers.
5. **main is protected.** Never push to `main`, force-push a branch someone else pushed, `--admin` merge, bypass or disable a required check, or weaken a test or the issue check to go green. Concurrent agents merge all day and every merge restarts the rounds.
6. **Never merge what does not fix what it says.** `Fixes #n` only when the evidence shows the issue's reproductions no longer stop with that signature; otherwise `Refs #n` and say why. A false close hides a live failure until the rounds reopen it.
7. **Never `--no-verify`.** Fix what the hook says (section 11).
8. **Never cancel a run you did not start**, and never cancel a round: a merge cancels the running round on its own within a minute or two.

---

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

- `race.yml`, the real race: up to eight lanes (a lane is one run, one runner), each with 26.1.2 servers on a pregenerated landing set (R1 to R4), 25 bot slots a server, a slot refilled the moment its bot ends. Servers lag under that load (15.4, 17.1).
- `simrace.yml`, the sim race: up to eight lanes playing the bot against the model in captured worlds (`cache/sim-worlds/R1..R4/<n>.edn.gz`), seed after seed.
- The fleet (`fleet.yml`, `ci/fleet.edn`) keeps the runners full of lanes and gives one back the moment any other job has waited 90 s for a runner (GitHub has no preemption of its own), never fewer than two lanes a kind; a merge that touches code cancels and replaces every lane, docs-only merges (`brain/`, `docs/`, `*.md`) do not. So a check, a probe or a gym waits about two minutes at most, never hours.
- Every 20 minutes per runner (`bb churn batch`, then `bb failures report`), the runs that ended since the last report are filed: one issue per signature (infrastructure stops such as a closed socket are counted, not filed; a signature with fewer than 3 runs in a report is a one-off, not filed). Labels: `bot-failure` plus `sim`, `real` or both. The body holds a hidden marker with the signature and a tally (rounds, runs, first and last seen). Each report adds one comment: run count, last intents, landings, up to five reproductions, the run link.
- A closed issue whose signature comes back in runs of code committed after it closed is reopened, its comment saying "(regressed: this issue was closed)". Runs of older code only comment.
- Results also go to https://bridgerb.github.io/clojurecraft/.

### 2.3 What a reproduction line says

Sim:

```
- snapshot `R4-35-2.edn.gz` in artifact `simrace-<sha>-6` (moment 1:30 before the stop, :intent-failed, judged over 2:30); or `clojure -M:simrace --world cache/sim-worlds/R4/35.edn.gz --seed 2 --seeds 1` at <sha>, landing [2360 3640 103]
```

The snapshot is a moment the run kept from before the choice that failed (an intent that failed three times: when the first started; a wait: when it began; a `:timeout`: its last five minutes), with everything the next tick depends on and the window it is judged over: a value you can play on (8.4). The artifact holds that runner's `runs.edn` (one run map a line: `:run/signature`, `:pos`, `:held`, `:plan`, `:run/ms`, `:run/moment`), `simrace.log` and `snapshots/`. The command reruns the whole run from spawn (up to 330 simulated minutes, #252).

Real:

```
- real race: bot Clj_r08, replay its recording with `clojure -M:replay <artifact>/Clj_r08.edn`
```

The artifact `race-<sha>-<runner>` holds `s1/` and `s2/` (one per server), each with `race.log`, `server.log`, `samples.edn.zst`, `milestones.edn.zst` and one recording per slot, `Clj_rNN.edn.zst`. A name is a slot, not a bot: one name holds several bots a round, and a slot that fails round after round means its landing is the case. The runner number is in the comment's heading ("Race 38665691517 runner 1 batch 1 at 0d036cb").

---

## 3. The board

https://github.com/users/BridgerB/projects/10: owner `BridgerB`, number 10, single-select `Status` with `Todo`, `In Progress`, `Done`. The token needs the `project` scope (`gh auth refresh -s project`; without it: `missing required scopes [read:project]`).

```sh
bb board todo                    # the Todo bot-failure issues, oldest first: number, labels, item id
bb board heal                    # make the board true; prints every move
bb board move <n> progress       # todo | progress | done; prints what it reads after
```

`bb board` looks the ids up each run (never hard-code them). `heal` applies four rules: an open bot-failure issue not on the board is added as Todo; an open issue that is Done or unsorted goes to Todo (reopened); a closed issue that is Todo or In Progress goes to Done; an open issue In Progress over six hours with no `fix/<n>` or `fix/<n>-rK` branch on origin goes to Todo (no branch, no claim). It never touches a claim that has a branch.

You may take only: status `Todo`, open, labelled `bot-failure`; never #10 (the roadmap), never an issue without `bot-failure`, never an item `In Progress` unless it is stale.

**Stale claims.** An item with a branch is stale when all three hold: In Progress for over six hours (the Status value's `updatedAt`); its claim branch has no commit in six hours that is not on `main`; no open PR from it was updated in six hours. Any agent may return a stale item without asking: push its branch to `stale/fix/<n>-<YYYYMMDD>`, delete `fix/<n>`, close its PR (`gh pr close <pr>`, no comment), set it to Todo. Never return one that fails any of the three tests: an agent thinking for a long time still pushes.

```sh
gh api graphql -f query='query { user(login: "BridgerB") { projectV2(number: 10) { items(first: 100) { nodes {
  id content { ... on Issue { number } }
  fieldValueByName(name: "Status") { ... on ProjectV2ItemFieldSingleSelectValue { name updatedAt } } } } } } }' \
  --jq '.data.user.projectV2.items.nodes[] | select(.fieldValueByName.name == "In Progress") | [.content.number, .id, .fieldValueByName.updatedAt] | @tsv'
```

---

## 4. The loop

The same way every time. Work in your own clone or worktree, one per task (`git worktree add ../clojurecraft-<n> origin/main`), never in another agent's.

1. **Read.** Section 0 if not done this session; reread sections 15 and 16 at the start of each session.
2. **Open the board.** `bb board heal`, then `bb board todo`.
3. **Choose.** The oldest (lowest-numbered) Todo bot-failure issue: early stages first, so later work lands on stages the bot then reaches. Add any other Todo issue that clearly shares its root cause (the same reason family across stages, as `rejected` at ingots, cobblestone and stone-pickaxe was one slow server; or the same stage with a symptom of the same thing). When unsure, take one. One root cause, one PR.
4. **Claim.** The branch name is fixed by the issue, never chosen: `fix/<n>`, `<n>` the lowest number in your group. Two agents that chose the same issue compute the same name and git lets only one create it: the push is the lock.

   ```sh
   git fetch origin
   git ls-remote --exit-code --heads origin fix/<n> >/dev/null && echo taken
   git switch -c fix/<n> origin/main && git push -u origin fix/<n>    # rejected: someone has it
   bb board move <n> progress
   ```

   When `fix/<n>` exists, look at its PRs (`gh pr list --head fix/<n> --state all --json number,state`): an open PR or none means another agent has it, choose again; only merged or closed PRs mean it regressed and the name is spent, so claim `fix/<n>-r2`, then `-r3`, the first that does not exist. Never a slug in the name: two slugs would both succeed and the lock would be gone. If the board shows the item is no longer Todo after your push, delete your branch and choose again. No comment, assignee or label (rule 2).
5. **Work the fix.** Sections 6 to 10: the issue and every comment, the evidence, probes, the cause, the fix with its test, its brain note and its `problems.edn` sentence.
6. **Check locally.** `bb fmt`, then `bb check` (format, lint, idioms, ns forms, unused vars, brain). Nothing else (rule 3).
7. **Commit and push** to the claim branch (section 11). Every push runs `test.yml`: the unit suite, one forest shard, the workflow lint. That is your test run. It does not judge the issue snapshots (their worlds are not restored there).
8. **Watch without blocking.** Find the run for your head and look at it between other work:

   ```sh
   gh run list --workflow test.yml --branch fix/<n> --limit 3 --json databaseId,headSha,status,conclusion
   gh run view <id> --json status,conclusion
   gh run view <id> --log-failed          # when red: read it in full, then fix and push again
   ```

   Never sit in a sleep loop, never end a turn only because a run is going, never rerun anything locally to confirm.
9. **Judge the snapshots** before the PR, the way the check will: `bb probe run <n>-judge 'clojure -M:simrace --judge test/issues/<n>/R4-35-2.edn.gz,test/issues/<n>/R1-7-1.edn.gz --expect pass' R1,R4` from the pushed fix branch (seconds a snapshot), and the same with `--expect recur` from `main` (each moment must reproduce its failure there, or it proves nothing: take another).
10. **Open the PR when ready** (section 12): fix and test pushed, `test.yml` green, the snapshots under `test/issues/<n>/`, the before/after in hand. Never a draft.
11. **Merge when green** (section 13), move the items to Done.
12. **Stopping before the merge,** for any reason: hand off (section 14).
13. **Next.** Back to step 2.

---

## 5. Decisions already made

The forks where agents stop and reconsider. Take the answer and move on. A fork not here, after changing your mind twice: write the hypotheses with the evidence for and against each in a scratch file and settle it with one probe.

| Situation | Decision |
|---|---|
| The `fix/<n>` push is rejected, or `fix/<n>` has an open PR | Another agent has it. Choose another; do not investigate theirs. |
| A slugged branch exists (`fix/<n>-<words>`) | From before the fixed-name rule. Ignore it; the lock is `fix/<n>`. |
| You were handed an issue whose branch exists (a hand-off) | That branch and its PR are yours now: no new claim, no second PR. Start from the hand-off comment's next step. |
| A probe branch name you want exists | Not yours. Pick another word; never touch it. |
| An earlier PR fixed part of the issue (`Refs #n`, merged) | Read it first. Your job is the reproductions that still fail on current `main`. |
| The reproductions come from a round older than the issue's last merged PR | Judge them on current `main` first. If none recur there, they prove nothing about a fix: use newer rounds, or say the fix may already be in (`Refs #n`) and move on. |
| The check says a moment does not reproduce on the base commit | It proves nothing about the fix. Fetch another (`bb issue-check fetch`, the newest comment's), or ask the issue's next batch for one. Never weaken the check. |
| Labelled `sim` but only old rounds reproduced it in the sim | Do not weaken the check or add unrelated snapshots. Ask the owner to drop the label, or `Refs #n`. |
| A commit hook failed | Nothing was committed or pushed. Fix what it printed, commit again. |
| The sim and a real run disagree | Real-only signature: the real run is the evidence. Sim-only: check the model's limits first (16.6, 16.7). |
| A probe is still running | Do other work. Never dispatch the same probe twice. |
| Another Todo issue looks like the same cause | Group it before the claim. After the claim, finish yours first and mention the other in the PR. |
| A CI job fails and your probe of "the same thing" passes | Context difference, not flake (section 15). Reproduce with the job's exact command. |
| The cause is in tooling (workflows, `bb/`, the issue check, the filer) | Not yours: hand off with the evidence (section 14) and tell the owner. |
| Your tool cannot keep background processes | Poll; one foreground `gh run watch <id> --exit-status` only when nothing else is left. |

---

## 6. Reading an issue

```sh
gh issue view <n> --json title,state,labels,body --jq '.title, .state, ([.labels[].name] | join(",")), .body'
gh issue view <n> --json comments --jq '.comments[] | .createdAt + "\n" + .body'
```

Read the body and every comment.

- The signature: `[:wood :no-log]` is target `:wood`, reason `:no-log`.
- The tally: hundreds of runs over many rounds is a wall; a signature first seen right after a merge may be that merge's regression (read the merged PRs).
- Each comment's heading: real or sim, run id, runner, batch, commit. Start from the newest: their artifacts exist (7 days) and their code is closest to `main`.
- "last intents: {nil 3, :walk 2}": the intents the runs stopped in; `nil` means the plan was waiting, and the reason is the wait's.
- "landings: 2": several landings make a pattern; one may be the place itself (or the captured world's edge, 16.7).
- "(regressed: ...)": a fix did not hold or was undone. Read the closing PR first.
- A hand-off comment (section 14): start from its next step.

Then the code that raises the reason and the notes that explain it: `grep -rn ':no-log' src/clojurecraft/`, `grep -rln 'no-log' brain/`, and section 22.

---

## 7. Getting the evidence

Reading artifacts is allowed locally; running code on them is not.

**Snapshots (sim issues).** `bb issue-check fetch <n>` reads the issue's comments newest first, then its body, and copies at most two snapshots into `test/issues/<n>/`, one a world (two seeds of one world are one moment), only snapshots with a kept moment; it prints what it took and what it passed over. An expired artifact (7 days): wait for the next batch's comment, or take one by hand (`gh run download <run> -n simrace-<sha>-<runner> -D /tmp/ev/<n>/r<runner>`, then `ls .../snapshots/`).

**Run maps.** `runs.edn` is one run map a line; `:pos`, `:held`, `:plan` (`:plan/boxed-at`, `:plan/reached-after`, `:plan/last`, `:plan/intent`) say a lot before any code runs:

```sh
grep ':run/signature \[:water-bucket :boxed\]' r4/runs.edn \
  | grep -o ':run/file "[^"]*"\|:run/seed [0-9]*\|:run/ms [0-9]*\|:pos \[[^]]*\]' | paste - - - -
```

**Real races.** `gh api repos/BridgerB/clojurecraft/actions/runs/<run>/artifacts --jq '.artifacts[] | [.name, .size_in_bytes] | @tsv'`, then `gh run download <run> -n race-<sha>-<runner> -D /tmp/ev/race` (300 to 500 MB). `race.log` has every `RESULT {...}` line and the server's tick-time complaints; `samples.edn.zst` has `:sample/server-tick` (`{:avg :p50 :p95 :p99}` ms); a recording's log lines are plain strings inside it:

```sh
grep -h '^RESULT' /tmp/ev/race/s*/race.log | grep -o ':reason :[a-z-]*' | sort | uniq -c | sort -rn
zstd -dc /tmp/ev/race/s2/Clj_r08.edn.zst | grep -o '"[^"]\{3,200\}"' | grep -v '^"[0-9a-f]\{8\}-' | awk '!seen[$0]++' | tail -40
```

**Do not trust:** a bot name as one bot (2.3); a RESULT's `:pos` as where it went wrong (it is where it ended); a sim run ending at its world's edge as the bot's fault (16.7).

---

## 8. Probes

Every question that needs the code run goes to `probe.yml` on a runner, against a branch you pushed.

```sh
bb probe run <label> '<command>' [worlds]    # from the pushed branch: dispatches, prints the run id
bb probe log <id>                            # when done: artifact into /tmp/probe-<id>, prints out/run.log
```

- `label` is unique: the issue and a word (`142-pool`, `142-after`); the run's title is `probe <label>` and `bb probe` refuses a taken one.
- `command` is one shell command from the checkout root, with Java, the Clojure CLI and Babashka. Its whole output is `out/run.log`; anything else written under `out/` comes back in the artifact `probe-<id>`.
- `worlds` (`R1,R2,R3,R4` or a subset) restores the captured sim worlds a snapshot or simrace run needs. A snapshot names its world file (`cache/sim-worlds/R4/35.edn.gz`).
- `bb probe run` refuses a branch that is not pushed as it stands: the runner checks out origin's branch, nothing local.
- Expect queueing: the rounds hold most runners. Batch every question for an issue into one script rather than dispatching one probe a question, and never dispatch the same probe twice.

### 8.1 Probe branches

Never on the fix branch. One throwaway branch per question, `probe/<n>-<word>` (the same word as the label), from `origin/fix/<n>` (to probe the fix) or `origin/main` (to probe the failure). Script under `probe/`, inputs under `probe/<n>/`. Commit through the hooks (they lint probe scripts too: house style) and push. An existing probe branch is someone else's: never push to, merge, rebase onto or delete it. Delete your own when done (`git push origin --delete probe/<n>-<word>`).

### 8.2 A script that survives its own bugs

You cannot debug interactively, and a linear script dies at its first bad call, taking every later answer with it. So: wrap each question (each snapshot, each candidate function) in its own `try`, print the exception's message and keep going; print partial results as soon as you have them; ask everything in one script.

```clojure
(require '[clojurecraft.capture :as capture] '[clojurecraft.simrace :as simrace]
         '[clojurecraft.intent :as intent] '[clojure.string :as str])
(defn out [& xs] (println (str/join " " xs)))
(defn ask [label f]
  (try (out label (pr-str (f)))
       (catch Throwable e (out label "THREW" (.getName (class e)) (ex-message e)))))
(doseq [p *command-line-args*]
  (try
    (let [snap (capture/read-world p)
          [world _sim] (simrace/moment snap (simrace/prepared (capture/read-world (:snapshot/file snap))))]
      (out "==" p (pr-str (:snapshot/signature snap)))
      (ask " feet" #(intent/feet world))
      (ask " intent" #(:plan/intent world))
      (ask " boxed-at" #(:plan/boxed-at world)))
    (catch Throwable e (out "==" p "THREW" (ex-message e)))))
(shutdown-agents)
```

`simrace/moment` gives the world and the model at the snapshot's tick. From there call the act or helper you suspect with the world as its argument: everything is a pure function of the world, so `(scoop/scoop-next world)`, `(stone/deeper world)`, `(build/station world origin facing h)` answer exactly as the bot did. A private function: call it through its var, `(#'ns/fn args)`.

### 8.3 The terrain round the feet

```clojure
(require '[clojurecraft.path :as path])
(let [[fx fy fz] (intent/feet world) r 8]
  (doseq [y (range (+ fy 4) (- fy 3) -1)]
    (out "y" y)
    (doseq [z (range (- fz r) (inc (+ fz r)))]
      (out (str/join (for [x (range (- fx r) (inc (+ fx r)))]
                       (if (= [x y z] [fx fy fz]) "@"
                           (case (path/classify world [x y z])
                             :solid "#" :water "W" :clear "." :unknown "?" :lava "L" "~"))))))))
```

`~` is awkward (a slab, a lily pad; ice reads as solid). Name a block with `(blocks/name-of (terrain/block-at world [x y z]))`.

### 8.4 Judging a snapshot

`clojure -M:simrace --judge probe/<n>/R4-35-2.edn.gz[,...]` judges as the check does: from the snapshot's moment over its window (`:snapshot/moment :window-ms`, from the moment to a minute past the stop, or five minutes for a stall), uninstrumented, stopping when the plan ends or reaches the signature's stage. It prints one JUDGE line a snapshot (signature, moment, window, `:recurs?`, how it ended, simulated and wall ms) and a STORY line per intention fact from the moment on: what the bot did. `--expect recur|pass` sets the exit code. Run it on `main` and on the fix; both go in the PR. `simrace/judged` returns the RESULT, `simrace/recurs?` whether the failure recurs, `simrace/moment-of` the moment as `sim/play-from` takes it. `--from-landing f` replays the snapshot's whole run from its landing to its stage (up to 330 simulated minutes): a probe's view of the road, never the check's. To see what the bot does step by step, step it a quarter second at a time and print what changed:

```clojure
(require '[clojurecraft.sim :as sim] '[clojurecraft.plan :as plan])
(loop [world world sim sim k 0 prev nil]
  (when (and (< k 480) (not (plan/failed? world)) (not (plan/done? world)))
    (let [{world :world sim :sim} (sim/play-on simrace/step world sim (constantly false) 250 (:time/now world) [] nil)
          i (:plan/intent world)
          cur [(intent/feet world) (select-keys i [:intent/kind :intent/for :intent/stage :intent/dir])
               (select-keys (:intent/child i) [:intent/kind :intent/target :intent/status :intent/reason])]]
      (when (not= cur prev) (out (:time/now world) (pr-str cur)))
      (recur world sim (inc k) cur))))
```

### 8.5 Replaying a real recording

A recording replays exactly only with the code that made it. From a probe branch based on `main`, check that commit's source out on the runner:

```sh
bb probe run <n>-replay 'git fetch -q --depth 1 origin <sha> && git checkout FETCH_HEAD -- src resources deps.edn && clojure -M:dev probe/p<n>.clj probe/<n>/Clj_r08.edn.gz'
```

Convert before committing: `zstd -dc Clj_r08.edn.zst | gzip -c > probe/<n>/Clj_r08.edn.gz`. In the script, `(record/replay main/step (game/init {:host "replay" :port 0 :name "Clj_replay"}) path)` is the bot's final world; `clojurecraft.probe` (`dev/`) has `fold`, `at-ms`, `story` (every intent's start, end and reason) and `around`.

### 8.6 A full run from spawn

When the snapshot is too late to show the cause (9.2): `bb probe run <n>-spawn 'clojure -M:simrace --world cache/sim-worlds/R4/1.edn.gz --seed 1 --seeds 1' R4`, with the fix and with `main`. One SIMRUN line with the RESULT; a late stage takes many runner minutes, which is fine.

---

## 9. Finding the cause

### 9.1 In order

1. What was the bot doing when it stopped? The target, the running intent and its child, or the wait reason.
2. What did the world look like there? Position, inventory, the cells round it (8.3), what it remembered (`:plan/boxed-at`, `:plan/blacklist`, memory).
3. Which function returned the value that led to the stop? Call each candidate at the moment and print its answer. For a wait, find which branch of the act's `or` chain produced it and why every earlier branch was nil.
4. Why did it answer so? Usually one condition: a filter that excludes everything, a budget too small, a block read wrongly, a missing case.
5. The bot or the model? Sim-only may be the model's limit (16.7); real-only may be load (16.9).

### 9.2 The moment problem

A snapshot is kept from before the decision that failed, so the moment usually still holds the choice; but the cause can be older still (a site chosen beside a lone lava source many minutes earlier, beyond the six minutes a run keeps), with the failing decision already made. A fix for the cause alone then leaves that moment failing, and the check sees the failure recur. Fix both: (1) the cause, so new runs never get there, and (2) the state at the moment, so a bot already there recovers: usually a rule that turns a failure into a re-plan (a spent site is left, a boxed climb ends instead of failing, a placement refused for the bot's body is approached again). It must not loop: when a failure stops counting attempts, record it so the act cannot choose the same thing from the same place (as `:plan/boxed-at` does).

### 9.3 Read the history first

Most walls have been hit before. Grep the brain and the code for the reason and the situation before inventing a mechanism; the rules usually need one more case, not a subsystem. Docstrings carry the run that taught each rule; a fix that undoes one brings that failure back.

---

## 10. Writing the fix

- **Style** (`docs/RICH-HICKEY.md`, section 25). Functions of the world value: an act returns an intent or a wait and never mutates; state a rule needs lives on the world or in memory, added through an intent's own reducer. Nothing predicted: the world changes when the server says so. Time is an input: deadlines are `(:time/now world)` plus a constant. Accretion: add keys and arities, never rename or change a meaning.
- **Docstrings carry the evidence:** what the rule does and the run that taught it ("Sim race 38664661309 (issue #142, 89 runs in 18 rounds): ..."). Keep the existing evidence when you change a function; add yours.
- **A test of the decision:** the smallest world with the situation (builders in the test namespaces: `standing`, `open-ground`, `standing-at`, `deep-in`, `world/column` with `{[x y z] state}`), call the function, assert. Name it for the behaviour (`a-lone-lava-source-is-no-site`), issue and run in a comment. It must fail without the fix: you cannot run it, so reason it through, or push the test first and let `test.yml` show it red. Grep `test/` for the function's name: tests asserting the old behaviour must be updated deliberately. Section 23 has more.
- **New keys and packets:** a world or intent key gets a row in `model.clj` and a spec in `spec.clj`. A newly decoded packet: its spec in `packet.clj`, the same entry in `test/clojurecraft/packet_specs_ever.edn`, and check `packet_test` does not use it as its unmodelled example (16.10).
- **The brain note, in the same change:** find it (`grep -rln '<fn>' brain/`), add the new function and test to its `sourceRefs` (`src/clojurecraft/<ns>.clj#defn <name>`; when one name prefixes another, anchor on a unique phrase of the docstring), and the rule to its prose with the run that taught it. `bb check` runs the brain checker: `broken 0`, `ambiguous 0`, `unresolved 0`.
- **`problems.edn`, in the same change:** append one sentence to the stage row's `:problem/last-failure` (find the row by `:problem/goals`): the round, the issue, what the bot did, what it does now. One line.

---

## 11. Committing

```
<Imperative subject, 72 characters at most>

- <what went wrong, with the round and the issue>
- <what the change does, one bullet per function or rule>
- <the test; the brain note and problems.edn follow>

Fixes #<n>
```

Bot fixes use a plain imperative subject; tooling and docs use conventional prefixes. Body wrapped at about 72 columns. Reread for attribution (rule 1).

`git add -A src test brain resources && git commit -F /tmp/msg.txt`. lefthook runs `tidy-ns`, `format` (zprint), `lint` (clj-kondo, zero findings), `idioms` (Splint), `unused`, and the commit-msg check. Commit inside the shell that has the tools (`nix develop`, or `nix shell nixpkgs#babashka nixpkgs#clojure -c git commit -F /tmp/msg.txt`). A failed hook means nothing was committed; the usual ones: Splint `lint/identical-branches` (merge the two `cond` tests with `or`), `style/apply-str-interpose` (use `clojure.string/join`), a zprint layout (run `bb fmt`), `unused` (use, privatise or delete the var). zprint rewrites staged files during the commit, so line numbers in a probe script can shift between what you wrote and what ran: read the pushed file.

Start from a fresh `origin/main`. Never rebase or force-push a pushed branch; merge `origin/main` into it.

---

## 12. The pull request

Open it when the change is ready (step 10), never as a draft, from the claim branch:

```sh
gh pr create --base main --head fix/<n> --title "<the commit subject>" --body-file /tmp/pr.md
```

The body, one paragraph per line:

```
<The signature, its tally, the rounds and worlds it came from, what the evidence showed the bot doing.>

<The cause in one or two sentences.>

- **<change one>.** <what it does and why>

Before/after, each snapshot judged over its window (the check's summary, or probes <run id> on main and <run id> on this branch):

    snapshot   main                     this branch
    R4-35-2    [:cast-one :rejected]    active at [:portal]: cast done

<The test that holds it. What is under test/issues/<n>/. What is not proven and why.>

Fixes #<n>
```

`Fixes #n` closes the issue on merge; `Refs #n` for an issue touched but not proven.

**The checks** (both required):

- `unit / unit`: every test but the forest property, then the static checks.
- `check` (`issue.yml`): only the issues the PR names (its branch `fix/<n>` and its `Fixes #n` lines). Every issue the body fixes that is labelled `sim` must have snapshots under `test/issues/<n>/` or it fails at once (`issue <n> is reproduced in the sim (label sim) but test/issues/<n> holds no snapshot`); a real-only issue is only warned about. Only the snapshots the PR itself adds or changes are judged (a snapshot already on main proved an earlier fix; a fix/<n>-r2 for a regression brings its own or says Refs). Then, restoring only the captured worlds those snapshots name, `clojure -M:simrace --judge` judges them on the base commit (each must recur: a moment that does not reproduce its failure proves nothing) and on the PR's code (none may), in parallel, and writes both, JUDGE and STORY lines, to the step summary. Limit 15 minutes; a few minutes in practice. Older issues are not judged again: the races are the regression guard (the filer reopens an issue whose failure comes back).

Two snapshots an issue at most, from two landings when it has them, each under GitHub's 100 MiB file limit.

**A red check:** `gh pr checks <pr>`, then `gh run view <id> --log-failed`, read it whole, fix, push; the checks rerun. Typical: a test asserting the old behaviour; a new key without its model row or spec; a failure that still recurs on the PR's code (the moment, 9.2; read its STORY lines); a moment that does not recur on the base (fetch another, or take one from the newest comment); a sim issue without snapshots; "no checks reported" because the PR conflicts (section 13). A failure outside the code (a lost runner, a cache miss): `gh run rerun <id>`.

---

## 13. Merging and after

```sh
gh pr view <pr> --json mergeStateStatus --jq .mergeStateStatus      # CLEAN, BLOCKED, BEHIND, DIRTY
git fetch origin && git merge --no-edit origin/main && git push      # when DIRTY or BEHIND
```

`DIRTY` runs no checks until resolved. The usual conflict is `problems.edn`, where two fixes appended to the same `:problem/last-failure` line: keep both sentences.

Green and the body has the before/after: `gh pr merge <pr> --merge` (merge commits, never squash, never `--admin`). Confirm `gh issue view <n> --json state` reads CLOSED, then `bb board move <n> done` for each issue.

The merge restarts the rounds on your code; their reports 20 to 30 minutes later are the real test (a recurrence on newer code reopens the issue as regressed). Delete your probe branches; keep the fix branch.

---

## 14. Handing off

Whenever you stop before the merge (your session ends, you are told to stop, the cause is in tooling, two probes in a row contradict each other), the next agent must be able to start cold. Commit and push everything worth keeping to the claim branch (`WIP:` at the start of the subject if `bb check` fails, saying what fails in the body; probe commits stay on probe branches), stop your watchers, and post one comment on the issue:

```
Hand-off: issue #<n>, branch fix/<n> @ <sha>[, PR #<pr>]

Change: <what it does, one or two sentences>.
Cause: <as understood>; confidence <high/medium/low>.
Runs: <run id> — <what it showed>; <run id> — <what it showed>; ...
Unproven or failing: <exactly what, with the exact error>.
Next step: <the one you would have taken>.
```

Leave the branch and any open PR as they are; never a draft. Leave the item In Progress if a named agent takes over now; otherwise move it to Todo with `bb board move <n> todo` (the branch stays, and the next claimant continues it, step 4's hand-off row in section 5). Return every other item you hold that has no branch of yours to Todo.

---

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
2. **Labels.** `sim` means the model produced the signature and the fix needs snapshots. `real` alone cannot be judged by the check and closes on the next race's word. A stale `sim` label blocks the check: ask the owner to drop it.
3. **One-offs and tallies.** Fewer than 3 runs in a report is not filed. A tally from one round after a merge may be that merge's regression.
4. **The moment can still be late** (9.2): a run keeps six minutes. When the cause is far earlier and the moment hopeless, the evidence is a full run from spawn (8.6).
5. **`:timeout` snapshots are stalls.** The moment is the run's last five simulated minutes; the check judges whether the stage is reached in that window (no new stage and no end: it recurs). Nothing replays a run from its landing in the check.
6. **The sim race knows its landing.** Sim runs get `:go/at` like real bots; snapshots from before that are played with their captured world's landing (`simrace/moment`). An old snapshot behaving oddly: check `:plan/go-at`.
7. **The captured world has an edge.** 625 chunks; past them every cell is `:unknown` where a real server would load more. A failure there is the model's limit: say so in the PR.
8. **Difficulty is Normal** (issue #10): night mobs and hunger on every real server and gym; keep_inventory stays on. The sim lanes run the model at Normal too (`simrace/difficulty`): hunger drains, heals and starves by vanilla's rules (#260, `hunger.clj`, `brain/bot/tooling/sim-hunger.md`); and mobs spawn at night and in the dark, walk at the bot, hit, shoot and blast, and die to its `attack` with their loot (`mob.clj`, `brain/bot/tooling/sim-mobs.md`); a run starts at one of eight times of day by its seed.
9. **Server load is real.** 25 bots a server dropped it to 7 or 8 ticks a second (now 15 a server, #252) (`:sample/server-tick`, `server.log`). Anything the bot times by its own clock against something the server times in ticks breaks under load: digs, window answers. A real-only signature with load: look there first.
10. **"The unmodelled packet."** `packet_test` decodes an id with no spec and expects `:unknown`; adding a spec for that packet breaks it: switch its example.
11. **Give exact numbers.** Run ids, commits, counts, before and after lines. "Seems fixed" is not evidence.

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
- **Real-only and no cause in sight.** Download the round's race artifact (section 7), read the failed slots' RESULT lines, the server's tick time and the bot's log lines, and replay the recording on a runner at its commit (8.5).
- **A CI job fails and the same thing passes in a probe.** Section 15.
- **The cause is in tooling, or the issue is not a bug** (the model cannot represent it, the world's edge): hand off (section 14) and tell the owner. Never close issues by hand.

---

# Part II: the reference

Consult on demand. Spot-checked against `src/clojurecraft/` at 51505a9; when the code and this file disagree the code is right, and the fix that finds the difference corrects this file in the same PR. Find where a reason is raised with `grep -n ':<reason>' src/clojurecraft/*.clj`.

---

## 19. Planner and intents

### 19.1 The planner tick (`plan/step`)

`plan/step` = `plan/step-over` on the shipped table (`resources/clojurecraft/goals.edn`), composed after `game/step`. `:go` calls `plan/begin`; `:tick` calls `plan/plan-tick` while `:plan/status :active`, `plan/landing-tick` while `:landing`.

**The table.** Rows `:goal/id :goal/priority :goal/needs :goal/provides :goal/done? :goal/act`, optional `:goal/plan :goal/holds :goal/spares :goal/blocks :goal/depth :goal/dimension :goal/always? :goal/until :goal/superseded-by`. Need keys `:item/<name> n`, `:tag/<item-tag> n`, `:block/<name> :near`, `:fuel/ticks n`. Targets have `:goal/target? true`; producers are every other row with an act, plus one generated row per recipe (`make/craft-row`, priority 10) and furnace recipe (`make/smelt-row`). Superseded rows are never chosen; `plan/current-id` maps a `:go` naming one to its replacement. Registries: `plan/done-by` (`:goal/done?`), `plan/act` (`:goal/act`, default waits `[:no-act <act>]`), `plan/next-intent` (`:goal/plan`, default `:needs`).

**One tick (`plan/plan-tick`), in order.**
1. `trap/trapped-tick` re-derives `:plan/trap` (only with `:player/pos`); `plan/reach` adds milestones on a chained run.
2. `plan/choose`: highest-priority target in play (`:plan/goals` or every target, plus `:goal/always?` rows), not in `:plan/reached`, `done-by` false, worked in this dimension (`here?`).
3. Running intent whose cursor holds an item, or with a click unanswered (`can-abandon?` false): run it anyway (it puts the item down, the answer comes).
4. No goal here but one undone elsewhere (`elsewhere?`): abandon `:goal-met`, wait `:other-dimension`.
5. No goal: abandon `:goal-met`, `:plan/status :done`.
6. Running intent for another goal than the chosen one: abandon `:preempted`, plan again this tick.
7. Running intent: `run-intent`; when it ends, `plan-tick` runs again this tick.
8. No intent: `next-intent`. nil waits `:nothing-to-do`; `{:plan/wait r}` waits `r`; else `start-intent` with `:intent/goal`.

**Needs netting (`make/decide`, `make/next-row`).**
- `decide` order: `wood/continue-gather`, a table pick-up in flight (`chain-next` for `:take-table`), `next-row`; `:stuck` becomes `{:plan/wait :stuck}`. Act waits: `:craft` `:no-table`; `:smelt` `:no-furnace`, `:no-fuel`.
- All needs resolve against one working copy of inventory counts (`recipe/consume`). `resolve-row`: `:goal/holds` first (resolved once, handed back unconsumed), then `:goal/spares`, then `:goal/needs` × runs.
- `resolve-item-need`: producers in `producer-order` (needs covered, no table, larger yield); `:stuck` past `make/max-depth` 12, on a cycle, or none doable. `craft-now`: a craft wanted n > 1 that can run once now runs first; smelts batch on purpose.
- `resolve-fuel-need`: held fuel in smelt order, then coal, then planks (planks first when `wood-covers?`, logs less `log-reserve` 1). `resolve-block-need`: `make/near` within `table-search` 24, not blacklisted.
- Spares: worn tools don't count (`without-worn`: < `spare-uses` 40 digs left). `spare-worth?` refuses `:stuck`, any `:mine` act, and a `:gather` that would only wait `:no-log` or walk up an `:opening`.
- `take-table`: a row that fetches (`#{:gather :mine :scoop}` or `:stuck`) with no table held and the placed one within reach digs it to carry it (#39, #67).

**Waits (`plan/wait`).** Counted in planner ticks, not wall ms: `plan/wait-ticks` 400 (20 s at 20 tps) in `:plan/waiting-ticks`; then the plan fails with the wait's reason. A starved loop (9 ticks in 21 s on a loaded runner, #57) ran out a wall-clock wait before tick-counted recovery could act. Only `begin` and `start-intent` clear the wait clock, so consecutive waits with different reasons share it.

**Failure (`plan/fail-intent`).**
- Every failure: fact recorded, controls cleared, `:plan/last` dropped. Target into `:plan/blacklist`, except `plan/placing-kinds` `#{:place :redig}` (#43). A log intent's `:intent/trunk` counts in `:plan/trunk-failures`; `wood/max-trunk-failures` 2 gives the trunk up.
- `plan/boxing-kinds` `#{:stairs-down :tunnel}` failing `:boxed` add the feet cell to `:plan/boxed-at`; a `:stairs-up` does not (only `stairs/box`, `stairs/duck` record a climb's cell).
- `max-attempts` 3 consecutive failures fail the plan; a `:walk` `:no-path` counts in `:plan/unwalkable` instead (`max-unwalkable` 12); a success resets both. `:plan/reason` = last `:intent/reason` or the wait reason.

**Landing.** A `:go` with `:go/at` starts in `:landing`; `landed?` wants feet within `landing-reach` 2.0 on the plane, chunk loaded; `landing-timeout` 30000 ms fails `:no-landing`.

**Chained runs.** `--until X` plays `plan/through X`: every live target with a `:goal/until` (no escapes, no `:wade`) at or below X's priority, so `--until nether` starts at wood. With `:plan/chain? true`, `plan/reach` puts every holding target into `:plan/reached` for the run (`:plan/reached-after` ms). A reached step is never asked again; a broken tool is remade by the needs of the step that uses it. A gym stage (one target) keeps re-deriving.

**Preemption.** `:escape-water`/`:escape-lava`: priority 0, `:goal/always? true`, done by `:not-trapped-in`, planned by `:escape` only while `:plan/trap` is their kind. `abandon-intent`: fact `:abandoned`, controls cleared, `:plan/last` dropped, open container closed.

**Composition.** `intent/run-child` runs `:intent/child` as `:plan/intent` for one tick, then restores. `intent/chain-next last for drops extra`: after a `:walk` for `for`, a `:dig` of its target; after that dig, a `:collect` with drops; only links with the same `:intent/for` continue.

### 19.2 Walk (`intent/run :walk`)

To within `intent/reach` 4.0 (eye to block centre) of `:intent/target`; route planned on first tick; time in ticks. Each tick: arrived → look, done; past `walk-timeout-ticks` 1200 (60 s) → `:stuck`; no waypoints → `route!` (`:none` with none fails `:no-path`); `route-stale?` → replan, or `:no-path` after `max-replans` 6; route ended short → press straight at target; waypoint reached → advance `:intent/at`; else `follow`.
- `walk-goal`: `:near` range `goal-range` 3; `:pickup` (`pickup-range` 1.5, `pickup-rise` 2) when `:intent/pickup?`, plus `:goal/step-down?`; `:intent/for :scoop` → `:pickup` over the source, `scoop-range` 1, rise 1; `:build` → `:block`; `:opening` → `:sky`.
- `arrived?`: `:log` also wants feet ≤ `pickup-rise` below; `:scoop` feet level with or one under the cell over the source; `:build` `centred?` (within 0.25); `:opening` under open sky.
- `waypoint-reached?`: within 0.35 on the plane and 0.5 in height; one below counts once feet are down. `follow` jumps when the waypoint is above within 1.3, or when blocked. A walk ending in water is preempted by the escape.
- `route-stale?`: a waypoint ahead no longer standable; none reached for `stuck-ticks` 40; or `:partial`/`:none` with every waypoint behind. A new chunk is NOT a replan reason (it once burned six replans).
- `routable?`: acts must ask it before choosing a target. True for `:found`; `:partial`/`:none` only when heading toward a target in an unloaded chunk.

### 19.3 Dig (`intent/run :dig`, `intent/dig-stage`)

Stages `:settle` → `:digging` → `:confirm`, absolute times against `:time/now`. Run as a child by stairs, swim-out, build, redig, place niche.
- `:settle` refusals, in order: block `intent/open?` (air, water or lava only; an awkward block is dug, #91) → `:target-gone`; eye > 4.5 from centre → `:out-of-reach`; `choose-tool` `:needs-tool`; block in `intent/never-dug` `#{:obsidian}` or `exposes-fluid?` (fluid in any of six neighbours, or `fluid-over-falling?`: gravel/sand over it within 16 with fluid on top, or `bed-over-fluid?`: gravel/sand beside it resting on a fluid, #37) without `:intent/exposes-ok?` → `:unsafe`.
- Start after 3 `still?` ticks and `settle-ms` 150, or `settle-deadline` 2000. `still?` = horizontal speed < 0.05, on ground or afloat. Sends `set-carried-item`, `player-action :start-destroy` (`face-toward`), `swing`; `:intent/finish-at` = now + `finish-delay`.
- `:digging`: tool gone from slot → `:tool-broke`; swing every 350 ms; at `finish-at` send `:stop-destroy`, go `:confirm` with `confirm-ticks` 60.
- `:confirm`: block differs from START state → done. Ticks out and FINISH acked → `:rejected`. Ticks out with no ack at all → done, cell predicted air (`terrain/predict`, `:world/unconfirmed`, dropped after `terrain/unconfirmed-ms` 3000).
- `finish-delay` = `ms × 1.15 × slowdown + 100`; `confirm-ticks` also × `intent/slowdown` (`:server/tick-ms` / 50, ≥ 1, from `game/clocked` on set-time spacing; #64, #88, #144: a 130 ms/tick server left blocks standing). 3550 ms for a log by hand, 1442 for stone with a wooden pickaxe at 20 tps. FINISH is never early (property test).
- `dig-time`: `dig/ms`, 3000 for unknown blocks; penalties `:off-ground?`, `:eyes-in-water?` each divide speed by 5. `dig/best-tool`: fastest hotbar tool of the block's own kind that harvests it; stone by hand drops nothing (7500 ms).
- Only hotbar slots: callers equip a stowed tool first (`intent/stowed-tool` + `:equip`): stairs `dig-next`, mine act, place niche. The ack alone is never the break (#59, #64, #66).

### 19.4 Collect (`intent/run :collect`)

Walk onto the drop until one more of `:intent/drops` than `:intent/drops-before` is held (no drops → counts logs). No packet: vanilla picks up on contact.
- Goal point: `nearest-item` within 6 of the block centre, else the centre; progress beats best by 0.1. Past `collect-timeout` 10000 ms → `:not-picked-up` (clock restarts per resumed collect).
- Done, not failed (no attempt, no blacklist): stalled > `collect-stall-ticks` 20 with `blocker` naming a leaf (probes 0.8 ahead, head then feet) → `:intent/blocked-by`; `ledge-ahead?` (open cell 0.6 ahead, no landing within `path/max-fall`) → drop left (was `:ledge`, #93).

### 19.5 Gather chain (`wood/gather-next`)

Walk, dig, collect, each `:intent/for :log`, chained from `:plan/last`. `:wood` uses it directly, `:kit` via `:gather`.
1. `continue-gather` (only `:log`-tagged): walk → dig same target; dig → `:intent/resume` or collect; collect with `:intent/blocked-by` and < `max-clears` 6 → dig the leaf, `:intent/clears` + 1.
2. `near-walk`: nearest standing trunk within `search-radius` 48, ±12 of the eye (`memory/nearest-log`), skipping blacklisted logs and failed trunks; first routable of 3 nearest. Target = `trunk-target` (log nearest feet height, up to `max-trunk` 8 up).
3. `climb-out`: trunks within `far-radius` 256: walk to the first routable; else (not `stairs/out?`, feet not in `:plan/boxed-at`) `intent/opening`, else `:stairs-up` to top of world, `:intent/for :surface`.
4. `hunt` (under open sky): a walk `:intent/for :log-hunt` via `scoop/hunt-targets`/`first-walk`. Logs remembered within `far-radius`: toward the ground nearest the nearest log, and may walk back toward the landing. None remembered: sets out anyway in the water hunt's order (#57). nil underground or with no routable ground.
5. `crossing` (#87): under open sky, nothing else reaching a tree, the nearest trunk across open water → `:cross` (swum at the surface, jump held; done on dry land once wet and 8 nearer; stuck in the water, a child `:swim-out :retreat` to the nearest bank, #57).
6. `{:plan/wait :no-log}`.
- The `:log` tag exists so a walk to a table is never continued as a dig. A trunk no route reaches is passed over, never walked at (#75). Stairs only once a trunk is remembered, unreachable, and the shaft cannot be walked up. `stairs/out?` is false in a pit (`stairs/pit-rim`; #54, #56, #58, #63, #102-#104).

### 19.6 Stairs and tunnel (`stairs.clj`)

`:stairs-down` (`:intent/to-y`) cuts one stair at a time, never digs the block under the feet; `:stairs-up` the same upward; `:tunnel` level steps only, done after `:intent/length`. Stages are methods of `stairs-stage`.
1. `:plan`: `airborne?` (not on ground, not in liquid) waits (#65, #107). Heading `:intent/dir` (default +x). Cells `safe?` and not in `:intent/visited` → `:open`; else `lay` gives cells → `:lay`; else turn a quarter; at `max-turns` 4, `step-aside`.
2. `:lay`: child `:place` of a `filler` per cell; refused or no filler → `refuse`.
3. `:open`: child dig of the topmost solid cell (`dig-next` equips a stowed tool or fails via `tool-missing`). Child `:target-gone` counts done (`fell?`); child in `refusals` `#{:needs-tool :unsafe}` → turn; `:rejected` → dig again up to `max-rejections` 3, then `:rejected`; any other child reason fails the staircase.
4. `:step`: walk `onto` the new feet cell. `taken?`: half a block past start in the stair's direction, `centred?`, on ground going up. Stair cell solid again (gravel) → `:open`. `step-timeout-ticks` 200 (10 s) → `:stair-not-taken`.
5. `:level`: child digs of level cells, walk until feet in the cell; 200-tick clock restarts per dig. A child in `refusals` (gravel fallen in under water after the step was chosen, #171) is `level-refused`: a tunnel plans again, a staircase turns.
6. Done: down at/below `to-y`; up on ground at `to-y`, or `out?` unless `:intent/to-height?`.
- Cells: `stair-cells` head-up, head, new feet one down; `up-cells` over head, new head, new feet one up; `level-cells` head and feet one along.
- `clear-way?`: no opened cell water, lava or unloaded; no lava in any `exposed` cell; new feet not in `:plan/boxed-at`. `safe?` adds a solid floor. Water beside a stair is allowed, lava is not; a cell that itself borders water is refused by the dig (`:unsafe`) and the stair turns.
- `level-safe?`: stair rules, no opened cell bordering fluid, not `too-hard?`. `tunnel-plan`: heading when `level-safe?`, else one side kept in `:intent/side`, never back; `:boxed` when neither.
- `lay`/`floor-laying`: floor against `place/support`, else a holder first; never over a fluid (`over-fluid?`, `bridge-depth` 16; #62). `fillers`: cobblestone, self-dropping stones, dirt; never gravel or sand (#37).
- `onto` creeps under `creep-speed` 0.06 when nothing beyond stops the body; a stair must end centred or it becomes a shaft.
- `step-aside`: never straight back; keeps `:intent/level-dir`. Going up, ranked by `shore-distance` (dry top within 48; lake ice is not shore), `dry-head-distance` (`wet-head?` y+1..y+3, #61), toward `:plan/go-at`; not `under-falling?` first (#43); visited cells last. `:boxed` when none allowed or `max-level-steps` 32 spent (plus plane distance to landing for a climb).
- `duck` (climbs): no fresh level step → a stair down along the heading whose head dries soonest; feet into `:plan/boxed-at` (#37). `box`: `:stairs-up` `:intent/for :surface` ends done and records its feet instead of failing (#61).
- Stairs up: `up-safe?` refuses gravel/sand over the cell above the head; `seal` lays filler into water beside the cells unless the water is over or under (#54); `step-lay` lays the step ahead (#61); `climb-to` cuts `climb-stairs` 8 at a time (#39).
- `out?`: under open sky, an open level step under sky, no `pit-rim` (`rim-reach` 8). Tools: going down or tunnelling, pickaxe-only rock with no pickaxe → `:no-pickaxe` (never dig on by hand at 7500 ms a stone); a stair up is cut by hand (`by-hand?`, #40); snow is dug by hand (#42, #72).

### 19.7 Mine act (`stone/mine-next`) and redig

| Row | Blocks | Holds | Depth |
|---|---|---|---|
| `:mine-stone` | stone | wooden pickaxe (by item) | none |
| `:mine-coal` | coal ore, deepslate coal ore | `:tag/pickaxes` | 40 |
| `:mine-iron` | iron ore, deepslate iron ore | stone pickaxe | 16 |
| `:dig-gravel` | gravel | any pickaxe + spare (#67) | 40 |

Order: (1) `continue-mine` (`chain-next` tagged with the row id, drops `drops-of-row`); (2) `tool-missing` → wait `:no-pickaxe` (gravel never waits); (3) tool only in main inventory → `:equip`; (4) `exposed-of-states`: row blocks within 24 of the eye and `search-height` 12 from loaded terrain (never memory), not blacklisted, with a `beside` cell; first routable of 3 nearest; walk `:intent/pickup? true` (+ `:intent/step-down?` when the stand is a block up); (5) `stairs` `stair-depth` 6 down, never below `:goal/depth`; (6) `tunnel-heading` → `:tunnel` of 12 (ranked: dry top 12 ahead, then farthest from landing); (7) `deeper`: 6 more ignoring row depth, bottom more than `floor-margin` 8 away; (8) `retreat`: in water `:swim-out :retreat`, else walk to the end of a `:found :away` route of 12; (9) `climb`: `:stairs-up :intent/for :boxed` unless `out?`; (10) wait `:boxed`. Steps 5-7 return nil from a cell in `:plan/boxed-at` (#105).
- `beside`: horizontal neighbour at the block's level or one lower, the cell under it, or (step-down) a neighbour one up. Never straight above. Floor, head and ceiling blocks count.
- Iron peaks at y 16; no coal below y 0.
- Redig (`:flint-from-gravel`): `plan/act :redig` wraps in `place/ashore`; `stone/link` stages place (`:intent/passage-ok? true`) → dig → collect, one intent (as three, the planner fetched more gravel instead). Gravel back → another redig, no counter. Child place `:no-spot` while afloat (`afloat-no-spot?`) → `:swim-out :retreat`, place again (#95).

### 19.8 Scoop (`scoop/scoop-next`, `intent/run :scoop`)

`:fill-bucket` holds and spares `{:tag/pickaxes 1}`; `take-table` precedes. Surface water only, never cave water. Order: (1) `sources`: plain sources open above, within 24, `surface-water?` (≤ `memory/surface-water-below` 5 under the eye, or under open sky), not blacklisted; first in reach and `sees?` → `:scoop`; (2) `first-walk :scoop` to the bank of the first of 3 routable; (3) remembered pools within `pool-radius` 96; (4) `seen-source` when `deep?`, scooped with `:intent/aim`; (5) `from-below`: source over the feet column under ≤ `bed-depth` 2 of rock, dug with `:intent/exposes-ok?`; (6) `climb` (`:intent/for :surface`) when deeper than `underground-depth` 8 under `:plan/go-at` and no open sky; (7) `first-walk :water-hunt` over `hunt-targets`: 8 bearings, ground `hunt-distance` 40 out, within +16/-24 of the eye, none nearer the landing; (8) `pit-climb` with `:intent/to-height?` (#60); (9) `to-bank` stairs toward the seen water's bank height, then `:tunnel` 8 (#60); (10) `dig-out`; (11) `tunnel-out` 8 along `stone/tunnel-heading`; (12) wait `:no-water`. `climb` and `pit-climb` wait while `to-bank` has water to cut toward (#69: they undid its stairs for 90 minutes).
- Intent: `:equip` (`:not-held`); `:aim` at `aim-point` (0.85 up the source), no longer scoopable → `:target-gone`, 3 still ticks + 300 ms → `use-item`; `:sent`: one more filled bucket → done, else re-aim after 3000 ms, `max-tries` 3 → `:no-scoop` (source blacklisted).
- `scoopable?` wants air over the source; `sees?` wants every ray cell air or water: a plant is a wall (#83). `first-walk` drops already-arrived targets. `boxed-here?`: no climb/pit-climb/dig-out from a `:plan/boxed-at` cell (#61). Only the inventory says the bucket filled.

### 19.9 Place, equip, open-container (`place.clj`)

Success only when the server shows the block. Given `:intent/cell` (build, stairs) is used; else a spot is found.
1. `:equip`: held → on; hotbar → `set-carried-item`; main → window-0 swap click (an open container closed first: the server ignores window-0 clicks while one is open); none → `:not-held`.
2. `:spot`: given cell with a `support` neighbour, else `:no-support`. Else `spot`, else `niche` (dug first, stowed tool equipped), else with `:intent/passage-ok?` a passage spot, else once `fresh-rock` (walk `fresh-rock-distance` 12 away via an `:away` route, or with no route `fresh-rock-climb` 4 stairs up; never in water; `:intent/retreated?`; #94, #148), else `:no-spot`.
3. `:settle`: target no longer replaceable → back to `:spot`; 3 still ticks + 300 ms → `use-item-on`.
4. `:sent`: block name at target → done. Acked (500 ms passed) with cell still replaceable and < `max-refusals` 3 → `give-up` (another spot; #106). Acked otherwise → `:rejected`. 3000 ms no answer → `:no-answer`.
- `spot`: rings 1-3 round the feet (levels 0, -1, +1), target replaceable and `terrain/confirmed?`, support solid and not a placed container, not `passage?`, not inside the player, within `place-reach` 4.0. Flowers are not replaceable.
- `niche`: wall cell at feet or head level, solid above and below, nothing falling over, no fluid beside, droppable by held tools, not obsidian or placed; side nearest the landing, never ahead (#94). `dig-niche` waits for the server's confirm (or 3000 ms after FINISH) before placing.
- `place/ashore`: in water, `:swim-out :retreat` first (#95). `:open-container`: `:out-of-reach` beyond `open-reach` 4.5, `:no-window` after 3000 ms.

### 19.10 Build (`build.clj`)

A build is a vector of ops with done-predicates as data (`build/holds?`); `next-op` works the first unsatisfied one; no progress is stored, so it resumes from the world after death or relaunch.
- Ops `:build/op` ∈ `:place :dig :pour :scoop :use-on :stand-in :verify`, with `:build/pos :build/stand :build/item :build/aim :build/done`. Predicates `[:solid p] [:not-solid p] [:firm p] [:filled p] [:block p name] [:lava-source p] [:no-water p] [:dimension name] [:or ...] [:and ...]`.
- `portal-plan origin facing h` (#124, the rock is the mould): `room-ops` (landing, room two rows six high, five-step stair, access steps to `h`, floors firm); `cell-ops` per frame cell in `layers` order (bottom up, NEVER sorted): walls firm, hole and cell over it dug, lava poured low, water poured into the cell directly over the lava, scooped back; then `interior-ops`, `light-op`, `enter-op`. `cast-one-plan`: room, first cell, `:verify`.
- `:build` intent per tick: `plan-for` (memoized), `next-op`, `work-op`. `op-child`: lava pour with no lava bucket → `refill`; water pour with other lava touching → `:stray-lava`; `:place` into `foundation` (up to depth 4) or `:no-support`; `:dig` with `:intent/exposes-ok?`; `:pour`/`:use-on` equip, aim, use, wait `:intent/expect`; `:stand-in` → `:await`; `:verify` → `:unverified`.
- A dig child failing `:target-gone` counts done (`found-open?`, #91). A place child `:rejected` with its cell inside the player is re-approached (`refused-for-the-body?`, #44).
- `approach-stand`: `reachable-stand` (refuses a stand whose feet or head is the worked cell, #44; also tries feet one under the cell), `room-stand`; with no route to any, cuts toward `nearest-stand-cell` (feet's height only, sorted by distance to the worked cell, line must have a diggable block; `stance-block` never takes frame rock; #74) via `toward-stand`.
- `work-op`: `:no-station` with a `spent?` pool ends the build done (new site chosen); a way child failing `:no-path`/`:stuck` beyond `site-reach` of the landing gives the site up and ends the build (`lost-way?`, `memory/give-up-site`; #222); any other keyword fails it.
- `choose-site`: beside the `max-sources` 3 nearest lava sources not within `give-up-radius` of a given-up site, any depth; `site-ok?` wants footprint loaded, no fluid in or touching it, pool not `spent?` (≥ as many reachable sources as cells left; #142); then fewest `blocks-needed` (≤ `site-blocks` 8), then nearest.
- `refill`: `station` (4 to `station-depth` 9 in front, within `station-span` 8 aside, sources in reach); scoop the nearest seen source, else dig `sight-block`; returns `:no-station`/`:no-bucket`.
- `plan/next-intent :build`: `held-first` (row's `:goal/holds` + cobblestone by `blocks-needed`; #70) via `make/decide`; then `:build` at the remembered site unless spent, else a chosen one; else `lava-hunt` (`lava-walk`, `uncap`, `toward-lava`; #41); else wait `:no-site`.
- Rules: never dig obsidian; water above the lava, never beside; landing reached at the lava's height (`:no-stand` otherwise); every worked cell in `footprint`; `kept` cells never dug for a way; a stand over water placed from shore (#45). `:no-effect` = pour/use-on with no effect in 3000 ms; `:not-yet` = `:await` past `await-ms` 10000.

### 19.11 Escape (`trap.clj`, `swim.clj`)

- Trap (`trap/trapped-tick`): lava on any contact (`:player/in-lava?`); water when `:plan/wet-ticks` (eye under) reaches `trap-after-ticks` 20. Wading never preempts. Clears after `dry-land?` (on ground, body dry, eye in air, solid non-lily-pad below) for `dry-ticks-to-clear` 6. On clearing, `{:trap/pos :trap/kind :trap/at}` joins `:world/traps`; `path/trap-factor` costs routes within 2 columns 10× for 600000 ms.
- `:swim-out`: `banks` within `bank-radius` 16, from 3 under to 1 over `float-top` (1.03 under an open surface), kept once picked. No bank: dig the cap (`obstacle`), else `notch` a wall within 4 (never under a roofed surface), else hold jump and look again every 20 ticks. A kept bank no longer standable (`flooded?`, water flowed in; #156, #157) is dropped and another picked. Stuck 40 ticks without 0.05 progress: dig `obstacle` (lily pad, head-height ahead, feet-height ahead when the bank is no higher than the feet, over the head; never feet-height ahead under a higher bank; #156); none → `:stuck`. Child digs via `dig-afloat` (jump held, #73) or `dig-or-sink` (sneak to the floor, `:intent/dig-when-down`: 5.75 s instead of 28 s). > `escape-timeout-ticks` 2400 → `:no-escape`. A `:retreat` swim-out ends itself on `dry-land?`.
- `:flee`: footing within 6, one up or down, no lava beside or under; none or > 200 ticks → `:no-footing`.
- `:wade` (gym `:shore`): entry over pools within 64 and 16 below; walk to bank, then head `wade-beyond` 4 ahead of the feet once wet. Fails `:no-water` (no entry, or 2400 ticks), `:shallow`.

### 19.12 Pathfinder (`path/plan`)

`(path/plan world from goal opts)` → `{:path/waypoints :path/cost :path/status :found|:partial|:none :path/from}`. Pure A*, budget in node expansions (`default-max-nodes` 6000) so replays match; `:partial` when spent, `:none` when the open set empties, both return the route to the nearest node seen.
- Classes (`classify-id`): `:water :lava :awkward` (shape taller than 0.125, or slime, ice, magma, cobweb) `:solid :clear :unknown`. One snow layer is `:clear` (#38: 39 bots froze). Powder snow is `:clear` and not solid: a player sinks through it (#87). `:unknown` is never floor nor clear: routes stop at the loaded edge.
- Moves: `:walk` 1, `:jump-up` 2, `:diagonal` √2 (both corners clear), `:drop` 1 + 0.5/block up to `max-fall` 3; `landing` refuses water, lava, awkward, unknown. `clear?` refuses lava in the four sides or below. Water is a wall, never a floor.
- `start-cell`: feet cell if standable, else nearest within one block (block-edge starts).
- Goals (`goal-done?`, `heuristic`): `:near` (3) `:block :xz :away :pickup` (`:goal/rise`, `:goal/step-down?`) `:sky` (`sky-weight` 4, not admissible).
- Never: parkour, digging, scaffolding.

### 19.13 Fortress tunnel (`fortress.clj`)

`:fortress` (Nether) done by `:at-fortress?`: a brick on record at y ≥ 32 within 6. `:tunnel-to` the nearest brick within 512, else explore 48 along the first safe axis; wait `:other-dimension` outside the Nether, `:no-way` nowhere safe. `step-plan`: floor under the new cell, `opens` dug, stand in it (worked by `build/next-op`). `safe?` refuses unloaded cells, opened lava/unbreakable cells, lava beside an opened cell, a floor neither solid nor lava. Vertical first when > 3 off. Done at target or after 32 steps. Failure `:blocked`.

### 19.14 Intentions as facts (`memory.clj`)

`plan/start-intent` numbers the intent (`:intent/id` from `:plan/intents`) and appends `:started`; then `:done`, `:failed` (with `:intention/reason`), or `:abandoned`. `memory/intentions` (oldest first); `memory/intention-as-of world t`: what it was doing at t (the tick before a death). Answers: `:ack` (`:answer/sequence`), `:pickup` (`:answer/entity :answer/count`), `:death`; read with `memory/answers`. Intents without `:intent/id` (hand-built in tests) are not recorded.

---

## 20. World, protocol, game

Every attribute, its meaning and lifetime is in `src/clojurecraft/model.clj` (`model/attributes`) and its spec in `spec.clj`; read those, not a copy. One line per namespace is in CLAUDE.md's Layout.

### 20.1 Design decisions a fix must not break

- One world value, written only by `main/apply-event!`; unknown = absent, never nil. No clock or `rand` in a reducer (`:event/now`, `:event/rand`); new nondeterminism must be an event field (an early `rand-nth` broke replay). Phase changes only in `game/emit` via `packet/transitions`.
- Goals in play arrive on `:go` (`:go/goals`, `:go/chain?`), never via a CLI flag or dynamic var. Chained runs use milestones (`:plan/reached`); without them a run recrafted sticks forever. `:portal-kit` counts the filled water bucket beside the empty one (#49).
- Memory is append-only DataScript facts. Protocol from scratch: never add a Java client library.
- Predict nothing: every `container-click` is sent with `:changed [] :cursor nil`; windows change only on `container-set-slot`, `container-set-content`, `set-cursor-item`. Manual clicks, never `place-recipe` (no spec; undecodable display ids; vanilla ghost-places).
- `recipe/sources` takes each cell group from one stack that covers it, else several, lowest first (#86), never reading the grid. One `:craft` intent = one craft; slot 0 shift-clicked only when exactly `{:item result :count makes}`. Window-0 slots 0-4 live in `:window/grid`, never `:player/inventory`; counts read the inventory only.
- The server judges placement: done only on its `block-update`. One planner (`make/next-intent :needs`): never add a second answer to the same question. `blocks/log-items` matches only names ending `_log` (no `*_wood`, no `crimson_stem`).
- `memo/by` keys by `identical?` (`memory/positions-in`, `stone/exposed-cells`, `path/plan-on`): keep the clock out of memo keys and the overlay identical when nothing changed (`terrain/superseded`), or plan-tick cost explodes.
- New attribute → row in `model/attributes` + spec in the same change. Meanings never change; packet specs only grow (`packet_specs_ever.edn`); goal rows are superseded, never edited.

### 20.2 Terrain (`terrain.clj`, `chunk.clj`, `blocks.clj`)

- `:world/chunks` `{[cx cz] {:sections [...] :chunk/min-y y}}`; overworld 24 sections from -64, Nether/End 16 from 0 (decode takes the count from the column).
- Section `{:single id}` or `{:bits n :palette [...] :longs long[]}`; palette nil above 8 bits; index `(y<<8)|(z<<4)|x`, entries never straddle longs, no long-count prefix in 26.x.
- `terrain/block-at`: overlay `:world/blocks` first, then `chunk/block-at`. **nil = not loaded, never air.**
- Overlay writers: `set-block` (server's word; confirms an unconfirmed cell; `memory/observe`), `predict` (dig-silence guess into `:world/unconfirmed`), `forget-stale` (drops guesses > 3000 ms every tick). `confirmed?` = not a guess. `put-column` drops overlay and guesses inside that chunk.
- `solid-fn` treats unloaded as solid; `blocks/solid?` of an unknown id is false (two different questions). `fluid-at` → `{:fluid/kind :fluid/level}` or nil (unloaded = no fluid; kelp, seagrass, bubble columns count as water source). `under-sky?`: only air, plants or leaves above up to y 319; unloaded is not under sky.
- `blocks/passable-types`: `grass` (the type of `grass_block`) deliberately absent (copying ruststeve's list made the ground passable); `tall_grass`, `snow_layer`, `nether_portal` passable. Leaves solid; slabs, stairs, fences, doors are full cubes.
- `blocks/replaceable?` (bot's spot rule): air, grasses, ferns, dead bush, seagrass, vines, roots, lichen; never flowers, flower beds, bushes, saplings. `blocks/placeable-into?` (server's): those plus any fluid (#44).
- State ids (26.1.2): air 0, cave_air 15293, stone 1, grass_block 8-9, dirt 10, water 86-101, lava 102-117, oak_log 136-138; item entity type 71. Regenerate with `bb datagen`.

### 20.3 Memory (`memory.clj`)

- Sightings `{:sight/pos :sight/state :sight/at :sight/dimension}` appended only on change. `watched?`: logs, `placed-kinds` `#{:crafting_table :furnace}`, `landmark-kinds` (fortress bricks, fences, stairs, lit portal). Never stone (it froze every chunk load).
- Pools: one fact per chunk and kind (highest surface source nearest the centre). Build sites: `remember-site`/`site`.
- Queries (in the bot's dimension): `remembered`, `history`, `latest`, `positions-now` (memoised `positions-in`), `nearest`, `nearest-log` (radius 48, ±12 of the eye; blacklist removed before `trunk-bottom?`), `pools`, `nearest-surface-water` (≤ 5 under the eye; deeper is the aquifer trap), `answers`, `intentions`, `intention-as-of`.
- Edges: air is sticky; a chunk load records logs but not air (a log broken while unloaded stays a log); a chunk load clears the overlay, not memory; first facts carry time 0; no eviction. Terrain answers "is water beside me now", memory "where can I walk for water".

### 20.4 Physics (`physics.clj`)

- `(physics/step solid? fluid-at world controls)`, run by `game/on-tick` only when `game/physics-ready?` and not dead. Controls `:control/forward? :jump? :sneak? :yaw :look` persist in `:player/controls`.
- Constants: tick 50 ms, gravity 0.08, drag 0.98, half-width 0.3, height 1.8, eye 1.62, jump 0.42, jump cooldown 10 ticks. Walk 0.216 blocks/tick; jump peak 1.2-1.3. **Resting vy is -0.0784, never 0**: stillness checks are horizontal only. No step-up, sprint or strafe; non-full cubes are full cubes. Yaw: south 0, east -90; looking down is positive pitch.
- Fluids: `eye-in-water?` tests y+1.62. **The only lift out of water** is `out-of-liquid-impulse` 0.3 on horizontal collision when the box moved up 0.6 would be free; swim-up never clears a one-block bank. Floats with feet 1.03 under the surface, 0.1 blocks/tick. Flow currents are **not modelled**.
- Breath `:player/air` is an estimate (own metadata not decoded): -1/tick eye under, +4 otherwise, 0-300.
- `move-player-pos-rot` on change, status-only every 20 ticks; on-ground flag `0x01` (typecraft's `0x80` is wrong).

### 20.5 Protocol

- A packet is a flat map with `:packet/name` and unqualified wire fields; never a `:name` field (collision; hello uses `:username`).
- `packet/specs` maps `[state dir name]` → `[[key type] ...]` (66 rows). `packet/values` names enums; a missing name throws. `decode` reads listed fields only; `encode` throws on a missing spec. Ids in generated `packets.edn`.
- Synthetic packets: no spec → `{:packet/name :unknown}` counted in `:stats/unknown`; decode exception → `:decode-error`; socket failure → `:closed` (sets `:bot/closed`).
- `:slot` components decode through `component-codecs` (damage, max damage, max stack size, repair cost, unbreakable); any other truncates the rest (`:truncated true`). Before this, a worn pickaxe hid the water bucket behind it (`:no-scoop`).
- Phases `:handshake → :login → :configuration → :play`, back on `start-configuration`. `hello` carries the MD5 v3 UUID of `OfflinePlayer:<name>`; changing `--name` changes player data.
- The first teleport also emits `player-loaded`; without it nothing (pickup included) ticks for the player; resent after a dimension change.
- `set-time` has a fieldless spec; `game/clocked` estimates `:server/tick-ms` from its spacing (one every 20 server ticks). Not modelled: `attack` (c2s 1), `place-recipe` (39), encryption. Entity metadata is skimmed (`packet/read-metadata`: simple serializers, stopping at the first other); every entity is tracked with velocity, a living one's health and flags, deaths (`entity.clj`, brain `world/entities`, #258).
- `:bot/sequence` increments for each `player-action`, `use-item-on`, `use-item`; answered when `:stats/last-ack` ≥ ours. `block-changed-ack` appends an `:ack` answer.
- **After a break the server sends `block-update` then the ack. An ack alone means not broken** (dropped early FINISH, or refused). Under delayed destroy a loaded server breaks seconds after the ack (#64).
- Never predicted: placed blocks, window slots and cursor, an acked-but-undone break, pickups (`take-item-entity` with our id appends `:pickup`), health. The only guess is the dig-silence overlay.
- Death: `set-health ≤ 0` → `game/die` (dead, controls cleared, `:death` answer, `:plan/intent`/`:plan/last` dropped, `client-command 0`). `change-dimension` drops columns and overlay and resets `:player/loaded?`. A hand-built `{:packet/name :respawn}` fails the spec; use `fixtures/respawn`.

### 20.6 Loop, recording, replay

- `main/step` = `(game/compose game/step plan/step)`; ticks on a fixed 50 ms grid. `apply-event!` swaps, reads effects, clears them, performs them: a reducer may assume `:bot/effects` empty at step start.
- `-main` defaults: port 25571, name `Clj_wood`, `--until wood`, timeout 120 s. `--events stdin` drops bad EDN lines (`external-event`).
- RESULT: one stdout line `RESULT {...}`; `:reason` is `:goal`, `:disconnected`, `:closed`, a `:plan/reason`, or `:timeout`; `:held`, `:plan`, `:stats`. Exit 0 iff `:ok true`.
- Recording: optional `{:record/commit sha}`, one EDN line per event (`record/wire` drops derived `:chunk/column`), `#:record{:effects ...}` after any event with effects (grep that form), `{:record/result ...}` last. Tap runs before the swap; `tap-buffer` 4096 blocks rather than drop. `.gz` reads directly.
- Replay: `record/verify` compares `:send` effects (logs ignored): `:identical` or the first `:record/mismatch`. **Fold a recording at its own commit** (`bb replay`; `--here` for the working tree): newer code makes a whole file look truncated (#111).
- `dev/clojurecraft/probe.clj`: `fold`, `at-ms`, `story` (every intent's start, end, reason), `around` (slice round the feet: `#` solid `.` clear `W` water `L` lava `~` awkward `g` falling).
- Sim snapshots `<set>-<n>-<seed>.edn.gz`: a moment kept from before the choice that failed (`simrace/decision`, `chosen-moment`; runs keep a moment every 30 s, the last 12, `sim/play-from`), with the next tick's inputs and `:snapshot/moment` (why, when, window). `simrace/judged` plays it over its window, never from the landing. `bb issue-check fetch <n>` → `test/issues/<n>/`; `clojure -M:simrace --judge f` judges one.
- Telemetry (`watch.clj`) is a view: offered to a 1024 channel, drops rather than slow the loop, never recorded.

### 20.7 Windows, crafting, smelting

| Window | Own slots | Player slots | Hotbar starts | Menu type |
|---|---|---|---|---|
| Window 0 | 0 result, 1-4 grid, 5-8 armour | main 9-35, hotbar 36-44, offhand 45 | 36 | none |
| Crafting table | 0 result, 1-9 grid | main 10-36, hotbar 37-45 | 37 | 12 |
| Furnace | input 0, fuel 1, output 2 | from 3 | 30 | 14 |

- Name slots with `inventory/slot-in` (`[:hotbar n] [:main n] [:result] [:grid n] [:input] [:fuel] [:output]` ...).
- A current-state-id click is answered per changed slot; a stale one by `container-set-content` + real cursor. **A click that changes nothing gets no packet.** A click for a closed window is silently ignored.
- `window/click` emits, drops `:window/cursor`, records `:intent/awaiting`; intent-only. `window/waiting` → nil, `:waiting`, or `:overdue` after `answer-timeout` 3000. On overdue `window/lost` resyncs (`resync-click`, state id -1, cursor put in a free slot, or with none a `junk` stack thrown); the `max-lost` 3rd loss fails `:stale-window` (#131, #132: loaded servers answer late, not never).
- **A full inventory:** a result or output moved into 36 full slots stays put and gets no answer. The craft's and smelt's `:settle` throw a `window/junk` stack first when no slot is free (`window/tossable`, `toss`: look up to `toss-pitch`, then mode 4; cobblestone only above `cobblestone-keep`; #172, #192).
- **Phantom cursor:** the server adopts the click's claimed cursor, so `window/click` forgets the cursor as it sends; never claim a non-empty cursor.
- **A furnace moves the state id by itself:** judge a click by the slot clicked.
- `recipe/recipes` 1030 recipes, tags resolved; `datagen/shrink` trims padding. `recipe/clicks` lays one craft; nil when not coverable.
- `:craft`: `:settle` (`:unknown-recipe`; close any container for `:inventory`; 5 s for a state id `:no-window`; cursor down `:inventory-full`; shift-click grid empty; plan `:no-ingredient`) → `:click` (one per round trip) → `:verify` (slot 0 exactly expected else `:wrong-result`; 5 s `:no-result`) → `:take` (5 s `:not-taken`; table craft then closes). Junk crafts (two planks → pressure plate, one → button) are why the grid is emptied and slot 0 verified.
- Table: `make/craft-row` adds `{:block/crafting_table :near}` to recipes over 2×2; `make/at-container` uses an open table, opens one in reach (4.5), or walks; menu type must be 12 else `:no-table-window`.
- Smelting: `make/smelt-row` needs input, `{:fuel/ticks t}`, `{:block/furnace :near}`. `smelt/burn-order` coal/charcoal then planks; never logs, never sticks (#36); charcoal never made. `fuel-clicks` loads `ceil(ticks/burn)` from one stack. `:smelt`: `:settle` → `:load` (fuel then input) → `:cook` (`loaded?` 5 s `:not-loaded`; `cook-by` = now + n × cook ticks × 50 + 5 s; next fuel stack or `:out-of-fuel`; past it `:not-cooked` once the furnace stops cooking, `cooking?`) → `:take` (5 s) → `:close`. 200 ticks/item; plank burns 300. An abandoned mid-cook smelt leaves input in the furnace, invisible to the planner.

### 20.8 Vanilla 26.1.2 mechanics

- Dig: `damage/tick = speed / hardness / (30 if harvest else 100)`, `ticks = ceil(1/damage)`. Log by hand 3000 ms, leaves 300. Tier ranks: wood/gold 0, stone/copper 1, iron 2, diamond/netherite 3; stone needs any pickaxe, iron ore stone. Gravel's flint is a 0.1 roll.
- Server takes FINISH once 70% of its break time passed since START; earlier aborts silently.
- Reach: dig 4.5 eye to centre, place 4.0, open 4.5, sim bucket ray 4.5.
- `use-item-on` (c2s 66): bot clicks the support's top face (face 1), cursor `[0.5 1.0 0.5]`. Server places only into replaceable cells (+ fluids), **never into any player's box** (#106); right-clicking a table or furnace opens it. Rejected = `block-update` restating the cell, then the ack. Judge furnaces by block name (facing varies).
- Buckets use `use-item` (c2s 67, carries yaw/pitch), not `use-item-on`; flint and steel uses `use-item-on`. Empty bucket ray-casts source-only; full one pours before the first solid.
- Fluids (`fluid.clj`): water drop 1, slope 4, every 250 ms, makes sources; lava drop 2, slope 2, every 1500 ms (Nether: drop 1, 500 ms). Lava touched by water **above or beside (never below)** → obsidian from a source, cobblestone from a flow. Not modelled: waterlogged flows, fire.
- Falling blocks fall 2 ticks after the cell below changes; gravel refills a niche dug under it.
- Pickup: box inflated (1, 0.5, 1), 10-tick delay, 0.5-1 s after FINISH live.
- 20 tps; one server stops near 25 bots. Drowning: 2 damage every 20 ticks at zero air.
- Server settings: any new `server.properties` needs `pause-when-empty-seconds=0` and `spawn-protection=0` (else a non-op's breaks revert after the ack). `race.clj` sends `gamerule keep_inventory true`; camelCase gamerules return an error string, not an exception.

### 20.9 The server model (`sim.clj`) and its limits

- `(sim/step sim event)`; driven by `sim/play-from` (from a moment: world, model, pending packets, tick seed; keeps moments when asked), `sim/run`/`run+` (from the handshake, 50 ms ticks, moments kept), `sim/play-on` (from a world and model), `sim/connect!` (behind channels like `conn/open!`).
- Faithful: handshake, chunk streaming, breaks at 70% with update before ack, delayed destroy, seeded loot, tool wear (tools stack to 1, #36), pickup after 500 ms, windows with vanilla click rules, furnaces cooking every tick, placement under `placeable-into?`, buckets, fluid flow (`tick-fluids`), falling blocks, portal lighting (dimension changes after 4000 ms), drowning, respawn, hunger and eating at a given `:difficulty`, mobs (night spawns, melee, arrows, creeper blasts, the `attack` with its hurt window, loot; fixtures by `:mobs`) (#260).
- Inputs: `:columns`, faults `:drop-clicks :drop-breaks :lag-ticks :slow-breaks :kill-at`, `:inventory`, `:seed`, `:tick-seed`. `:sim/violations` (`:click-on-empty-result`, `:close-with-cursor`, `:click-inventory-while-open`, `:place-into-player`) must be `[]` in end-to-end tests.
- Not modelled: real server lag (no reorder, duplicate, lost cursor, set-time, so `:server/tick-ms` never stretches in the sim); the captured world's edge (radius 12 columns; past it unloaded = solid/`:unknown`); water push and rubber-banding (model trusts reported position); `use-item-on` reach; block facing; break penalties; stacking of buckets (64); hoppers, lava-bucket fuel; a never-opened furnace does not tick.
- A sim-only failure touching those may be the model's limit: if a real race on the same signature does not fail, fix `sim.clj` first against the jar (#44: model refused placing into water; #36: tools stacked). Real-only failures under load need a fault input before a sim test can hold the fix.

### 20.10 Gotchas already paid for

Most are stated where they apply above (grass type, resting vy, `:name` collision, early FINISH, phantom cursor, junk crafts, sticky memory, guesses, ashore, block-edge starts, furnace state ids, own-commit replay). The rest:
- Digging the bottom log from above dropped it into a hole: `wood/trunk-target` digs the log nearest feet height. Leaves at head height over a drop: collect stall → `intent/blocker` → dig the leaf (≤ 6); `step?` also checks over the step and the player.
- Never place into a guess, a flower, onto a table or into a body: each cost three `:rejected` in a race. Gravel refilled a niche dug under it seven times.
- Nether columns read at overworld height put brick 64 off: read height from the column. `set-held-slot` outside 0-8 is ignored (found by the totality property).

---

## 21. Stage chain

`--until <stage>` plays every target up to it in priority order (`plan/through`; races use `nether`). A signature `[target reason]` names the target being worked: `[:ingots :boxed]` had every earlier target and stopped on the way to the ingots.

| Pri | Target (`--until`) | Done when | Plan / holds |
|---|---|---|---|
| 0 | `:escape-water` (always) | `:not-trapped-in` | `:escape` → `:swim-out` |
| 0 | `:escape-lava` (always) | `:not-trapped-in` | `:escape` → `:flee` |
| 0 | `:defend` (always, after the escapes; `defend`) | `:safe?`: no hostile within 4, no creeper within 6 (blacklisted ghosts aside) | `:defend` → `:fight`, or `:back-off` from a creeper or under 10 health (issue #259) |
| 0 | `:eat` (always, after `:defend`; `eat`) | `:fed?`: food ≥ 14, unknown, or nothing safe to eat held | `:eat` → `:eat` (issue #259) |
| 0 | `:hunt` (always, after `:eat`) | `:provisioned?`: fed, food held, or no animal a route reaches within 24 and no kill to pick up | `:hunt` → `:walk`, `:fight`, `:collect` the meat |
| 0 | `:wear-armour` (always, after `:hunt`) | `:dressed?`: no piece held over an empty armour slot | `:wear` → `:wear` |
| 13 | `:sword` (always, after `:armour`) | `:armed?`: no better sword the held items make with a handle and a table at hand | `:sword` → needs planner (craft) |
| 5 | `:attack` (aside: gym only, never chained; `attack`) | `:cleared?`: no hostile within 16 | `:attack` → `:fight` |
| 13 | `:armour` (always, after `:portal-kit`; `armour`) | `:armoured?`: no iron helmet or chestplate the spare ingots pay for, table at hand | `:armour` → needs planner (craft) |
| 1 | `:wood` | `{:tag/logs 1}` | needs |
| 2 | `:kit` (`table`) | `{:item/crafting_table 1 :item/stick 4}` | needs |
| 3 | `:pickaxe` | wooden pickaxe | needs |
| 4 | `:cobblestone` | 3 cobblestone | needs |
| 5 | `:shore` | `:escaped-water?` | `:wade` (gym only) |
| 6 | `:stone-pickaxe` | stone pickaxe | needs |
| 7 | `:furnace` | `{:block/furnace :near}` | needs |
| 8 | `:ingots` | 4 iron ingots | needs |
| 9 | `:bucket` | 1 bucket | needs |
| 10 | `:water-bucket` | 1 water bucket (surface water only) | needs |
| 11 | `:flint-and-steel` | flint and steel | needs |
| 12 | `:iron-kit` | flint and steel + water bucket | needs |
| 13 | `:portal-kit` | water bucket + empty bucket (#49) | needs |
| 14 | `:cast-one` | `:built?` | `:build :cast-one`, holds a pickaxe (#70) |
| 15 | `:portal` | `:built?` | `:build :portal`, holds a pickaxe |
| 16 | `:nether` | `:built?` (stood in until the Nether) | `:build :nether`, holds a pickaxe |
| 17 | `:fortress` | `:at-fortress?` | `:fortress` |
| 19 | `:ender-pearls` (aside for now; `pearls-12`) | 12 ender pearls | `:pearls` → `:collect` the drop, `:fight`, `:provoke` by a stare from range, `:walk` (issue #263) |
| 19 | `:ender-pearl` (aside: gym only; `pearls-1`) | 1 ender pearl | `:pearls`, as above |
| 20 | `:eyes-of-ender` (aside for now; `eyes`) | 12 eyes of ender | `:eyes` → needs planner (craft blaze powder, then eyes, 2x2), waits `:no-pearls` / `:no-rods` (issue #264) |

- `:iron-kit` takes flint and steel first so all iron is mined in one trip down and the climb out ends at the water. The cast's few placed blocks are counted from the plan (`build/held-first`, `blocks-needed`), never a constant.

| Producer | Act | Needs → provides | Holds / spares |
|---|---|---|---|
| `:gather-log` | `:gather` | → `{:tag/logs 1}` | |
| `:mine-stone` | `:mine` | → cobblestone | holds wooden pickaxe |
| `:mine-coal` | `:mine` | → coal (depth 40) | holds + spares `:tag/pickaxes` (#36) |
| `:mine-iron` | `:mine` | → raw iron (depth 16) | holds stone pickaxe; spares stone pickaxe + 4 sticks (#39) |
| `:dig-gravel` | `:mine` | → gravel (depth 40) | holds + spares `:tag/pickaxes` (#67) |
| `:flint-from-gravel` | `:redig` | gravel → flint (1 in 10) | |
| `:fill-bucket` | `:scoop` | bucket → water bucket | holds + spares `:tag/pickaxes` |
| `:place-table` | `:place` | table → `{:block/crafting_table :near}` (passage allowed; taken along) | |
| `:place-furnace` | `:place` | furnace → `{:block/furnace :near}` | |

- Tool budget that drove the spares: stairs down, tunnels and the climb out dig about 390 blocks, three stone pickaxes' worth; a stone pickaxe lasts 131 blocks; fifty stairs up dig about 150.
- To change a row: add the fixed row under a new id, move `:goal/until` to it, set `:goal/superseded-by` on the old one. Never edit or delete. The hand-written stage problem statements are in `problems.edn`; update a stage's row when it fails a new way.

---

## 22. Failure reasons

Signature = `[target reason]`. Meaning is the common case; read the raising code for your run. Past issues per reason: `gh issue list --label bot-failure --state all`; a regressed issue's closing PR is the first read.

| Reason | Usually means | Raised in | First look |
|---|---|---|---|
| `:no-path` | no route after 6 replans, or 12 unwalkable walks in a row; target chosen without asking `routable?` | `intent/run :walk`, `intent/routable?`, `plan/fail-intent`, `wood/near-walk`, `wood/climb-out`, `build/reachable-stand`, `stairs/seal` | terrain the moves lack (step beside water, snow, ledge); does the act call `routable?` |
| `:no-log` | wait ran out: no trunk to walk to, climb for or hunt toward | `wood/hunt`, `wood/gather-next`, `wood/climb-out`, `stairs/pit-rim` | treeless landing, trunks behind water, hunt bearings refused (#57) |
| `:boxed` | stairs/tunnel/climb has no safe next cell | `stairs/boxed?`, `stairs/box`, `stairs/duck`, `stone/climb`, `stone/deeper`, `stone/mine-next`, `scoop/boxed-here?` | under a lake/aquifer/sea; `:plan/boxed-at`, `duck` (#37, #61) |
| `:no-pickaxe` | dig needs a pickaxe, none held or makeable | `stairs/tool-missing`, `stone/tool-missing`, `intent/opening`, `make/spare-worth?` | spare not made in time, tools worn deep (#36, #39, #67) |
| `:rejected` | server left the block unchanged 3 times on one cell | `intent/dig-stage :confirm`, `place/place-stage :sent`, `place/give-up`, `stairs/rejected` | slow server (`intent/slowdown`), block into a body (#44), protected cell |
| `:timeout` | ran out of time busy but not failing (sim race: 330 simulated min since #252, a real bot's horizon; 90 before); real-race cuts are never filed | `main/result`, `simrace/judged` | back-and-forth loops; `probe story` on the recording |
| `:no-spot` | nowhere to put a block: no spot, niche, passage or fresh rock | `place/place-stage :spot`, `place/niche`, `place/dig-niche`, `place/ashore`, `stone/afloat-no-spot?` | aquifer dead ends, sand round the bot (#94, #148) |
| `:stair-not-taken` | stair cut but feet never reached it in 10 s | `stairs/stairs-stage :step`/`:level`, `wood/climb-out` | unbroken block, mid-jump start (`airborne?`) |
| `:no-shovel` | a staircase block needs a tool not makeable there | `stairs/tool-missing` | snow should be by hand (#42, #72) |
| `:stuck` | walk no progress 60 s; needs planner found no producer (`make/max-depth` 12, cycle); swim-out no obstacle to dig | `intent/run :walk`, `make/decide`, `make/resolve-need`, `swim` `:swim-out` | a wait `:stuck` is the needs planner (missing or cyclic producer); an intent `:stuck` is a walk or swim-out |
| `:stale-window` | third unanswered click in one window intent | `window/lost`, `window/resync` | server load (#131, #132) |
| `:no-site` | no lava site whose pool can finish the frame; lava hunt exhausted | `build/lava-hunt`, `build/site-ok?`, `plan/next-intent :build` | `choose-site`, `spent?` |
| `:no-escape` | swim-out past 2400 ticks: no bank, no floor to dig from | `swim` `:swim-out` | notch/cap rules, `dig-or-sink` |
| `:needs-tool` | no held tool harvests the block; build without a held pickaxe | `intent/choose-tool`, `intent/dig-stage :settle`, `build/held-first`, `stairs/too-hard?` | `:goal/holds` (#70) |
| `:unsafe` | dig would open water/lava (six neighbours or fluid over falling blocks) or obsidian | `intent/dig-stage :settle`, `stairs/refusals`, `stairs/seal` | was `:intent/exposes-ok?` due |
| `:target-gone` | dig/scoop target already open (air, water, lava) or no longer a source | `intent/dig-stage :settle`, `scoop/scoop-stage :aim`, `stairs/fell?` | flowed away, fell; build counts it done (`found-open?`) |
| `:not-picked-up` | collect timed out (10 s) | `intent/run :collect`, `plan/fail-intent` | drop round a corner, in water, behind a wall |
| `:no-water` | no surface water in sight/remembered, no way to cut to any | `scoop/scoop-next`, `scoop/to-bank`, `scoop/climb`, `scoop/dig-out`, `scoop/tunnel-out` | dry landing, deep under landing (#60) |
| `:no-station` | lava pour has no stand reaching a source | `build/refill`, `build/spent?`, `build/work-op` | spent pool (#142) |
| `:no-stand` | no reachable stand for a build op | `build/approach-stand`, `build/reachable-stand` | `nearest-stand-cell`, `toward-stand` (#74) |
| `:no-scoop` | bucket did not fill after 3 uses | `scoop/scoop-stage :sent` | ray blocked by a plant, not a real surface source (#83) |
| `:no-landing` | not standing at the landing within 30 s | `plan/landing-tick` | fixture or server load, not bot logic |
| `:no-ingredient` | craft/smelt could not plan clicks from the inventory | `craft` `:settle`, `smelt` `:settle`, `recipe/sources` | split stacks (#86) |
| `:no-bucket` | lava pour has no empty bucket | `build/refill` | `:portal-kit` counting (#49) |
| `:inventory-full` | no free slot to put the cursor down, and no junk to throw | `craft` `:settle`, `smelt/put-down`, `window/resync` | RESULT `:held`: what filled the inventory; `window/junk` (#172, #192) |
| `:crashed` | the run threw; RESULT has `:error` | `race/play-one!`, `simrace/play!` | read `:error` first |
| `:ledge` | no longer raised (collect over a ledge is done now) | none | closing PR of #93 |

Other build/fortress reasons: `:stray-lava`, `:no-effect`, `:not-yet`, `:unverified`, `:no-support`, `:blocked`, `:no-footing`, `:shallow`, `:tool-broke`, `:out-of-reach`, `:not-held`, `:no-answer`, `:no-window`, `:no-table-window`, `:wrong-result`, `:no-result`, `:not-taken`, `:not-loaded`, `:out-of-fuel`, `:not-cooked` (see section 19 and 20.7).

---

## 23. Tests you cannot run

You never run a test locally; the PR's `unit / unit` runs them all in 2-4 minutes. Read the neighbouring tests first and copy their shape.

| Test namespace | Holds |
|---|---|
| `<ns>_test.clj` | one per source namespace |
| `plan_test.clj` | planner end to end over canned events; scenarios `shaft`, `quarry`, `crevice`, `canopy-pocket`, `sky-shaft` |
| `sim_test.clj` | whole bot against the model in small built worlds; largest; holds the forest property |
| `props_test.clj`, `make_test.clj` | test.check properties (keep-alives, phase, `step` total, needs never pick unmet goals, FINISH never early) |
| `issues_test.clj` | `every-snapshot-fits-on-github`, `an-issue-keeps-two-snapshots-at-most` (judging is the PR check's `--judge`) |
| `recordings_test.clj` | promoted recordings replay with every effect verified |
| `model_test`, `docs_test`, `style_test` | keys in `model.clj`; docstrings; no `if` nested in `if` |
| `packet_test`, `packet_specs_ever.edn` | decoding; `packet-specs-only-grow` |
| `problems_test` | `problems.edn` a row per stage |
| `fixtures.clj`, `world.clj`, `runner.clj` | helpers, `fixtures/instrument!`, the eftest runner |

- Build the smallest world: `clojurecraft.world/column` takes `{[x y z] state}`; states from `(blocks/default-state :stone)`, `blocks/air`, `blocks/water-base`, `blocks/lava-base`. Reuse each test ns's builders (`scoop_test`: `standing`, `pond`, `cave-pool`, `mine`; `build_test`: `open-ground`, `standing-at`, `ops-of`, generators `gen-origin`/`gen-facing`/`gen-height`; `plan_test`: `world-state`, `run`, `remembered`, `walking`).
- A decision function's world needs only the keys its selection `fdef` lists (`spec/requires`). Missing chunks read as solid.
- Name the test as a sentence of the behaviour; top comment names the issue and run; assert the decision (intent kind/target, wait reason, site or nil), not which helper ran. It must fail without the fix: reason it against the old code, or push the test alone first and let CI show it red. Add it to the brain note's `sourceRefs` (`test/clojurecraft/<ns>_test.clj#<name>`).
- **CI's context is not a scoped probe's.** test.yml's forest job runs `clojure -M:test -v <var>` with no `-n`, so every test namespace loads and `fixtures/instrument!` instruments the reducers' fdefs; a probe run with `-n` (or a script) runs uninstrumented and can pass where CI fails. Reproduce a red CI job with the job's exact command (read it from `.github/workflows/test.yml`), not a narrower one.
- The forest job's seed is fresh every push (`FOREST_SEED` = run id + shard, printed on failure); reproduce with that seed.
- Red on something you did not touch: another test asserts the behaviour you changed (update it only if it asserts the old wrong behaviour, and say so in the PR); an older issue's snapshot recurs (your rule conflicts with its fix: read its PR); `model_test`/`docs_test` (missing model row, spec or docstring).
- The sim is deterministic by seed in the same context; a failure that does not reproduce in a probe is usually a context difference (instrumentation, worlds restored, namespaces loaded), not flakiness.

---

## 24. CI map

| Workflow | Trigger | Does | Artifacts |
|---|---|---|---|
| `fleet.yml` | always on (hands over every 340 min), hourly backstop | keeps lanes in every free runner, cancels the newest when another job waits 90 s (floor 2+2), replaces lanes on replaced code | log lines `FLEET ...` |
| `race.yml` | dispatched by the fleet, one lane a run (`race lane k`) | up to 8 lanes × 2 servers × 15 slots on pregenerated landings, refilled, 330 min; `bb churn batch` every 20 min | `race-<sha>-<runner>`: `s1/`,`s2/` with `race.log`, `server.log`, `samples.edn.zst`, `milestones.edn.zst`, a recording per slot |
| `simrace.yml` | same (`simrace lane k`) | up to 8 lanes, bot against the model in captured worlds, every core | `simrace-<sha>-<runner>`: `runs.edn`, `simrace.log`, `snapshots/` |
| `test.yml` | every push, any branch | `unit` (via `unit.yml`), one forest shard of 250 (dispatch: 8), actionlint | none |
| `unit.yml` | called by `test.yml`, `issue.yml` | every test but the forest property (`bb test`), then `bb check` | none |
| `issue.yml` | PR from `fix/*` | `check`: the issues the PR names; `sim` issues must have snapshots; `--judge` on base (must recur) and head (must not), only their worlds restored (15 min cap, a few in practice); plus `unit / unit` | step summary: JUDGE and STORY lines |
| `probe.yml` | hand | one command on a branch with the sim worlds asked for | `probe-<id>`: files under `out/` |
| `gym.yml` | hand | N trials of one goal on real servers, judged by RESULT + truth commands | recordings and logs |
| `world.yml`, `simworld.yml` | hand when a key changes | build a landing set's world / capture sim worlds once | caches |
| `pages.yml` | each report | deploys the results page from `gh-pages` | Pages |
| `plan.yml`, `timer.yml`, `capacity.yml`, `full.yml` | hand or schedule | plan files, capacity study, one fresh-world attempt | their own |

- A push does not judge snapshots; the PR's check does, or a probe with `--judge` before the PR.
- A code merge to `main` cancels and restarts the rounds (they keep artifacts and run a last report).
- Artifacts last 7 days; job logs are readable only when the job ends.
- Required on `main`: `unit / unit` and `check`; `check` "skipping" on a non-`fix/*` PR counts as passing. A PR with merge conflicts runs no checks.
- 20 runners: the fleet holds one, the lanes up to 16, three stay free; anything more waits 90 s, then the newest lane is given back. Queued is not failed.

---

## 25. Hickey patterns

- One value, one writer: only `main` swaps the atom. A fix never adds an atom, a changing `def`, or a side table.
- Reducers compose: `game/step` then `plan/step` (`game/compose`); tests compose them the same way (`plan_test`).
- Effects are data on `:bot/effects`, logging too (`game/say`). Time and randomness are event inputs; deadlines are absolute ms in the world (`:intent/finish-at`) against `:time/now`, or tick counts. Never sleep.
- Open dispatch: `game/on-packet` `[phase name]`, `intent/run` `:intent/kind`, stage multimethods (`intent/dig-stage`, `stairs/stairs-stage`, `scoop/scoop-stage`, `place/place-stage`), `plan/done-by`/`act`/`next-intent`. New behaviour = new `defmethod`, usually in the namespace owning the subject.
- Goals and builds are data; nothing records build progress, the first unsatisfied op is worked. Remember via a fact or a namespaced world key (`:plan/boxed-at`), never a hidden field.
- Rules as small functions whose docstring states the rule and the run that taught it; constants are top-level `def`s with a comment (`(def max-turns 4) ; refusals before a level step, or :boxed`).
- Accretion: supersede goal rows, add attributes beside old ones, append packet fields.
- I/O fenced last (`conn`, `rcon`, `main`, `record`, `watch`, `replay`, `race`, `harness`); a bot fix touches none of them.
