# The loop in full: decisions, reading an issue, evidence, probes, the cause, the fix, committing, the pull request, merging, hand-off (sections 5-14)

Part of the bot-failure manual; [AGENTS.md](../../AGENTS.md) is its entry and index. Read only the section a question needs.

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
| The sim and a real run disagree | Real-only signature: the real run is the evidence, and its real snapshots (2.3) are judgeable evidence of it. Sim-only: check the model's limits first (16.6, 16.7). |
| An issue's snapshots do not recur on current `main` | Take the newest batch's (they recurred on that batch's commit). None recur: the failure may be fixed already; say `Refs #n` and move on: the filer closes it after 7 days unreproduced, or reopens it with fresh snapshots. |
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

**Snapshots (sim issues, and real ones the model reproduces).** `bb issue-check fetch <n>` reads the issue's comments newest first, then its body, and copies at most two snapshots into `test/issues/<n>/`, one a world (two seeds of one world are one moment), only snapshots with a kept moment, each under its file name; it prints what it took and what it passed over. Each comes from the evidence release its line names (`gh release download evidence-<day>-<hour> --repo BridgerB/clojurecraft -p <run>-<file>` by hand), kept for good; a comment from before the releases names a runner's artifact instead, which fetch still reads while it lasts (7 days; `gh run download <run> -n simrace-<sha>-<runner> -D /tmp/ev/<n>/r<runner>` by hand). A real snapshot (`real-<runner>-<s>-<bot>.edn.gz`) is its own world.

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

`clojure -M:simrace --judge probe/<n>/R4-35-2.edn.gz[,...]` judges as the check does: from the snapshot's moment over its window (`:snapshot/moment :window-ms`, from the moment to a minute past the stop, or fifteen minutes for a stall, `simrace/stall-judge-ms`), uninstrumented, stopping when the plan ends or reaches the signature's stage. It prints one JUDGE line a snapshot (signature, moment, window, `:recurs?`, how it ended, simulated and wall ms) and a STORY line per intention fact from the moment on: what the bot did. `--expect recur|pass` sets the exit code. Run it on `main` and on the fix; both go in the PR. `simrace/judged` returns the RESULT, `simrace/recurs?` whether the failure recurs, `simrace/moment-of` the moment as `sim/play-from` takes it. `--from-landing f` replays the snapshot's whole run from its landing to its stage (up to 330 simulated minutes): a probe's view of the road, never the check's. To see what the bot does step by step, step it a quarter second at a time and print what changed:

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

Convert before committing: `zstd -dc Clj_r08.edn.zst | gzip -c > probe/<n>/Clj_r08.edn.gz` (or commit the `.zst`: the JVM reads it through `zstd`). To make a real snapshot of an older recording (the round's batch kept none, or its artifact expired), fold it at its commit the same way with `clojure -M:simrace --from-recording probe/<n>/Clj_r08.edn.zst --out out/real`, the conversion's files laid over that commit when it predates them (`git worktree add ../rec FETCH_HEAD`, copy `real.clj`, `sim.clj`, `simrace.clj` and `record.clj` from `main` into it, run there: none of them is the bot's reducer); a `REAL` line means it recurs there, a `GAP` line how it got past. In the script, `(record/replay main/step (game/init {:host "replay" :port 0 :name "Clj_replay"}) path)` is the bot's final world; `clojurecraft.probe` (`dev/`) has `fold`, `at-ms`, `story` (every intent's start, end and reason) and `around`.

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

Open it when the change is ready (step 10), never as a draft, from your branch:

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
- `check` (`issue.yml`): only the issues the PR names (its branch `fix/<n>` and its `Fixes #n` lines). Every issue the body fixes that is labelled `sim` must have snapshots under `test/issues/<n>/` or it fails at once (`issue <n> is reproduced in the sim (label sim) but test/issues/<n> holds no snapshot`) (every issue filed since #299 is; one filed before it with only real runs is only warned about). Only the snapshots the PR itself adds or changes are judged (a snapshot already on main proved an earlier fix; a fix/<n>-r2 for a regression brings its own or says Refs). Then, restoring only the captured worlds those snapshots name, `clojure -M:simrace --judge` judges them on the base commit (each must recur: a moment that does not reproduce its failure proves nothing) and on the PR's code (none may), in parallel, and writes both, JUDGE and STORY lines, to the step summary. Limit 15 minutes; a few minutes in practice. Older issues are not judged again: the races are the regression guard (the filer reopens an issue whose failure comes back).

Two snapshots an issue at most, from two landings when it has them, each under GitHub's 100 MiB file limit.

**A red check:** `gh pr checks <pr>`, then `gh run view <id> --log-failed`, read it whole, fix, push; the checks rerun. Typical: a test asserting the old behaviour; a new key without its model row or spec; a failure that still recurs on the PR's code (the moment, 9.2; read its STORY lines); a moment that does not recur on the base (fetch another, or take one from the newest comment); a sim issue without snapshots; "no checks reported" because the PR conflicts (section 13). A failure outside the code (a lost runner, a cache miss): `gh run rerun <id>`.

---

## 13. Merging and after

```sh
gh pr view <pr> --json mergeStateStatus --jq .mergeStateStatus      # CLEAN, BLOCKED, BEHIND, DIRTY
git fetch origin && git merge --no-edit origin/main && git push      # when DIRTY or BEHIND
```

`DIRTY` runs no checks until resolved. The usual conflict is `problems.edn`, where two fixes appended to the same `:problem/last-failure` line: keep both sentences.

Green and the body has the before/after: `gh pr merge <pr> --merge` (merge commits, never squash, never `--admin`). Confirm `gh issue view <n> --json state` reads CLOSED for each issue.

The merge restarts the rounds on your code; their reports 20 to 30 minutes later are the real test (a recurrence on newer code reopens the issue as regressed). Delete your probe branches; keep the fix branch.

---

## 14. Handing off

Whenever you stop before the merge (your session ends, you are told to stop, the cause is in tooling, two probes in a row contradict each other), the next agent must be able to start cold. Commit and push everything worth keeping to your branch (`WIP:` at the start of the subject if `bb check` fails, saying what fails in the body; probe commits stay on probe branches), stop your watchers, and post one comment on the issue:

```
Hand-off: issue #<n>, branch fix/<n> @ <sha>[, PR #<pr>]

Change: <what it does, one or two sentences>.
Cause: <as understood>; confidence <high/medium/low>.
Runs: <run id> — <what it showed>; <run id> — <what it showed>; ...
Unproven or failing: <exactly what, with the exact error>.
Next step: <the one you would have taken>.
```

Leave the branch and any open PR as they are; never a draft. The branch stays, and whoever is named next continues it (section 5's hand-off row). Take the `no-agent` label off an issue you were named for, so the orchestrator can give it to Copilot.

---
