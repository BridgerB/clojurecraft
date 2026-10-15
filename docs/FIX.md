# FIX.md: keeping the bot-failure issues down

This is the working manual for fixing the bot's failures in this repository. It is written for any coding agent or person who picks the work up cold: you may not know Clojure well, this repo, or the tools that were used before you. Follow it in order. Everything here was done for real, many times, on 2026-10-14; the commands are the ones that worked, and the catches are the ones that cost time.

The short version: the rounds on GitHub's runners play the bot without end and file every way it fails as an issue labelled `bot-failure`. You take the oldest such issue off the project board, find out why the bot stopped, change the bot so it does not, prove it with the evidence the issue carries, and merge. Then the next one.

## Contents

1. Hard rules
2. Orientation: what to read first
3. How failures become issues
4. The board
5. The loop, step by step
6. Reading an issue
7. Getting the evidence
8. Asking the code questions: `probe.yml`
9. Finding the cause
10. Writing the fix
11. Committing
12. The pull request and its checks
13. Merging and after
14. Failures seen and how they were fixed
15. Catches
16. Quick reference
17. A worked example: issue #142, start to finish
18. When you are stuck

---

## 1. Hard rules

These are absolute. Each has a reason; the reason is not an invitation to argue an exception.

### 1.1 No attribution, anywhere, ever

Never write who or what did the work. No `Co-Authored-By` trailer. No "Generated with ..." line. No agent, model, assistant or provider name of any kind (none: not the one you are, not any other). No session links, no tool links, no signatures, no "as an AI". This holds in commit messages, PR titles and bodies, issue comments, board items, branch names, code, comments in code, docs (this one included) and test names.

Why: the repository is the owner's; the history reads as one author's work. The commit-msg hook (`scripts/check-commit-msg`, run by lefthook) refuses some of these patterns mechanically, but the rule is wider than the hook. Before every `git commit` and every `gh pr create`, reread the message and the body and remove anything that names an agent. If your tool adds a trailer by default, turn it off or strip it before the command runs.

### 1.2 No claims in comments

Never comment on an issue, a PR or the board to say who took what, who is working on it, or that you are "on it". The board status and the branch name are the whole claim (section 4).

Why: several agents work the same queue at once. Comments that say who took what are noise the owner has to read; the board already says it.

### 1.3 No compute on the local machine

The machine you run on is the owner's laptop. Locally you may only: edit files, run `git` and `gh`, read files and downloaded logs and artifacts, and run the quick static checks: `bb fmt`, `bb check`, and the commit hooks. That is all.

Never locally: any test (not even one test var), any snapshot judged or played on, any replay of a recording, any probe or scratch script that loads the bot's code (`clojure -M:dev`, `-M:test`, `-M:simrace`, `-M:replay`, `-M:run`, `bb test`, `bb ci`, `bb replay`, `bb gym`), any sim, gym or race of any length, and never a local Minecraft server.

Why: GitHub's runners have the compute and the captured worlds; the laptop must stay usable, and a "quick" local run is how hours of heat and battery went before this rule. Everything that runs code goes through a pull request's checks or `probe.yml` (section 8). This is not a guideline to weigh against convenience.

### 1.4 Never touch the shared game box

Never connect to, SSH into, or send RCON to `144.24.32.76`, and never use the ports 25565-25570 or 25575-25580. Do not start Minecraft servers locally either.

Why: that box is shared with other projects; an accidental world reset or server restart destroys their work. The runners bring up their own servers.

### 1.5 main is protected by habit as much as by settings

Never push to `main`. Never force-push a branch someone else pushed. Never `--admin` merge, never bypass or disable a required check, never weaken a test or the issue check to make a PR green.

Why: concurrent agents merge into `main` all day; one bad push stops all of them, and the rounds restart on every merge.

### 1.6 Never merge what does not fix what it says

A PR says `Fixes #n` only when its evidence shows the issue's reproductions no longer stop with that signature. If the evidence is not there, say so plainly in the PR body and leave it open (or change `Fixes` to `Refs` and say why).

Why: `Fixes #n` closes the issue on merge. A false close hides a live failure until the rounds reopen it as "regressed", which costs a full cycle.

### 1.7 Never use `--no-verify`

Commit through the hooks every time. If a hook fails, fix what it says (section 11).

---

## 2. Orientation: what to read first

Read these before your first fix, in this order. Skim the second time.

1. This file.
2. `docs/hickey.md`: the style the code follows. The bot is a pure reducer over a world value; facts are data; nothing is predicted; time is an input. A fix that fights this style will be wrong in ways the tests do not catch.
3. `CLAUDE.md` at the repo root: the repository map, the commands, the CI, the rules. Despite its file name it applies to every agent and person. Where it and this file disagree on process, this file is newer for the fix loop.
4. `docs/CHURN.md`: the older description of finding and fixing failures on the runners. Its "run locally" advice is superseded by rule 1.3.
5. `brain/`: the notes, one per subject, each pinned to the source by `sourceRefs`. Before touching a namespace, read its note (`grep -rl '<ns-or-fn-name>' brain/`). The notes record why things are the way they are, with the run that taught it.
6. `resources/clojurecraft/problems.edn`: one row per stage (wood, kit, pickaxe, ..., portal, nether, fortress) with the statement, needs, risks, what done means and `:problem/last-failure`, a running record of what failed last at that stage and what was done about it. Every fix appends a sentence there (section 10.5).

### 2.1 The bot in one page

- `src/clojurecraft/game.clj` folds events (`:tick`, `:packet`, `:go`, ...) into the world value: positions, chunks, inventory, windows. `game/step` is the reducer.
- `src/clojurecraft/plan.clj` is the planner: a table of goals (`resources/clojurecraft/goals.edn`), each with an act (`plan/act`, `plan/next-intent`) that looks at the world and returns the next intent, or `{:plan/wait reason}`. `plan/wait` fails the plan after `wait-timeout` (20 s) with that reason. An intent that fails counts an attempt; `max-attempts` (3) failures in a row fail the plan with the intent's reason. A finished intent resets the count.
- Intents are maps `{:intent/kind :dig ...}` run one tick at a time by `intent/run` methods (`intent.clj`, `stairs.clj`, `scoop.clj`, `place.clj`, `build.clj`, `craft.clj`, `smelt.clj`, ...). An intent can run a child intent (`intent/run-child`).
- The run's outcome is a RESULT map; `failure/signature` turns a failed one into `[target reason]`, e.g. `[:ingots :boxed]`. That pair is an issue.
- `src/clojurecraft/sim.clj` is the server model: the same packets a 26.1.2 server sends, over captured worlds. The sim race plays the real bot code against it.
- `src/clojurecraft/model.clj` and `src/clojurecraft/spec.clj` list every key the world and intents may hold. A new key needs a row in `model.clj` and a spec in `spec.clj`, or the docs and spec tests fail.

### 2.2 Tools you need

- `git`, `gh` (signed in as the owner; `gh auth status`), `zstd` and `gzip` (to read recordings), `jq` (optional).
- Babashka (`bb`) and the Clojure CLI for `bb fmt` and `bb check`. If they are not installed, `nix shell nixpkgs#babashka nixpkgs#clojure -c bb check` works on a machine with nix. The hooks run through lefthook; run `git commit` inside the same shell (for example `nix shell nixpkgs#babashka nixpkgs#clojure -c git commit -F msg.txt`).

---

## 3. How failures become issues

You need this to read an issue correctly.

### 3.1 The rounds

Two workflows play the bot without end on the latest `main`:

- `race.yml`, the real race: eight runners, each with Minecraft servers (26.1.2) on a pregenerated landing set (R1-R4), each server kept full of bots (25 slots a server; a bot that ends is replaced at once). Real servers lag under that load (see 14.1).
- `simrace.yml`, the sim race: eight runners playing the bot against the model (`sim.clj`) in captured worlds (`cache/sim-worlds/R1..R4/<n>.edn.gz`, one per landing), seed after seed.

Every merge to `main` (except docs-only changes under `brain/`, `docs/` and `*.md`) cancels the running rounds and starts new ones on the new code. Rounds since commit 526762e keep their artifacts and their last report through a cancel.

### 3.2 Batches and reports

Every 20 minutes per runner (staggered by runner), each runner reports the runs that ended since its last report (`bb churn batch`, then `bb failures report`):

- Every failed run gets its signature `[target reason]`. Runs that stopped for infrastructure reasons (the server closed the socket, a disconnect) are counted but not filed.
- One issue per signature, labelled `bot-failure`, plus `sim`, `real` or both by where its runs came from. The issue body carries a hidden marker with the signature and a tally: rounds, runs, first and last seen.
- Each report adds one comment to the signature's issue: how many runs, the last intents they stopped in, how many landings, up to five reproductions, and the run link.
- A signature fewer than 3 runs hit in a report is a one-off: listed in the job output, not filed.
- A closed issue whose signature comes back in runs of code committed after the issue was closed is reopened, and its comment says "(regressed: this issue was closed)". Runs of older code (a round that started before the fix merged) add a comment but leave the issue closed.
- The results also go to the results page, https://bridgerb.github.io/clojurecraft/ (built from the `gh-pages` branch by `pages.yml`).

### 3.3 What a reproduction line looks like

Sim (the model produced it):

```
- snapshot `R4-35-2.edn.gz` in artifact `simrace-0d036cb5df3b3c109caf86ae3503a52f5d424760-6`; or `clojure -M:simrace --world cache/sim-worlds/R4/35.edn.gz --seed 2 --seeds 1` at 0d036cb5df3b3c109caf86ae3503a52f5d424760, landing [2360 3640 103]
```

- The snapshot is the world and the model's state one tick before the run stopped, a value you can play on (section 8.4).
- The artifact `simrace-<sha>-<runner>` holds that runner's `runs.edn` (one run map a line, with `:run/signature`, `:pos`, `:held`, `:plan`, `:run/ms`), `simrace.log` and `snapshots/`.
- The command reruns the whole run from spawn with the commit's code; it takes up to the sim race's 90-minute budget in simulated time.

Real (a real server):

```
- real race: bot Clj_r08, replay its recording with `clojure -M:replay <artifact>/Clj_r08.edn`
```

- The artifact `race-<sha>-<runner>` holds `s1/` and `s2/` (one per server on the runner), each with `race.log`, `server.log`, `samples.edn.zst`, `milestones.edn.zst` and one recording per bot slot, `Clj_rNN.edn.zst`. Bot names are slots: a slot is refilled when its bot ends, so the same name may hold several bots in one round, and the same slot fails in round after round when its landing is the problem.
- The report names the round run; take its runner number from the comment's heading ("Race 38665691517 runner 1 batch 1 at 0d036cb").

---

## 4. The board

The queue is a GitHub Projects board: https://github.com/users/BridgerB/projects/10, owner `BridgerB`, project number 10, with a single-select field `Status` whose options are `Todo`, `In Progress` and `Done` (mind the capital P: option names are matched exactly). The board holds pull requests too; only `Issue` items are work.

The token needs the `project` scope. Without it every `gh project` command fails with `missing required scopes [read:project]`. Add it once:

```sh
gh auth refresh -s project
gh auth status            # Token scopes should now include 'project'
```

### 4.1 Look the ids up at run time

Never hard-code the project id, the field id or the option ids; look them up each session.

```sh
# the project's node id
PROJECT_ID=$(gh project view 10 --owner BridgerB --format json --jq '.id')

# the Status field and its options
gh project field-list 10 --owner BridgerB --format json \
  --jq '.fields[] | select(.name == "Status") | {id, options}'
STATUS_FIELD=$(gh project field-list 10 --owner BridgerB --format json \
  --jq '.fields[] | select(.name == "Status") | .id')
TODO=$(gh project field-list 10 --owner BridgerB --format json \
  --jq '.fields[] | select(.name == "Status") | .options[] | select(.name == "Todo") | .id')
IN_PROGRESS=$(gh project field-list 10 --owner BridgerB --format json \
  --jq '.fields[] | select(.name == "Status") | .options[] | select(.name == "In Progress") | .id')
DONE=$(gh project field-list 10 --owner BridgerB --format json \
  --jq '.fields[] | select(.name == "Status") | .options[] | select(.name == "Done") | .id')
```

### 4.2 List the items

```sh
gh project item-list 10 --owner BridgerB --format json --limit 500 > /tmp/board.json
# Todo bot-failure issues, oldest first, with their item ids and labels
jq -r '.items[] | select(.status == "Todo" and .content.type == "Issue")
       | select((.labels // []) | index("bot-failure"))
       | [.content.number, .id, ((.labels // []) | join(",")), .content.title] | @tsv' /tmp/board.json | sort -n
```

Each item carries `id` (the item id `item-edit` needs), `status`, `title`, `labels` (absent when the issue has none) and `content` (`type`, `number`, `url`). The issue number is a good proxy for age (issues are numbered in creation order). Confirm a candidate's state and labels from the issue itself before claiming:

```sh
gh issue view <n> --json labels,state,title --jq '[.state, .title, ([.labels[].name] | join(","))] | @tsv'
```

### 4.3 Which items you may take

- Only items with Status `Todo`.
- Only issues labelled `bot-failure` (with `sim`, `real` or both).
- Never an item `In Progress`: another agent owns it, even if it looks stale (see 15.11).
- Never issue #10 (the roadmap, on hold) and never anything without `bot-failure` (tooling issues belong to whoever owns the tooling).

### 4.4 Moving an item

These commands were verified against the live board (2026-10-14): the project id has the form `PVT_...`, the Status field `PVTSSF_...`, an item `PVTI_...`, an option a short hex id.

```sh
gh project item-edit --id <item-id> --project-id "$PROJECT_ID" \
  --field-id "$STATUS_FIELD" --single-select-option-id "$IN_PROGRESS"
```

Re-read the board after every move to confirm it held (another agent may have moved it at the same moment).

---

## 5. The loop, step by step

This is the spine of the work. Do it the same way every time.

### Step 1. Read

Read this file, `docs/hickey.md` and `CLAUDE.md` (section 2). After the first time, reread section 15 (catches) at the start of each session.

### Step 2. Open the board

Look up the ids and list the items (section 4).

### Step 3. Filter

Keep only `Todo` items whose issue is open and labelled `bot-failure`. Ignore everything else (4.3).

### Step 4. Choose

Take the oldest Todo bot-failure issue: the lowest number. The lower stages are worked out first, so that later work lands on the higher stages the bot reaches once the early ones hold.

Then look for other Todo issues that clearly share its root cause, and take them together in one PR:

- the same reason family across stages: `rejected` at ingots, cobblestone and stone-pickaxe was one cause (a slow server, 14.8); `stale-window` at ingots and portal-kit was one cause (14.1);
- the same stage and a reason that is a symptom of the same thing (read the comments' last intents and positions).

When unsure, take one issue. One PR per root cause: two causes in one PR make the evidence ambiguous and the revert impossible.

### Step 5. Claim

In this order, and stop at the first sign that someone else got there first.

a. Re-read the board and confirm each chosen item is still `Todo`.

b. Check no branch exists for it, then create the branch from the latest `origin/main` and push it at once. The push is the lock.

```sh
git fetch origin
git ls-remote --heads origin 'fix/<n>-*'          # must print nothing
git switch -c fix/<n>-<slug> origin/main
git push -u origin fix/<n>-<slug>
```

The slug is a few words of the cause or the signature (`fix/142-no-station`, `fix/88-rejected`). If `ls-remote` prints a branch, or the push is rejected because it exists, another agent has it: pick something else.

c. Move each chosen item to `In Progress` (4.4), then re-read to confirm the move held.

No comment, no assignee, no label, no name: the board status and the branch are the whole claim (rule 1.2).

### Step 6. Work the fix

Sections 6 to 10: read the issue and every comment, fetch the evidence, ask the code questions on a runner, find the cause, write the fix and a test, update the brain note(s) and the stage's `problems.edn` row in the same change.

### Step 7. Local checks only

```sh
bb fmt          # format with zprint
bb check        # format check, lint (clj-kondo), idioms (Splint), ns forms, unused vars, the brain checker
```

Nothing else locally (rule 1.3).

### Step 8. Commit, push, open the PR at once

Section 11 for the commit, section 12 for the PR. Open the PR as soon as the first commit is pushed (as a draft if the evidence is not in yet): its checks are your test run.

### Step 9. Watch the checks without blocking

```sh
gh pr checks <pr> --watch --interval 30 > /tmp/checks-<pr>.txt 2>&1 &
```

Keep working (the next issue's reading, a probe) while it runs. A red check: read its log, fix, push another commit to the same branch, repeat until green (12.3). Never rerun anything locally "to confirm".

### Step 10. Code questions go to `probe.yml`

Section 8.

### Step 11. Merge

Green, and the PR body carries the before/after evidence: merge normally (13.1). The `Fixes #n` lines close the issues. Move their board items to `Done` if the board does not do it on its own.

### Step 12. Next

Back to step 2.

---

## 6. Reading an issue

```sh
gh issue view <n> --json title,state,labels,body --jq '.title, .state, ([.labels[].name] | join(",")), .body'
gh issue view <n> --json comments --jq '.comments[] | .createdAt + "\n" + .body' | less
```

Read all of it: the body and every comment. What to take from it:

- The signature, in the title and the hidden marker: `[:wood :no-log]` means the plan's target was `:wood` and it stopped with reason `:no-log`.
- The tally: how many rounds and runs, first and last seen. A signature with hundreds of runs over many rounds is a wall; one that appeared only in the last round after a merge may be a regression from that merge.
- Each comment's heading: real or sim, the round's run id, runner, batch and commit. Prefer the most recent comments: their artifacts exist, and their code is closest to `main`.
- "From: {:sim 3}; last intents: {nil 3, :walk 2}": which intents the runs stopped in. `nil` means no intent was running: the plan was waiting (`plan/wait`), and the reason is the wait's.
- "landings: 2": how many distinct landings. Several landings make a pattern; one landing may be a property of that place (or of the captured world's edge, 15.9).
- "(regressed: this issue was closed)": a fix did not hold, or a later change undid it. Read the closing PR first.
- Older comments' reproductions point at commits long gone and at artifacts that may have expired; do not start there.

Then read the code that stops with that reason:

```sh
grep -rn ':no-log' src/clojurecraft/          # who raises it
grep -rln 'no-log' brain/                     # the notes that explain it
```

---

## 7. Getting the evidence

### 7.1 Snapshots (sim issues)

```sh
bb issue-check fetch <n>          # brings the issue's snapshots into test/issues/<n>/
```

`fetch` reads the issue's reproduction lines, downloads each named artifact once and copies the named snapshots. If an artifact is gone (expired, or a round cancelled before 526762e kept its artifacts), fetch what you can from the newest comments instead, by hand:

```sh
mkdir -p /tmp/ev/<n> && cd /tmp/ev/<n>
gh run download <run-id> -n simrace-<sha>-<runner> -D r<runner>
ls r<runner>/snapshots/
```

### 7.2 Which runs failed how: `runs.edn`

`runs.edn` holds one run map a line. Pull out the failures with a quick text tool; for example, every run with a given reason (plain `grep` and shell, no bot code):

```sh
grep ':run/signature \[:water-bucket :boxed\]' r4/runs.edn \
  | grep -o ':run/file "[^"]*"\|:run/seed [0-9]*\|:run/ms [0-9]*\|:pos \[[^]]*\]' | paste - - - -
```

`:pos`, `:held` (inventory), `:plan` (with `:plan/boxed-at`, `:plan/reached-after`, `:plan/last`, `:plan/intent`) tell a lot before any code runs.

### 7.3 Real races: logs and recordings

```sh
gh api repos/BridgerB/clojurecraft/actions/runs/<run-id>/artifacts --jq '.artifacts[] | [.name, .size_in_bytes] | @tsv'
gh run download <run-id> -n race-<sha>-<runner> -D /tmp/ev/race     # 300-500 MB a runner
```

- `s1/race.log` and `s2/race.log`: every bot's `RESULT {...}` line (grep `^RESULT`), the `BOARD` lines and the server's tick-time reports (`BOARD The game is running, but can't keep up ... Average time per tick: 130ms`).
- `samples.edn.zst`: periodic samples per runner, including `:sample/server-tick` (`{:avg :p50 :p95 :p99}` in ms) and the bots' own tick rate.
- `Clj_rNN.edn.zst`: the bot's recording, every event and effect. Its log lines (the bot's `game/say` messages) are plain strings inside it:

```sh
zstd -dc s2/Clj_r08.edn.zst | grep -o '"[^"]\{3,200\}"' | grep -v '^"[0-9a-f]\{8\}-' | awk '!seen[$0]++' | tail -40
```

Count outcomes across a runner:

```sh
grep -h '^RESULT' s*/race.log | grep -o ':reason :[a-z-]*' | sort | uniq -c | sort -rn
```

To replay a recording you need code to run: do it on a runner (8.5).

### 7.4 What not to trust

- Bot names: one slot, many bots (3.3).
- A RESULT's `:pos` is where it ended, not where it went wrong.
- A sim run's `:pos` at the edge of its captured world (the next chunk `:unknown`) is the model's limit, not the bot's (15.9).

---

## 8. Asking the code questions: `probe.yml`

Anything that needs the bot's code run goes to the `probe` workflow, on a runner, against a branch you pushed.

```sh
gh workflow run probe.yml --ref <branch> \
  -f run='clojure -M:dev probe/p<n>.clj probe/<n>/R4-35-2.edn.gz' \
  -f worlds=R1,R4 \
  -f label=p<n>
sleep 8
ID=$(gh run list --workflow probe.yml --limit 1 --json databaseId --jq '.[0].databaseId')
gh run watch "$ID" --interval 30 > /dev/null 2>&1 &         # in the background
gh run view "$ID" --log | grep -E '<what you print>'          # when it is done
gh run download "$ID" -n probe-"$ID" -D /tmp/probe-"$ID"      # anything written under out/
```

- `run` is one shell command, run from the checkout root with Java, the Clojure CLI and Babashka installed.
- `worlds` restores the captured sim worlds a snapshot or a simrace run needs (`R1,R2,R3,R4`; none by default). A snapshot names its world file (`cache/sim-worlds/R4/35.edn.gz`); restore that set.
- The script and any inputs (snapshots, recordings) must be committed and pushed on the branch before you dispatch: the runner checks out the branch, nothing else.
- Files written under `out/` come back as the artifact `probe-<id>`.

### 8.1 Where to put probe files

Never on the fix branch: a probe script is not part of the fix. Make a throwaway branch from the fix branch (to probe the fix) or from `main` (to probe the failure), put the script under `probe/` and the inputs under `probe/<n>/`, commit (through the hooks: they lint the script too, see 15.13) and push:

```sh
git switch -c probe/<n> origin/fix/<n>-<slug>       # or origin/main
mkdir -p probe/<n> && cp /tmp/ev/<n>/r4/snapshots/R4-35-2.edn.gz probe/<n>/
$EDITOR probe/p<n>.clj
git add probe && git commit -m "probe: issue <n>" && git push -u origin probe/<n>
```

Delete probe branches when you are done (`git push origin --delete probe/<n>`).

### 8.2 A probe script's skeleton

A probe is a Clojure script run with `clojure -M:dev <script> <args>`. Print plain lines; write them to `out/probe.log` too.

```clojure
(require '[clojurecraft.capture :as capture]
         '[clojurecraft.simrace :as simrace]
         '[clojurecraft.intent :as intent]
         '[clojure.java.io :as io]
         '[clojure.string :as str])
(.mkdirs (io/file "out"))
(defn out [& xs]
  (let [s (str/join " " xs)] (println s) (spit "out/probe.log" (str s "\n") :append true)))
(doseq [p *command-line-args*]
  (let [snap (capture/read-world p)
        w (simrace/prepared (capture/read-world (:snapshot/file snap)))
        [world sim] (simrace/moment snap w)]
    (out "==" p (:snapshot/signature snap))
    (out " feet" (intent/feet world) "intent" (:plan/intent world) "last" (:plan/last world))
    (out " boxed-at" (:plan/boxed-at world) "attempts" (:plan/attempts world))))
(shutdown-agents)
```

`simrace/moment` gives the world and the model at the snapshot's tick. From there call the act or the helper you suspect directly, with the world as its argument: everything is a pure function of the world value, so `(scoop/scoop-next world)`, `(stone/deeper world)`, `(build/station world origin facing h)` answer at that moment exactly as the bot did.

### 8.3 Looking at the terrain

A text map of the cells round the feet answers most "why can't it get out" questions:

```clojure
(require '[clojurecraft.path :as path])
(let [[fx fy fz] (intent/feet world) r 8]
  (doseq [y (range (+ fy 4) (- fy 3) -1)]
    (out "y" y)
    (doseq [z (range (- fz r) (inc (+ fz r)))]
      (out (apply str (for [x (range (- fx r) (inc (+ fx r)))]
                        (if (= [x y z] [fx fy fz]) "@"
                            (case (path/classify world [x y z])
                              :solid "#" :water "W" :clear "." :unknown "?" :lava "L" "~"))))))))
```

`~` is something else (`:awkward`: a slab, a lily pad, ice reads as solid). Name a block with `(blocks/name-of (terrain/block-at world [x y z]))`.

### 8.4 Playing a snapshot on

`simrace/play-snapshot` plays the snapshot's moment on for two simulated minutes with the code of the branch the probe runs on, and returns the RESULT; `simrace/recurs?` says whether the snapshot's signature recurs. `simrace/judged` is what the issue check uses (it replays a `:timeout` snapshot from its landing, 15.6). The command-line form:

```sh
clojure -M:simrace --snapshot probe/<n>/R4-35-2.edn.gz      # prints one SNAPSHOT line
```

Run it on `main` (before) and on the fix (after); both lines go in the PR body.

To see what the bot does between the moment and the stop, step it a quarter second at a time and print what changed:

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

A recording replays exactly only with the code that made it. On a runner, check out that commit's source before running your script from a branch based on `main` (which has `probe.yml`):

```sh
gh workflow run probe.yml --ref probe/<n> -f label=p<n> \
  -f run='git fetch -q --depth 1 origin <sha> && git checkout FETCH_HEAD -- src resources deps.edn && clojure -M:dev probe/p<n>.clj probe/<n>/Clj_r08.edn.gz'
```

Recordings are `.edn.zst`; the reader takes plain or `.gz`. Convert before committing: `zstd -dc Clj_r08.edn.zst | gzip -c > probe/<n>/Clj_r08.edn.gz`. In the script:

```clojure
(require '[clojurecraft.record :as record] '[clojurecraft.main :as main] '[clojurecraft.game :as game])
(let [w (record/replay main/step (game/init {:host "replay" :port 0 :name "Clj_replay"}) path)]
  ...)   ; w is the bot's final world: ask it what each act would do
```

### 8.6 A full run from spawn

When the snapshot moment is too late to show the cause (15.5), run the whole reproduction on a runner with the fix and with `main`:

```sh
-f run='clojure -M:simrace --world cache/sim-worlds/R4/1.edn.gz --seed 1 --seeds 1' -f worlds=R4
```

It prints one SIMRUN line with the RESULT. A run to a late stage takes many minutes of runner time; that is fine (it is not your machine).

---

## 9. Finding the cause

### 9.1 The questions to answer, in order

1. What was the bot trying to do when it stopped? The plan's target (the signature's first half), the running intent and its child, or the wait reason.
2. What did the world look like there? Position, inventory, the cells round it (8.3), what it remembered (`:plan/boxed-at`, `:plan/blacklist`, memory).
3. Which function returned the value that led to the stop? Call each candidate at the moment (8.2) and print its answer. For a wait, the act returned `{:plan/wait r}`: find which branch of its `or` chain produced it and why every earlier branch returned nil.
4. Why did that function answer so? Usually one condition: a filter that excludes everything, a budget too small, a check that reads a block wrongly, a missing case.
5. Is it the bot or the model? A sim-only signature may be the model's limit (15.9). A real-only one may be load (14.1, 14.8).

### 9.2 The moment problem

A snapshot is the tick before the stop: by then the cause is usually long past (a site chosen beside a lone lava source minutes earlier; a causeway laid 75 minutes earlier), and the failing decision is often already made (a child intent already failed, two attempts already counted). A fix for the cause alone does not change that moment, so the issue check still sees the signature recur.

Fix both, in this order: (1) the cause, so new runs never get there, and (2) the state at the moment, so a bot that is already there recovers instead of failing. The second half is usually a rule that turns a failure into a re-plan: a spent site is left (14.4), a boxed climb ends instead of failing (14.6), a placement refused for the bot's own body is approached again (14.9). It must not loop: when a failure stops counting attempts, make sure the act cannot choose the same thing from the same place (record it, as `:plan/boxed-at` does).

### 9.3 Read the history before inventing

Many walls have been hit before. Before writing a new mechanism, grep the brain and the code for the reason and the situation (`grep -rn 'boxed' brain/bot/plan/`); the existing rules usually need one more case, not a new subsystem. The docstrings carry the run that taught each rule; a fix that undoes one of them brings that failure back.

---

## 10. Writing the fix

### 10.1 Style (`docs/hickey.md`)

- Functions of the world value. An act returns an intent or a wait; it never mutates. A rule that needs state reads it from the world (`:plan/boxed-at`, memory facts) or adds it there through the intent's own reducer.
- Facts are data: a new thing the bot learns is a key on the world (with a `model.clj` row and a spec) or a fact in memory, never a hidden atom.
- Nothing predicted: the world changes when the server says so. The overlay of predicted blocks exists for a few cases; do not add more.
- Time is an input: deadlines are `(:time/now world)` plus a constant, never wall-clock calls.
- Accretion: add keys and arities, do not rename or change the meaning of existing ones. Packet specs only grow (`packet-specs-only-grow`: a new spec goes into `test/clojurecraft/packet_specs_ever.edn` too).

### 10.2 Docstrings carry the evidence

Every rule's docstring says what it does and which run taught it: "Sim race 38664661309 (issue #142, 89 runs in 18 rounds): five worlds chose a site beside a lone lava source, cast one cell from it and failed :no-station with nine to go." Keep the existing evidence when you change a function; add yours.

### 10.3 Tests

- A unit test of the decision: build the smallest world that has the situation (the test namespaces have builders: `standing`, `open-ground`, `standing-at`, `deep-in`, `world/column` with a map of `[x y z] state`), call the function, assert its answer. Name the test for the behaviour (`a-lone-lava-source-is-no-site`), and put the issue and run in a comment at its top.
- Make it fail without the fix. You cannot run it locally (rule 1.3); reason it through, and if in doubt push the test before the fix and let the PR's `unit` check show it red, then push the fix.
- When you change a function's behaviour, other tests may assert the old behaviour. Search for the function's name in `test/` before pushing, read those tests, and update or keep them deliberately. The `unit` check shows what you missed.
- The issue's snapshots under `test/issues/<n>/` are the issue check's fixtures (12.2).

### 10.4 New keys and packets

- A new world or intent key: a row in `src/clojurecraft/model.clj` (key, meaning, when present) and a spec in `src/clojurecraft/spec.clj`.
- A newly decoded packet: its spec in `packet.clj`'s `specs`, the same entry in `test/clojurecraft/packet_specs_ever.edn`, and check `packet_test.clj` does not use that packet as its example of an unmodelled one.

### 10.5 The brain note and `problems.edn`, in the same change

- The note: find it (`grep -rln '<fn-name>' brain/`), add the new function to its `sourceRefs` (`src/clojurecraft/<ns>.clj#defn <name>`; when two definitions share a prefix, `defn resync` also matches `defn resync-click`, anchor on a unique phrase of the docstring instead: `"src/clojurecraft/window.clj#Learn the server's cursor after a lost click"`), add the test to `sourceRefs`, and add the rule to the prose with the run that taught it. `bb check` runs the brain checker; it must report `broken 0`, `ambiguous 0`, `unresolved 0`.
- `problems.edn`: append one sentence to the `:problem/last-failure` string of the stage's row (find it by `:problem/goals [...]`): the round, the issue, what the bot did, and what it does now. Keep it one line; the file is read by `problems_test`.

---

## 11. Committing

### 11.1 The message

```
<Imperative subject, 72 characters at most>

- <what went wrong, with the round and the issue>
- <what the change does, one bullet per function or rule>
- <the test>
- <brain note(s) and problems.edn follow>

Fixes #<n>
```

Conventional prefixes (`fix:`, `docs:`, `test:`) are used for tooling and docs; bot fixes in this history use a plain imperative subject ("Climb to water seen overhead, and off a ledge"). Either is accepted; be consistent within a PR. The body wraps at about 72 columns. Reread it for attribution before committing (rule 1.1).

### 11.2 Through the hooks

```sh
git add -A src test brain resources
git commit -F /tmp/msg.txt
```

lefthook runs: `tidy-ns` (clojure-lsp clean-ns), `format` (zprint), `lint` (clj-kondo, zero findings), `idioms` (Splint), `unused` (no public var nothing calls), and the commit-msg check (no attribution, subject length). A hook that fails prints why; the commit did not happen. Fix it and commit again. Common ones:

- Splint `lint/identical-branches`: two adjacent `cond` branches return the same value; merge their tests with `or`.
- Splint `style/apply-str-interpose`: use `clojure.string/join`.
- zprint wants a different layout: run `bb fmt` and commit again.
- `unused`: a public function nothing calls; make it private, use it, or delete it.

### 11.3 Branch hygiene

- Start from a fresh `origin/main` (`git fetch origin` first). Agents merge all day.
- One root cause, one branch, one PR.
- Never rebase or force-push a branch after others may have pulled it; merge `origin/main` into it instead (13.1).

---

## 12. The pull request and its checks

### 12.1 Open it right away

```sh
git push
gh pr create --base main --head fix/<n>-<slug> --title "<the commit subject>" --body-file /tmp/pr.md
# not ready yet: add --draft, and mark it ready with gh pr ready <pr> when the evidence is in
```

The body, one paragraph per line (no hard wraps):

```
<The signature, its tally, the rounds and worlds it came from, and what the evidence showed the bot doing.>

<The cause in one or two sentences.>

- **<change one>.** <what it does and why>
- **<change two>.** <...>

Before/after, each snapshot played on for two simulated minutes (probe <run id>):

    snapshot   main                     this branch
    R4-35-2    [:cast-one :rejected]    active at [:portal]: cast done

<The test that holds it. What is kept under test/issues/<n>/. What is not proven and why.>

Fixes #<n>
```

Every `Fixes #n` line closes that issue on merge. Use `Refs #n` for an issue the PR touches but does not prove.

### 12.2 The checks

A `fix/*` pull request gets:

- `unit / unit`: every test but the forest property, then the static checks. Required.
- `check` (from `issue.yml`): first "Which issues, and a moment for each": every issue the body says it fixes and that is labelled `sim` must have snapshots under `test/issues/<n>/`, or the check fails at once (`issue <n> is reproduced in the sim (label sim) but test/issues/<n> holds no snapshot`). A real-only issue is only warned about (it closes on the next race's word). Then `issues_test` judges every snapshot under `test/issues/` (yours and every earlier issue's, which stay as regression guards) with `simrace/judged` and fails when a signature recurs. Required.

Snapshots: two an issue at most (from two landings when the issue has them), each file under GitHub's 100 MiB push limit (`every-snapshot-fits-on-github`); the folder has no total cap.

### 12.3 A red check

```sh
gh pr checks <pr>
gh run view <run-id> --log-failed | grep -E 'FAIL|ERROR|expected|actual|::error|Exception' | head -40
```

Read the failure, fix it, commit, push to the same branch; the checks rerun. Typical:

- a test asserting the old behaviour (10.3);
- a docs or spec test: a new key without its `model.clj` row or spec;
- the issue check: a snapshot that still recurs (your fix does not cover the moment, 9.2), or a sim-labelled issue without snapshots;
- the PR "has conflicts" and no checks start at all: merge main in (13.1).

When a check fails for reasons outside the code (a runner lost, a cache miss), rerun it: `gh run rerun <run-id>`.

---

## 13. Merging and after

### 13.1 Merge

Before merging, make sure the branch is not behind in a way that conflicts:

```sh
gh pr view <pr> --json mergeStateStatus --jq .mergeStateStatus      # CLEAN, BLOCKED, BEHIND, DIRTY
git fetch origin && git merge --no-edit origin/main                  # when DIRTY or BEHIND
git push
```

`DIRTY` means conflicts: no checks run until they are resolved. The usual conflict is `resources/clojurecraft/problems.edn`, where two fixes appended a sentence to the same `:problem/last-failure` line; keep both sentences (the base line, then each side's addition), not one or the other.

When `unit / unit` and `check` are green and the body has the before/after:

```sh
gh pr merge <pr> --merge
gh pr view <pr> --json state,mergeCommit --jq '.state + " " + .mergeCommit.oid'
gh issue view <n> --json state --jq .state            # CLOSED
```

Never `--admin`, never squash a branch whose history others refer to (the repo merges with merge commits).

### 13.2 The board

Move each closed issue's item to `Done` (4.4) if the board's automation did not.

### 13.3 After the merge

The merge restarts the rounds on your code. Their next reports are the real test: a signature that recurs on code newer than the issue's closing reopens it as "regressed" (3.2). Look at the first round's reports for your issues before taking the next one when you can; when the round's first reports land (20 to 30 minutes after the merge), check `gh issue view <n> --json state`.

Delete your probe branches. Keep the fix branch (the PR references it).

---

## 14. Failures seen and how they were fixed

Each entry: the signature, what the evidence showed, the cause, the fix, and where to look. They show the kinds of causes this work finds; use them as patterns, not recipes.

### 14.1 `stale-window` at ingots and portal-kit (#131, #132): a loaded server answers late

A round that kept 25 bots on every server failed crafts, equips and pours `:stale-window` on the first click left unanswered for 3 s. The server answers late, not never: smelting already recovered from a lost click (resync, restart the stage, fail only at the third loss), the others failed on the first. The fix moved that recovery into `window/lost` and `window/resync` and used it in every window intent (craft, place, equip, scoop, pour). Evidence: the sim with every server packet delayed (`sim/init` `:lag-ticks` 65, 3.25 s): the starting kit failed `:stale-window` on main and completed on the branch. Load still shows up as `:stale-window` when the server falls further behind than three resyncs cover.

### 14.2 `no-water` at water-bucket (#60): water seen with no route

The water hunt could see water (a pond's surface two blocks above the feet in a trench of the bot's own digging; pools remembered at the surface over a ravine floor; pools below a mountain ledge) and had no route to it, so it waited. `scoop/to-bank` cuts stairs up or down toward the nearest water's bank, or a tunnel toward it at its height. One snapshot stayed red: it stood at the edge of the captured world (15.9).

### 14.3 `boxed` at ingots (#37): a climb under water

A climb out of the mine for wood stood in a pocket under an aquifer's sheet (or under a frozen lake) where every stair up and every level step bordered water. Three changes: `stairs/duck` (with no fresh level step, a climb cuts one stair down along the heading whose head is dry soonest, and records the pocket in `:plan/boxed-at`); `:intent/visited` (a level step onto a cell the staircase already stood on comes after every other; one climb stepped round fifteen cells a hundred times); `stairs/dry-top?` treats ice as the lake's top, not the shore. And the sim race got its landing (15.8).

### 14.4 `no-station` at portal (#142): a site beside a lone lava source

Every snapshot: one obsidian cast, one lava scoop, no lava within reach of any stand. `site-ok?` accepted a site as long as some stand reached some source. `build/pool` (the sources any of a site's lava stands could scoop) and `build/spent?` (fewer than the frame cells left to cast) now refuse such a site; a remembered site that is spent is passed over; a refill that finds no station at a spent site ends the build (so the planner chooses a new site) instead of failing it. Also: `choose-site` filtered all 30 000 loaded lava sources for every candidate stand and took 40 s a call over the lava sea; each candidate now looks only at the sources near its own (`build-near`), about 3 s.

### 14.5 `timeout` at ingots (#62): a causeway over a lake

Stairs down a mountain met a lake; the staircase's sideways level steps laid a floor against the last one, twenty-six blocks over the water, until the cobblestone was spent. Every stair from the causeway was then boxed, and the mine's retreat walked a few cells along it; each finished retreat reset the plan's failure count, so the run walked back and forth until its 90 minutes ran out. `stairs/over-fluid?`: a floor is never laid when the first non-air block under it is water or lava. Proven by a full run from spawn (8.6): no ingots at 25 minutes on main, ingots at 8.5 minutes on the branch.

### 14.6 `boxed` at water-bucket (#61): the water was overhead

Climbs for water from under a lake or the sea (no shore within 48 columns) stepped and dug until boxed; one dug a room of 150 cells two blocks under a lake bed. Deep under the landing, the hunt now scoops any water source in reach that the eyes see whatever is over it (`scoop/seen-source`, aimed at the source's centre) or digs up through at most two blocks of bed and scoops from below (`scoop/from-below`). And a climb out to the surface that is boxed ends instead of failing, recording its cell in `:plan/boxed-at`, and no surface climb is cut from a boxed cell again, so the act chooses something else from there.

### 14.7 `wood: no-log` (#57): a hunt that only sets out toward known trees

Real-race slots Clj_r08, r14 and r15 on runner 1 failed in every round without moving from their landing. A replay on a runner (8.5) showed the feet under the open sky, no standing trunk remembered within reach, routable hunt targets on three bearings, and `wood/hunt` returning nil: it required a remembered log within `far-radius` before it would set out. The same slot failing round after round is the sign that the landing itself is the case (3.3). (This one was still being worked when this file was written; check its PR.)

### 14.8 `rejected` at ingots, cobblestone, stone-pickaxe (#88, #144, #64): a slow server

Ten of one runner's 33 bots stopped `:rejected`: digs of 750 to 950 ms answered with the block still standing, the same cell three times. The server log said 130 ms a tick (7.5 ticks a second, not 20). Vanilla counts dig progress in server ticks; a FINISH sent by the bot's clock arrived early, the break was delayed past the bot's 3 s confirm window or dropped behind another delayed break. The bot now decodes `set-time` (sent every 20 server ticks) with no fields and reads the server's tick length from its spacing (`game/clocked`, `:server/tick-ms`); a dig's FINISH and confirm window stretch by `intent/slowdown`. The sim sends no `set-time`, so nothing changes there; real-only issues close on the next race's word.

### 14.9 `rejected` at cast-one (#44): a block into the bot's own body

The cast laid a floor block into a cell that was the bot's own head (or feet) cell; the game refuses a block into a player. `build/reachable-stand` chose the feet because it only excluded `feet == pos`; it now refuses any cell whose feet or head is the cell worked on. And `build/refused-for-the-body?`: a placement refused with its cell inside the player is approached again instead of failing the build.

---

## 15. Catches

### 15.1 The rounds never stop, and every merge restarts them

`race.yml` and `simrace.yml` run on `main` without end; a merge cancels the running round (within a minute or two) and starts one on the new code. Docs-only merges (`brain/`, `docs/`, `*.md`) do not restart them. Never cancel a round by hand. Rounds since 526762e keep their artifacts through a cancel; earlier cancelled rounds uploaded nothing, so their issues' reproductions may have no artifacts.

### 15.2 Reports lag the code

A report comes 20 minutes after its runs ended, and a round may have started before your merge. A comment "at <sha>" tells you which code ran: if it is older than your fix, it says nothing about the fix (the filer knows this and does not reopen for it).

### 15.3 Labels: `sim`, `real`, both

`sim` means the model produced the signature; the fix needs snapshots. `real` alone cannot be judged by the issue check. A stale `sim` label (only very old rounds' runs reproduced it in the sim) blocks the check with no snapshot to give it: ask the owner to drop the label rather than weakening the check or adding unrelated snapshots.

### 15.4 One-offs and tallies

Fewer than 3 runs in a report is not filed. A big tally over many rounds means a wall worth the time; a tally from one round after a merge may be that merge's regression: read the merged PRs first.

### 15.5 The snapshot is late

See 9.2. Expect to need the "recover at the moment" half of a fix. When a fix truly cannot change the moment (the cause is 75 minutes earlier and the state at the moment is hopeless), the evidence is a full run from spawn (8.6), and for `:timeout` the issue check already replays from the landing (15.6).

### 15.6 `:timeout` snapshots

A `:timeout` snapshot is the tick before the 90-minute limit; played on for two minutes the plan is still running, which reads as the same `:timeout`. `simrace/judged` (used by the issue check and `--snapshot`) replays a `:timeout` snapshot's run from its landing (its world file, seed and target) to the sim race's limit instead. Expect that check to take several minutes.

### 15.7 Snapshots and the 100 MiB limit

Two snapshots an issue, from two landings when there are two. Each file under 100 MiB. Earlier issues' snapshots stay forever as regression guards; your change must keep them passing.

### 15.8 The sim race knows its landing

Real bots get `:go/at` (their landing) on their `:go`; before #37's fix sim runs did not, so `:plan/go-at` was nil in every sim run and every landing-relative choice differed (no climb home from deep, a climb's level-step budget without the way home). Sim runs now get it, and snapshots written before that are played with their captured world's landing (`simrace/moment`). If an old snapshot behaves oddly, check `:plan/go-at`.

### 15.9 The captured world has an edge

A sim world is a fixed set of chunks (625). A bot that walks to its edge sees `:unknown` cells and finds nothing beyond; a real server would load the next chunk. A failure there is the model's limit: say so in the PR and do not count it against the fix.

### 15.10 Server load is real

Real rounds keep 25 bots on each server; the server falls to 7 or 8 ticks a second (`samples.edn.zst` `:sample/server-tick`, `server.log`). Anything the bot times by its own clock against something the server times in ticks breaks under load: digs (14.8), window answers (14.1). When a real-only signature appears with load, look there first.

### 15.11 Items stuck In Progress

An item `In Progress` whose branch has had no pushes and whose PR (if any) no activity for a long time (more than a day): do not take it and do not move it. Tell the owner.

### 15.12 Conflicts in `problems.edn`

Every fix appends to the same few `:problem/last-failure` lines, so concurrent PRs conflict there. Resolve by keeping both additions (13.1). A PR in conflict runs no checks at all; `gh pr checks` then prints "no checks reported".

### 15.13 Hooks lint probe scripts too

Probe scripts committed on probe branches go through Splint and the rest: write them in the house style (`clojure.string/join` not `apply str interpose`, no identical `cond` branches).

### 15.14 Tests that use a packet as "the unmodelled one"

`packet_test` decodes an id with no spec and expects `:unknown`. When you add a spec for that packet the test breaks; switch its example to another unmodelled packet.

### 15.15 Reading artifacts is not running code

Downloading artifacts, `zstd -dc`, `grep`, `awk` over logs and run maps are reading and allowed locally. Anything that loads the bot's code is not (rule 1.3).

### 15.16 Watching without waiting

Start watches in the background (`gh run watch <id> > /dev/null 2>&1 &`, `gh pr checks <pr> --watch > file &`) and keep working. Never stop work to wait on a run. When a watcher ends, read its file.

### 15.17 Give the owner exact numbers

When you report or write a PR body: the run ids, the commits, the counts, the before and after lines. "Seems fixed" is not evidence.

---

## 16. Quick reference

```sh
# setup, once
gh auth refresh -s project
gh auth status

# the board (section 4)
PROJECT_ID=$(gh project view 10 --owner BridgerB --format json --jq '.id')
STATUS_FIELD=$(gh project field-list 10 --owner BridgerB --format json --jq '.fields[] | select(.name == "Status") | .id')
opt() { gh project field-list 10 --owner BridgerB --format json --jq ".fields[] | select(.name == \"Status\") | .options[] | select(.name == \"$1\") | .id"; }
gh project item-list 10 --owner BridgerB --format json --limit 500 \
  | jq -r '.items[] | select(.status == "Todo" and .content.type == "Issue") | [.content.number, .id, .content.title] | @tsv' | sort -n
gh issue view <n> --json labels,state --jq '[.state, ([.labels[].name] | join(","))] | @tsv'

# claim (section 5, step 5)
git fetch origin
git ls-remote --heads origin 'fix/<n>-*'
git switch -c fix/<n>-<slug> origin/main && git push -u origin fix/<n>-<slug>
gh project item-edit --id <item> --project-id "$PROJECT_ID" --field-id "$STATUS_FIELD" --single-select-option-id "$(opt 'In Progress')"

# evidence (section 7)
gh issue view <n> --json comments --jq '.comments[] | .createdAt + "\n" + .body'
bb issue-check fetch <n>
gh run download <run> -n simrace-<sha>-<runner> -D /tmp/ev/<n>
gh run download <run> -n race-<sha>-<runner> -D /tmp/ev/race
grep -h '^RESULT' /tmp/ev/race/s*/race.log | grep -o ':reason :[a-z-]*' | sort | uniq -c | sort -rn
zstd -dc /tmp/ev/race/s1/Clj_r08.edn.zst | grep -o '"[^"]\{3,200\}"' | awk '!seen[$0]++' | tail -40

# code questions (section 8)
gh workflow run probe.yml --ref probe/<n> -f run='clojure -M:dev probe/p<n>.clj probe/<n>/<snapshot>.edn.gz' -f worlds=R4 -f label=p<n>
gh run list --workflow probe.yml --limit 1 --json databaseId --jq '.[0].databaseId'
gh run watch <id> > /dev/null 2>&1 &
gh run view <id> --log

# local checks, commit, PR (sections 7, 11, 12)
bb fmt && bb check
git add -A src test brain resources && git commit -F /tmp/msg.txt
git push && gh pr create --base main --head fix/<n>-<slug> --title "<subject>" --body-file /tmp/pr.md
gh pr checks <pr> --watch --interval 30 > /tmp/checks-<pr>.txt 2>&1 &
gh run view <run> --log-failed

# merge (section 13)
gh pr view <pr> --json mergeStateStatus --jq .mergeStateStatus
git fetch origin && git merge --no-edit origin/main && git push       # when behind or in conflict
gh pr merge <pr> --merge
gh project item-edit --id <item> --project-id "$PROJECT_ID" --field-id "$STATUS_FIELD" --single-select-option-id "$(opt Done)"
```

---

## 17. A worked example: issue #142, start to finish

This is one real fix, step by step, with its real ids and shas. Its first probes ran before the probe workflow existed; the commands below are how they are run now. Use it as the shape of your own.

### 17.1 The issue

```sh
gh issue view 142 --json title,labels,body --jq '.title, ([.labels[].name] | join(",")), .body'
```

`Bot stops at portal: no-station`, labels `bot-failure,sim`, tally "Seen in 18 rounds, 89 runs". The newest comments came from sim race 38664661309 at 8ddbb1a, runners 3 and 6, worlds R1/38, R2/40, R1/43, R2/45 and R4/24, every one "last intents: {nil ...}" (the plan was waiting or the intent had just failed).

`grep -rn ':no-station' src/clojurecraft/` pointed at one function, `build/refill`: the reason a lava pour cannot get its lava (no stand that reaches a source, or no source in reach from the stand).

### 17.2 The evidence

```sh
mkdir -p /tmp/ev/142/r6 /tmp/ev/142/r3
gh run download 38664661309 -n simrace-8ddbb1a0b54461293c367ce17d00a30bf013a0af-6 -D /tmp/ev/142/r6
gh run download 38664661309 -n simrace-8ddbb1a0b54461293c367ce17d00a30bf013a0af-3 -D /tmp/ev/142/r3
grep ':portal :no-station' /tmp/ev/142/r6/runs.edn | grep -o ':run/file "[^"]*"\|:run/seed [0-9]*\|:pos \[[^]]*\]' | paste - - -
```

Seven runs, five landings, all at `[:portal :no-station]` after 25 to 65 simulated minutes, each holding the whole portal kit (flint and steel, a water bucket, an empty bucket, hundreds of cobblestone). Nothing in the run maps said why.

### 17.3 The first probe

A throwaway branch from `main` with three snapshots and a script that, at each snapshot's moment, printed the build intent, the lava sources nearest its origin, `build/station` and `build/refill`; then a second script that counted the obsidian and the lava within 12 blocks of each origin and the scoops in the bot's memory of intents. Dispatched with `worlds=R1,R2,R3,R4`.

The answer, in every world: `station nil`, `refill :no-station`, one obsidian in the frame, two scoops in memory (one lava, one water), no lava within 12 blocks of the origin, the nearest other source 20 to 40 blocks away.

### 17.4 The cause

Reading `build/site-ok?`: a site was accepted when some lava stand reached some source. A lone source passes. The cast scooped it, cast one cell, and the next refill found nothing. Nine cells to go and no lava: `:no-station` three times, the plan failed.

### 17.5 The fix, and the moment

The cause half: a site's pool (`build/pool`: every source any of its lava stands could scoop, at every height up to `max-access`) must hold at least as many sources as the frame has cells left to cast (`build/spent?`); `site-ok?` refuses a spent site; `plan/next-intent :build` passes over a remembered site that is spent.

The moment half (9.2): at each snapshot the build was already running at a spent site with two attempts counted, so the first play-on still failed on the next tick. A refill that finds no station at a spent site now ends the build (done, not failed) so the planner chooses a new site or hunts for lava.

A third finding came from the play-on taking minutes of wall time for two simulated minutes: `choose-site` filtered all 30 000 loaded lava sources for every candidate stand, 40 s a call over the lava sea. Each candidate now looks only at the sources near its own (`build/build-near`): about 3 s.

### 17.6 The test

`build_test/a-lone-lava-source-is-no-site`: one source and nine sources give no site, ten give one; with the pool check removed, the first two assertions fail.

### 17.7 Notes, commit, PR, checks

`brain/bot/plan/build-plan.md` got the pool rule with the run that taught it and the new functions and test in its `sourceRefs`; the portal row of `problems.edn` got one sentence. `bb fmt`, `bb check`, commit through the hooks, push, PR "Choose a site whose lava can finish the frame" with `Fixes #142`, one snapshot under `test/issues/142/`, and the before/after table:

```
snapshot   main                   this branch
R1-38-1    [:portal :no-station]  active: left the spent site, stairs down toward lava
R2-40-1    [:portal :no-station]  active: left the spent site, stairs down toward lava
R1-43-1    [:portal :no-station]  active: left the spent site, stairs down toward lava
```

`unit / unit` and `check` went green in about nine minutes; merged with `gh pr merge 160 --merge`; #142 closed.

---

## 18. When you are stuck

- **The probe crashed.** Read the stack in `gh run view <id> --log`: a typo in a namespace alias, a function that is private (call it through its var, `(#'ns/fn args)`), or a world file missing because `worlds=` did not name its set.
- **The snapshot recurs and you are sure the cause is fixed.** It is the moment problem (9.2): print what the bot does on the very next tick at the moment, find what fails there, and make that a re-plan.
- **The snapshot passes but you doubt it.** "Still active at the budget" passes the check without proving much; follow the run longer (8.4's stepper over a few simulated minutes) or run it from spawn (8.6) and report the stage it reached.
- **The fix makes another snapshot under `test/issues/` recur.** That issue's rule and yours conflict. Read that issue's PR and notes; the right fix keeps both.
- **The issue is real-only and you cannot see the cause.** Download the round's race artifact (7.3); read `race.log`'s RESULT lines for the slots that failed, the server's tick time, and the bot's own log lines in its recording; replay the recording on a runner at its commit (8.5) and ask the acts at the end what they would do.
- **The cause is in tooling** (the workflows, `bb/`, the issue check, the filer): it is not yours to change. Describe it to the owner with the evidence and leave the bot-failure issue in `Todo` or its PR open.
- **Two issues turn out to be one cause.** Put both `Fixes` lines in the one PR, both items `In Progress`.
- **The issue is not a bug** (the model cannot represent what the real server does, or the failure is the captured world's edge): say so in a PR or tell the owner; do not close issues by hand.
