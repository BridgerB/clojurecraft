# AGENTS.md: keeping the bot-failure issues down

This is the working manual for fixing the bot's failures in this repository. It is written for any coding agent or person who picks the work up cold: you may not know Clojure well, this repo, or the tools that were used before you. Follow it in order. Everything here was done for real, many times, on 2026-10-14; the commands are the ones that worked, and the catches are the ones that cost time.

The short version: the rounds on GitHub's runners play the bot without end and file every way it fails as an issue labelled `bot-failure`. You take the oldest such issue off the project board, find out why the bot stopped, change the bot so it does not, prove it with the evidence the issue carries, and merge. Then the next one.

## Contents

0. Start here: the tree, the philosophy, the brain, the code
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
19. The planner and the intents, rule by rule
20. The world, the protocol and the game, for a fixer
21. The run, stage by stage
22. Every failure reason, what it means, where it is raised
23. Every bot-failure issue so far
24. The attributes a fix reads and writes
25. Every namespace, in a line or two
26. The tests, and how to write one you cannot run
27. The CI, the workflows and their artifacts
28. `RICH-HICKEY.md` in this code: the patterns to copy

---

## 0. Start here: the tree, the philosophy, the brain, the code

Do this before anything else, before the board and before any issue. It is the most important part of this file: a fix written without it is a fix in the wrong style, and the wrong style is a wrong fix here even when the tests pass. Doing it well takes a long read (about 75,000 words of notes and 5,000 lines of code); take the time. An agent that has done all of section 0 in its current session may skip to section 1 for its next issue; a new session does it again.

### 0.1 See the tree

The very first command:

```sh
tree -a --dirsfirst -I '.git|.cpcache|.clj-kondo|.lsp|.nix|.direnv|node_modules|cache|data|out|target|issues|recordings|churn-batches'
```

About 360 lines: every source, test, note, workflow and tool in the repository. Without `tree` installed: `nix shell nixpkgs#tree -c tree ...` with the same arguments, or `find . -path ./.git -prune -o -type f -print | sort`. Read it all; you will refer back to it. The parts that matter most to a fix: `src/clojurecraft/` (the bot, one namespace per subject), `test/clojurecraft/` (a test namespace per source namespace), `brain/` (the notes), `resources/clojurecraft/` (the goal table, the problem statements, the generated tables), `bb/clojurecraft/` (the tooling: not yours to change), `.github/workflows/` (the CI: not yours to change).

### 0.2 Read `docs/RICH-HICKEY.md`, in full

This is the design brief, and the single most important document in the repository. Read every word before any other file. It sets out how Rich Hickey would build this bot (simple meaning not braided, values over state, information as open maps of namespaced keys, design as taking things apart, processes speaking data over bounded queues, growing without breaking), holds the TypeScript bot and the early Clojure code up against it, and describes the design the bot follows now. Every review of a fix asks one question first: does it follow this?

What it means for a fix, in practice:

- The world is one value. A fix reads the world and returns a new value or an intent; it never mutates, never keeps a hidden atom, never reads a clock or calls `rand` inside a reducer (time and randomness come in on the tick event).
- Facts are data with names: a new thing the bot knows is a namespaced key on the world (with its row in `model.clj` and its spec in `spec.clj`) or a fact in memory, never a positional tuple or an unnamed flag.
- Dispatch is open: a new behaviour is a `defmethod` or a row in a table (`goals.edn`, a registry), not an edit to a `case`.
- Rules, not conditionals piled up: one small function per rule, a docstring stating it and the run that taught it, `cond` over nested `if`, `reduce` over `loop`.
- Accretion: never change the meaning of a key, never rename one; add beside it. A wrong goal is superseded, not rewritten, so old recordings still replay.
- I/O lives below a `;;;; I/O ;;;;` fence in the few namespaces that have it. A bot fix almost never needs I/O.

If a fix you are about to write needs an exception to any of this, the fix is probably wrong; look for the shape that does not need it.

### 0.3 Read `CLAUDE.md`

At the repository root. Despite its file name it is the repository's guide for every agent and person: the style rules restated, the layout (one line per namespace), the commands, the CI workflows and the words the project uses. Read it whole.

### 0.4 Read the brain

`brain/` is the project's verified knowledge base: about 150 notes, one subject each, each pinned to the source by `sourceRefs` (`path#symbol` anchors the brain checker keeps resolving). The notes say why the code is the way it is, which run taught each rule and what was tried and failed. Read as much of it as you can, and in a first session read all of it but `siblings/`. In this order:

1. `brain/_index.md`, then `brain/rules.md`, `brain/conventions.md`, `brain/operating-manual.md`, `brain/glossary.md` and `brain/questions.md`.
2. `brain/bot/_moc.md` (the bot's map of contents), then every note under `brain/bot/` in this order of folders: `concepts/` (the ideas: the world value, intents, facts), `decisions/` (why the design is what it is), `plan/` (the planner, every intent and act: walk, dig, collect, stairs-down, scoop, build, fortress; the largest and most important for fixes), `world/` (terrain, memory, paths), `protocol/` (packets and the reducer), `runtime/` (the loop, recording, telemetry), `crafting/` (windows, recipes, smelting), `gotchas/` (traps already fallen into), `how-to/`, and `tooling/` (the races, the sim race, the issue check, the gym: how the evidence you will read is made).
3. `brain/game/_moc.md` and every note under `brain/game/` (`mechanics/`, `server/`, `windows/`): how vanilla 26.1.2 actually behaves, read from the server's own code.
4. `brain/siblings/` when a fix touches what the two sibling bots (TypeScript and Rust) learned first: drafts, not verified, but often the first record of a mechanic.

To list them: `find brain -name '*.md' | sort`. Before touching any namespace later, reread its note (`grep -rl '<namespace-or-function>' brain/`).

### 0.5 Read ten source files, in full

These are the namespaces the most other files depend on, plus the two that show the project's own patterns at their clearest. Read each top to bottom: the data at the top, then the helpers, then the entry points, each function with its docstring. Notice the shape: small pure functions over the world value, a docstring that states the rule and often the run that taught it, constants as `def`s with a comment, `cond` and `reduce`, namespaced keys everywhere.

1. `src/clojurecraft/game.clj`: the protocol reducer, `game/step`; every packet's handler (`on-packet`, a multimethod); effects as data. The centre of the bot.
2. `src/clojurecraft/plan.clj`: the planner; the goal table loaded from `goals.edn`; the open registries `done-by`, `act`, `next-intent`; waits, attempts and how a plan fails.
3. `src/clojurecraft/intent.clj`: intents as values run a tick at a time; `intent/run` as a multimethod; walk, dig, collect; child intents.
4. `src/clojurecraft/model.clj`: the information model, every attribute with its meaning and lifetime. Every key a fix adds goes here.
5. `src/clojurecraft/memory.clj`: facts with time in DataScript, never retracted; the Datalog queries the acts ask.
6. `src/clojurecraft/terrain.clj`: the blocks round the bot as values: columns, the overlay, `block-at`, `solid-fn`.
7. `src/clojurecraft/physics.clj`: vanilla movement as a pure function of the player, the controls and a solidity oracle.
8. `src/clojurecraft/blocks.clj`: the generated block, item and entity tables and the questions asked of them (`solid?`, `log?`, `name-of`).
9. `src/clojurecraft/stairs.clj`: an executor made of child digs (stairs up and down, the boxed and duck rules): the kind of namespace most fixes change.
10. `src/clojurecraft/build.clj`: a build plan as data, every op with a done-predicate read from the world, so a cast resumes after a death: "builds are data" in `RICH-HICKEY.md`, in code.

When a fix touches the server model, read `src/clojurecraft/sim.clj` as well (the largest namespace).

### 0.6 Then this file

Sections 1 to 18 are how to work; Part II (sections 19 to 28) is the reference to keep open while you work: read 19 to 22 once in full before your first issue. Then take an issue (section 5).

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

Why: GitHub's runners have the compute and the captured worlds; the laptop must stay usable, and a "quick" local run is how hours of heat and battery went before this rule. Everything that runs code goes through the branch's `test.yml` run on each push, the pull request's checks, or `probe.yml` (section 8). This is not a guideline to weigh against convenience.

### 1.4 Never touch the shared game box

Never connect to, SSH into, or send RCON to `144.24.32.76`. Never start a Minecraft server on the local machine. The runners start their own servers (on 25565 and up, on the runner, in the race and gym workflows); that is theirs, not a port rule for you.

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

Section 0 is the reading: the tree, `docs/RICH-HICKEY.md`, `CLAUDE.md`, the brain and ten source files. Where `CLAUDE.md` and this file disagree on process, this file is newer for the fix loop. Two more, once:

1. `docs/CHURN.md`: the older description of finding and fixing failures on the runners. Its "run locally" advice is superseded by rule 1.3.
2. `resources/clojurecraft/problems.edn`: one row per stage (wood, kit, pickaxe, ..., portal, nether, fortress) with the statement, needs, risks, what done means and `:problem/last-failure`, a running record of what failed last at that stage and what was done about it. Every fix appends a sentence there (section 10.5).

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
- Never an item `In Progress`: another agent owns it, unless it is stale by all three tests of 4.6.
- Never issue #10 (the roadmap, on hold) and never anything without `bot-failure` (tooling issues belong to whoever owns the tooling).

### 4.4 Moving an item

These commands were verified against the live board (2026-10-14): the project id has the form `PVT_...`, the Status field `PVTSSF_...`, an item `PVTI_...`, an option a short hex id.

```sh
gh project item-edit --id <item-id> --project-id "$PROJECT_ID" \
  --field-id "$STATUS_FIELD" --single-select-option-id "$IN_PROGRESS"
```

Re-read the board after every move to confirm it held (another agent may have moved it at the same moment).

### 4.5 Heal the board

The rounds file and reopen issues on their own, and the board may not follow. Every time you open the board, make it true:

```sh
gh issue list --label bot-failure --state open --limit 500 --json number,url > /tmp/open.json
# 1. an open bot-failure issue that is not on the board: add it, then set it to Todo
for url in $(jq -r --slurpfile b /tmp/board.json '.[] | select(.number as $n | ($b[0].items | map(.content.number) | index($n)) | not) | .url' /tmp/open.json); do
  item=$(gh project item-add 10 --owner BridgerB --url "$url" --format json --jq '.id')
  gh project item-edit --id "$item" --project-id "$PROJECT_ID" --field-id "$STATUS_FIELD" --single-select-option-id "$TODO"
done
```

2. An item whose issue is open but whose status is `Done` or empty: the issue was reopened (or never sorted). Set it to `Todo`.
3. An item whose issue is closed but whose status is `Todo` or `In Progress`: set it to `Done`.

Re-read the board after healing. Healing moves items between `Todo` and `Done` only, never into or out of `In Progress` except as 4.6 says.

### 4.6 Stale claims

An agent can stop mid-claim (its session ended, its machine slept). Its items would stay `In Progress` forever. An item is stale when all three hold:

- its status was set to `In Progress` more than six hours ago (the status value's `updatedAt`, below);
- its claim branch (`fix/<n>` or `fix/<n>-rK`) has no commit newer than six hours that is not on `main` (`git log -1 --format=%cI origin/fix/<n>` against `git merge-base`), or does not exist;
- no open PR from that branch was updated in the last six hours (`gh pr list --head fix/<n> --json updatedAt`).

```sh
gh api graphql -f query='query { user(login: "BridgerB") { projectV2(number: 10) { items(first: 100) { nodes {
  id content { ... on Issue { number } }
  fieldValueByName(name: "Status") { ... on ProjectV2ItemFieldSingleSelectValue { name updatedAt } } } } } } }' \
  --jq '.data.user.projectV2.items.nodes[] | select(.fieldValueByName.name == "In Progress") | [.content.number, .id, .fieldValueByName.updatedAt] | @tsv'
```

A stale item may be returned, by any agent, without asking: keep the abandoned work by pushing its branch to `stale/fix/<n>-<YYYYMMDD>`, delete `fix/<n>` (`git push origin --delete fix/<n>`), close its PR if it has one (`gh pr close <pr>`, no comment), and set the item back to `Todo`. Then it is an ordinary Todo item, for you or anyone. Never return an item that is not stale by all three tests; an agent thinking for a long time still pushes and updates its PR.

## 5. The loop, step by step

This is the spine of the work. Do it the same way every time.

### Step 1. Read

Section 0, if not done in this session: the tree, `docs/RICH-HICKEY.md`, `CLAUDE.md`, the brain, the ten files. Then this file. At the start of each session, reread section 15 (catches) as well.

### Step 2. Open the board

Look up the ids and list the items (section 4). Then heal the board (4.5): add any open bot-failure issue that is missing, put reopened issues back in `Todo`, put closed ones in `Done`, and return stale claims (4.6). Do this every time; nothing else keeps the board true.

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

b. Push the claim branch. Its name is fixed by the issue, never chosen: `fix/<n>`, where `<n>` is the lowest issue number in your group. Two agents that chose the same issue compute the same name, and git lets only one of them create it: the push is the lock.

```sh
git fetch origin
B=fix/<n>
git ls-remote --exit-code --heads origin "$B" >/dev/null && echo "taken"   # prints "taken" when it exists
git switch -c "$B" origin/main
git push -u origin "$B"            # rejected when another agent created it first: that agent has it
```

When `fix/<n>` already exists, look at its pull request: `gh pr list --head "$B" --state all --json number,state`.

- An open PR, or no PR at all: another agent has the issue. Pick something else.
- Only a merged or closed PR (the issue was fixed once and has come back, "regressed"): the name is spent. Claim `fix/<n>-r2` the same way, then `fix/<n>-r3`, the first that does not exist. Every agent counts the same way, so the lock still holds.

Never a slug in the name (`fix/<n>-<words>`): two agents with two slugs would both succeed, and the lock would be gone. Older branches in this repository have slugs; they are from before this rule.

c. Move each chosen item to `In Progress` (4.4), then re-read to confirm the move held. If any item turns out not to be `Todo` any more, delete your branch (`git push origin --delete "$B"`) and choose again.

No comment, no assignee, no label, no name: the board status and the branch are the whole claim (rule 1.2).

### Step 6. Work the fix

Sections 6 to 10: read the issue and every comment, fetch the evidence, ask the code questions on a runner, find the cause, write the fix and a test, update the brain note(s) and the stage's `problems.edn` row in the same change.

### Step 7. Local checks only

```sh
bb fmt          # format with zprint
bb check        # format check, lint (clj-kondo), idioms (Splint), ns forms, unused vars, the brain checker
```

Nothing else locally (rule 1.3).

### Step 8. Commit and push to the claim branch

Section 11 for the commit. Push every commit to your claim branch (`fix/<n>`). Do not open a pull request yet: the board status and the branch are the claim, and a PR is opened only when the change is ready (step 11). Every push to any branch runs `test.yml`: the whole unit suite, one forest shard of the sim property, and the workflow lint. That run is your test run. It does not judge the issue snapshots: `issues_test` skips a snapshot whose captured world is not on the runner, and only the PR's issue check restores them. Judge your snapshots before opening the PR with a probe: `gh workflow run probe.yml --ref fix/<n> -f run='clojure -M:test -v clojurecraft.issues-test/no-issues-failing-moment-fails-the-same-way-again' -f worlds=R1,R2,R3,R4 -f label=<n>-issues`.

### Step 9. Watch the branch's test run without blocking

```sh
ID=$(gh run list --workflow test.yml --branch fix/<n> --limit 1 --json databaseId,headSha \
  --jq '.[] | select(.headSha == "'"$(git rev-parse HEAD)"'") | .databaseId')
gh run watch "$ID" --exit-status > /tmp/test-<n>.txt 2>&1 &
gh run view "$ID" --log-failed | grep -E 'FAIL|ERROR|expected|actual|::error|Exception' | head -40   # when red
```

(If the list is empty, the run has not been created yet; ask again a few seconds later.) Keep working (the next issue's reading, a probe) while it runs. A red run: read its log, fix, push another commit to the same branch, repeat until green (12.3). Never rerun anything locally "to confirm".

If your tool cannot run a process in the background, poll instead: between other work, every three to five minutes, run `gh run view <id> --json status,conclusion` and carry on until it reports a result. Never sit in a loop of `sleep` doing nothing; never let a turn end only because a run has not finished.

### Step 10. Code questions go to `probe.yml`

Section 8.

### Step 11. Open the PR when the change is ready, then merge

Ready means: the fix and its test are pushed, the branch's `test.yml` run is green, the snapshots the issue check needs are under `test/issues/<n>/`, and you have the before/after evidence (from probes, section 8). Only then open the pull request (12.1), never as a draft. Its checks add the issue check (`check`: a `sim`-labelled issue it fixes must carry snapshots) to `unit / unit`. A red check: commit and push to the same branch; the PR's checks run again.

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
  -f label=<n>-<word>
sleep 8
ID=$(gh run list --workflow probe.yml --limit 20 --json databaseId,displayTitle \
  --jq '.[] | select(.displayTitle == "probe <n>-<word>") | .databaseId' | head -n 1)
gh run watch "$ID" --interval 30 > /dev/null 2>&1 &         # in the background
gh run view "$ID" --log | grep -E '<what you print>'          # when it is done
gh run download "$ID" -n probe-"$ID" -D /tmp/probe-"$ID"      # anything written under out/
```

- `label` names the run: its title is `probe <label>`. Make it unique, the issue number and a word (`142-pool`, `142-after`), and find the run by it as above. Several agents dispatch probes at once, so the newest probe run (`--limit 1`) may be someone else's.
- `run` is one shell command, run from the checkout root with Java, the Clojure CLI and Babashka installed.
- `worlds` restores the captured sim worlds a snapshot or a simrace run needs (`R1,R2,R3,R4`; none by default). A snapshot names its world file (`cache/sim-worlds/R4/35.edn.gz`); restore that set.
- The script and any inputs (snapshots, recordings) must be committed and pushed on the branch before you dispatch: the runner checks out the branch, nothing else.
- Files written under `out/` come back as the artifact `probe-<id>`.

### 8.1 Where to put probe files

Never on the fix branch: a probe script is not part of the fix. Make a throwaway branch from the fix branch (to probe the fix) or from `main` (to probe the failure), put the script under `probe/` and the inputs under `probe/<n>/`, commit (through the hooks: they lint the script too, see 15.13) and push:

```sh
git switch -c probe/<n> origin/fix/<n>       # or origin/main
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
gh workflow run probe.yml --ref probe/<n> -f label=<n>-<word> \
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

### 10.1 Style (`docs/RICH-HICKEY.md`)

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
- One root cause, one branch, one PR. The branch is the claim: `fix/<n>` for the lowest issue in the group (step 5).
- Never rebase or force-push a branch after others may have pulled it; merge `origin/main` into it instead (13.1).

---

## 12. The pull request and its checks

### 12.1 Open it when the change is ready

Not before (step 11): the work in progress lives on the claim branch, tested by `test.yml` on every push, and the PR list holds only changes that are ready to judge. Never open a draft.

```sh
git push
gh pr create --base main --head fix/<n> --title "<the commit subject>" --body-file /tmp/pr.md
gh pr checks <pr> --watch --interval 30 > /tmp/checks-<pr>.txt 2>&1 &
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

Move each closed issue's item to `Done` (4.4) yourself; do not count on the board's automation. The next agent's healing (4.5) catches what you miss, but move it anyway.

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

A `:timeout` snapshot is the tick before the 90-minute limit; played on for two minutes the plan is still running, which reads as the same `:timeout`. `simrace/judged` (used by the issue check and `--snapshot`) replays a `:timeout` snapshot's run from its landing (its world file and seed) until it gets past the stage it timed out on, or the 90 minutes run out. A fix that holds costs only the time to reach the stage.

### 15.7 Snapshots and the 100 MiB limit

Two snapshots an issue, from two landings when there are two. Each file under 100 MiB. Earlier issues' snapshots stay forever as regression guards; your change must keep them passing.

### 15.8 The sim race knows its landing

Real bots get `:go/at` (their landing) on their `:go`; before #37's fix sim runs did not, so `:plan/go-at` was nil in every sim run and every landing-relative choice differed (no climb home from deep, a climb's level-step budget without the way home). Sim runs now get it, and snapshots written before that are played with their captured world's landing (`simrace/moment`). If an old snapshot behaves oddly, check `:plan/go-at`.

### 15.9 The captured world has an edge

A sim world is a fixed set of chunks (625). A bot that walks to its edge sees `:unknown` cells and finds nothing beyond; a real server would load the next chunk. A failure there is the model's limit: say so in the PR and do not count it against the fix.

### 15.10 Server load is real

Real rounds keep 25 bots on each server; the server falls to 7 or 8 ticks a second (`samples.edn.zst` `:sample/server-tick`, `server.log`). Anything the bot times by its own clock against something the server times in ticks breaks under load: digs (14.8), window answers (14.1). When a real-only signature appears with load, look there first.

### 15.11 Items stuck In Progress

An item `In Progress` is another agent's. Only when it is stale by all three tests of 4.6 (six hours with no status change, no new commit and no PR activity) may you archive its branch and return it to `Todo`.

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

# claim (section 5, step 5): the branch name is fixed by the issue
git fetch origin
git ls-remote --exit-code --heads origin fix/<n> >/dev/null && echo taken
git switch -c fix/<n> origin/main && git push -u origin fix/<n>
gh project item-edit --id <item> --project-id "$PROJECT_ID" --field-id "$STATUS_FIELD" --single-select-option-id "$(opt 'In Progress')"

# evidence (section 7)
gh issue view <n> --json comments --jq '.comments[] | .createdAt + "\n" + .body'
bb issue-check fetch <n>
gh run download <run> -n simrace-<sha>-<runner> -D /tmp/ev/<n>
gh run download <run> -n race-<sha>-<runner> -D /tmp/ev/race
grep -h '^RESULT' /tmp/ev/race/s*/race.log | grep -o ':reason :[a-z-]*' | sort | uniq -c | sort -rn
zstd -dc /tmp/ev/race/s1/Clj_r08.edn.zst | grep -o '"[^"]\{3,200\}"' | awk '!seen[$0]++' | tail -40

# code questions (section 8)
gh workflow run probe.yml --ref probe/<n> -f run='clojure -M:dev probe/p<n>.clj probe/<n>/<snapshot>.edn.gz' -f worlds=R4 -f label=<n>-<word>
gh run list --workflow probe.yml --limit 20 --json databaseId,displayTitle --jq '.[] | select(.displayTitle == "probe <n>-<word>") | .databaseId' | head -n 1
gh run watch <id> > /dev/null 2>&1 &
gh run view <id> --log

# local checks, commit, push: test.yml runs on every push (sections 5, 11)
bb fmt && bb check
git add -A src test brain resources && git commit -F /tmp/msg.txt
git push
gh run list --workflow test.yml --branch fix/<n> --limit 1 --json databaseId,status,conclusion
gh run view <run> --log-failed

# the PR, only when the change is ready (step 11, section 12)
gh pr create --base main --head fix/<n> --title "<subject>" --body-file /tmp/pr.md
gh pr checks <pr> --watch --interval 30 > /tmp/checks-<pr>.txt 2>&1 &

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

---

# Part II: the reference

Sections 0 to 18 are how to work. The rest is what you need to know while you work: the planner and every intent's rules, the world and the game, the run's stages, every failure reason, every issue so far, the attributes, the namespaces, the tests, the CI and the code's patterns. Much of it is generated from the repository itself (the goal table, `model.clj`, the namespaces' docstrings, the issues) and was true on 2026-10-14; when the code and this file disagree, the code is right, and a fix that finds such a difference corrects this file in the same PR.

---

## 19. The planner and the intents, rule by rule

Reference for every intent and act a failure can stop in. Sources: the notes under `brain/bot/plan/` and the code under `src/clojurecraft/`. Where the two disagree, this section follows the code and says so. "Issue #N" is the GitHub number; "race 38477665442" etc. are run ids from the notes.

### 19.1 The planner tick (`plan/step`)

`plan/step` is `plan/step-over` partially applied to the shipped table (`resources/clojurecraft/goals.edn`). It is a reducer after `game/step`: a `:go` event calls `plan/begin`, a `:tick` calls `plan/plan-tick` while `:plan/status` is `:active`, or `plan/landing-tick` while `:landing`.

**The table.**
- Rows: `{:goal/id :goal/priority :goal/needs :goal/provides :goal/done? :goal/act}`, plus optional `:goal/plan`, `:goal/holds`, `:goal/spares`, `:goal/blocks`, `:goal/depth`, `:goal/dimension`, `:goal/always?`, `:goal/until`, `:goal/superseded-by`.
- Need keys: `:item/<name> n`, `:tag/<item-tag> n`, `:block/<name> :near`, `:fuel/ticks n`.
- Targets are rows with `:goal/target? true` (`:wood`, `:kit`, `:pickaxe`, `:cast-one`, `:portal`, `:nether`, `:fortress`, the escapes, `:shore`). Producers are every other row with a `:goal/act`, plus one generated row per recipe (`make/craft-row`, priority 10) and per furnace recipe (`make/smelt-row`).
- Rows are retired, never rewritten: a row with `:goal/superseded-by` is never chosen, and `plan/current-id` maps a `:go` that names it to its replacement.
- Three open registries give a row meaning: `plan/done-by` (keyed by `:goal/done?`), `plan/act` (keyed by `:goal/act`), `plan/next-intent` (keyed by `:goal/plan`, default `:needs`). Default `act` is `{:plan/wait [:no-act <act>]}`.

**One tick, in order (`plan/plan-tick`).**
1. `trap/trapped-tick` re-derives `:plan/trap` (only when `:player/pos` is known), then `plan/reach` adds milestones on a chained run.
2. `plan/choose`: the highest-priority target in play (`in-play`: `:plan/goals` from `:go`, or every target, plus every `:goal/always?` row), not in `:plan/reached`, whose `done-by` is false, and that is worked in this dimension (`here?`).
3. Running intent but the cursor holds an item (`can-abandon?` false): run it anyway; it puts the item down first.
4. No goal here but one undone in another dimension (`elsewhere?`): abandon the running intent `:goal-met`, wait `:other-dimension`.
5. No goal at all: abandon any running intent `:goal-met`, `:plan/status :done`.
6. Running intent whose `:intent/goal` is not the chosen goal: abandon it `:preempted`, plan again in the same tick.
7. Running intent: `run-intent`. When it ends (done or failed), `plan-tick` runs again in the same tick.
8. No intent: `next-intent`. nil waits `:nothing-to-do`; `{:plan/wait r}` waits `r`; else `start-intent` with `:intent/goal` set.

**Needs netting (`make/decide`, `make/next-row`).**
- `decide` order: `wood/continue-gather`, then a table pick-up in flight (`intent/chain-next` for `:take-table`), then `next-row`; `:stuck` becomes `{:plan/wait :stuck}`; a found row runs `take-table` first, else `plan/act`.
- All needs are resolved against one working copy of the inventory counts, so two needs never count the same item twice (`recipe/consume`).
- `resolve-row`: `:goal/holds` first (resolved once, count handed back unconsumed: three cobblestone need one pickaxe), then `:goal/spares`, then `:goal/needs` times the runs wanted.
- `resolve-item-need`: held items are consumed; producers of the rest are tried in `producer-order` (needs already covered most, then no table needed, then larger yield). `:stuck` past `make/max-depth` (12) nested needs, on a cycle (`cycle?`), or when no producer can be done.
- `craft-now`: a craft wanted n > 1 times that can run once now is crafted before fetching the rest. Smelts are batched on purpose.
- `resolve-fuel-need`: held fuel burned in smelt order; the rest from `fuel-sources` (coal, then planks), planks first when `wood-covers?` (held logs less `log-reserve` 1 make enough).
- `resolve-block-need`: met when `make/near` finds the placed block within `table-search` (24) and not blacklisted.
- Spares: `resolve-spares` counts tools less worn ones (`without-worn`: fewer than `spare-uses` 40 digs left). `spare-worth?` refuses `:stuck`, any `:mine` act, and a `:gather` that would only wait `:no-log` or walk up an `:opening`.
- `take-table`: when the row fetches (`fetching-acts` `#{:gather :mine :scoop}` or `:stuck`), no table is held, and the placed one is within `intent/reach`, dig it to carry it (`:intent/for :take-table`). Issues #39, #67.
- Act waits: `:craft` with no table near `:no-table`; `:smelt` with no furnace `:no-furnace`, with no batch `:no-fuel`.

**Waits (`plan/wait`).**
- `wait-timeout` 20000 ms. The clock is `:plan/waiting-since`; the plan fails with the wait's own reason as `:plan/reason`.
- Source detail: only `start-intent` and `begin` clear `:plan/waiting-since`, so consecutive waits with different reasons share one clock.

**Attempts and how a plan fails (`plan/fail-intent`).**
- Every failure: remembered as a fact, controls cleared, `:plan/last` dropped (the chain restarts from a fresh search).
- The target goes into `:plan/blacklist`, except for `plan/placing-kinds` `#{:place :redig}`: their target is the cell they chose. Issue #43 (race 38477665442 Clj_r04 lost its table that way).
- A log intent with `:intent/trunk` also counts against its column in `:plan/trunk-failures`; `wood/max-trunk-failures` (2) gives the whole trunk up.
- `plan/boxing-kinds` `#{:stairs-down :tunnel}` that fail `:boxed` add the feet cell to `:plan/boxed-at`. Source detail: a `:stairs-up` that fails `:boxed` is not recorded here; only `stairs/box` (climbs `:intent/for :surface`) and `stairs/duck` add a climb's cell.
- `max-attempts` 3: consecutive failures. A success resets `:plan/attempts` and `:plan/unwalkable` (`finish-intent`).
- A `:walk` failing `:no-path` does not count an attempt; it counts in `:plan/unwalkable`; `max-unwalkable` 12 in a row fail the plan.
- The plan's `:plan/reason` is the last intent's `:intent/reason` (or the wait reason).

**Landing.** A `:go` with `:go/at` starts in `:landing`; `landed?` wants the feet within `landing-reach` 2.0 on the plane with the chunk loaded; after `landing-timeout` 30000 ms the plan fails `:no-landing`.

**Chained runs.** `--until X` plays `plan/through X`: every live target with a `:goal/until` (no escapes, no `:wade` row) at or below X's priority, in order, so `--until nether` starts at wood. On `:plan/chain? true`, `plan/reach` puts every target that holds into `:plan/reached` for the rest of the run, with `:plan/reached-after` (ms since start). A reached step is never asked again; a tool that breaks is remade by the needs of the step that uses it. A gym stage (one target, `goals-for`) keeps re-deriving.

**Preemption and abandon.**
- `:escape-water` and `:escape-lava` are priority 0, `:goal/always? true`: chosen whatever `--until` says, done by `:not-trapped-in`, planned by `:escape` only while `:plan/trap` is their kind.
- `abandon-intent`: fact `:abandoned` with the reason, controls cleared, `:plan/last` dropped, any open container closed (`:container-close`).
- Property `water-over-the-eye-preempts-whatever-runs`; test `a-regressed-target-preempts-the-running-intent`.

**Composition.** `intent/run-child` runs `:intent/child` as `:plan/intent` for one tick, then puts it back. `intent/chain-next last for drops extra`: after a `:walk` for `for`, a `:dig` of its target; after that `:dig`, a `:collect` with `drops`; only links tagged with the same `:intent/for` continue.

**Plan-level reasons.** `:stuck` (no producer), `:nothing-to-do`, `:other-dimension`, `:no-landing`, any wait reason after 20 s, any intent reason after three failures.

### 19.2 Walk (`intent/run :walk`)

Walks to within `intent/reach` (4.0, eye to block centre) of `:intent/target`. Plans a route on its first tick (19.12) and follows it waypoint by waypoint. Time is counted in ticks, not ms.

Stages, checked each tick in order:
1. `arrived?`: set `:control/look` at the target, done.
2. More than `walk-timeout-ticks` (1200, 60 s) since start: fail `:stuck`.
3. No waypoints yet: `route!`. A `:none` route with no waypoints fails `:no-path` at once.
4. `route-stale?`: plan again, or fail `:no-path` once `max-replans` (6) plans were made.
5. Route walked to its end, target still out of reach: press straight at the target.
6. Waypoint reached: advance `:intent/at`.
7. Else `follow`.

Rules:
- `intent/walk-goal`: `:near` range `goal-range` 3 by default; `:pickup` with `pickup-range` 1.5 and `pickup-rise` 2 when `:intent/pickup?` (plus `:goal/step-down?` with `:intent/step-down?`); `:pickup` at the cell over the source, range `scoop-range` 1, rise 1, when `:intent/for :scoop`; `:block` when `:intent/for :build`. `intent/goal-of` uses path's `:sky` for `:intent/for :opening`.
- `intent/arrived?`: within reach and, for `:log`, feet no more than `pickup-rise` below; for `:pickup?`, `path/goal-done?` of the walk-goal; for `:scoop`, feet level with the cell over the source or one lower and within `scoop-range`; for `:build`, `intent/centred?` (within `centred-within` 0.25); for `:opening`, feet under the open sky.
- `intent/waypoint-reached?`: within `waypoint-reach` 0.35 on the plane and within 0.5 of its height. A waypoint below counts only once the feet are down.
- `intent/follow`: jump when the waypoint is above and within `jump-near` 1.3, or when blocked.
- `intent/route-stale?`: a waypoint ahead no longer standable; no waypoint for `stuck-ticks` 40 (2 s); or a `:partial`/`:none` route with every waypoint behind. A chunk arriving is not a reason (gym cobble-a1 run 7 spent six plans on chunks without a step).
- `intent/routable?`: acts ask it before choosing a target. True for `:found`; `:partial` or `:none` with waypoints only toward a target in an unloaded chunk.
- Water, lava and shapes are the route's business; a walk that ends in water is preempted by the escape.

Failures: `:no-path` (no route, or six replans; counts in `:plan/unwalkable`, blacklists the target), `:stuck` (60 s; counts an attempt). Taught by: start on a block edge (path-a1 run 5), snowfield (#38), cliff-and-pond sim scenario.

### 19.3 Dig (`intent/run :dig`, `intent/dig-stage`)

Breaks one block with absolute times compared to `:time/now`. Three stages: `:settle`, `:digging`, `:confirm`. Also run as a child by stairs, swim-out, build, redig, place (niche).

`:settle`, refusals checked first:
- Target present and not solid: fail `:target-gone`.
- Eye more than `reach + 0.5` (4.5) from the block centre: fail `:out-of-reach`.
- `choose-tool` says `:needs-tool` (a tiered block that no hotbar tool reaches and the hand cannot harvest): fail `:needs-tool`.
- Block in `intent/never-dug` (`#{:obsidian}`), or `exposes-fluid?` without `:intent/exposes-ok?`: fail `:unsafe`. `exposes-fluid?` = a fluid in any of six neighbours, or `fluid-over-falling?` (gravel or sand stacked over it, within `falling-reach` 16, with a fluid on top; gym iron-a4 run 1, riverbed).
- Start when three consecutive `still?` ticks and more than `settle-ms` (150) have passed, or `settle-deadline` (2000) since the stage began. `still?`: horizontal speed under 0.05, on ground or afloat. The deadline came from race 38477665442 Clj_r12 (moss carpet) and Clj_r19 (fell through a guessed air).
- Start sends `set-carried-item` (slot 0 for the hand), `player-action` `:start-destroy` with `face-toward` the eye, and a `swing`; sets `:intent/finish-at` = now + `finish-delay`.

`:digging`:
- The tool gone from its slot: fail `:tool-broke`.
- Swing every `swing-every` 350 ms.
- At `finish-at`, `player-action` `:stop-destroy` (FINISH) with the next sequence; go to `:confirm` with `confirm-ticks` 60.

`:confirm`:
- Block differs from its START state (air, or what fell in): done.
- Ticks run out and the FINISH sequence was acked: fail `:rejected`.
- Ticks run out with no ack at all: done, cell overlaid as air (`terrain/predict`), held in `:world/unconfirmed` until the server's update; `terrain/forget-stale` drops it after `terrain/unconfirmed-ms`.

Rules:
- `dig-time`: `dig/ms` for block, tool, penalties; 3000 ms when the tables do not know the block. `penalties`: `:off-ground?` and `:eyes-in-water?`, each divides speed by 5 before the ceiling.
- `finish-delay` = `ms * finish-margin (1.15) + finish-slack (100)`: 3550 for a log by hand, 1442 for stone with a wooden pickaxe. FINISH is never early (property test).
- `dig/best-tool`: fastest hotbar tool of the block's own kind that harvests it. Never a tool of another kind. Stone by hand drops nothing, 7500 ms.
- Only hotbar slots are used. Callers put a stowed tool in hand first (`intent/stowed-tool` then an `:equip` intent): stairs `dig-next`, mine act, place niche. Gym iron-a3 runs 1, 3 (`:no-pickaxe` with the pickaxe in the main inventory).
- The ack alone is never the break. Issues #59, #66 (runner 9, 25 bots: acks 200-300 ms late), #64 (delayed destroy).

Failures: `:target-gone`, `:out-of-reach`, `:needs-tool`, `:unsafe`, `:tool-broke`, `:rejected`.

### 19.4 Collect (`intent/run :collect`)

Walks onto the drop until the bot holds one more of `:intent/drops` than at its first tick (`:intent/drops-before`). No packet: vanilla picks up on box contact. A collect with no drops counts logs.

Each tick:
1. Goal point: `nearest-item` (tracked item entity within 6 of the block centre), else the block centre. Progress = horizontal distance beating the best by 0.1.
2. Held count above `drops-before`: done.
3. Stalled (horizontal collision, no progress for more than `collect-stall-ticks` 20) and `blocker` finds a leaf: done with `:intent/blocked-by`.
4. More than `collect-timeout` (10000 ms) since start: fail `:not-picked-up`.
5. Farther than 0.4 and `ledge-ahead?` (open cell `ledge-look` 0.6 ahead with no landing within `path/max-fall`): done, drop left.
6. Farther than 0.4: walk straight at it. Else stand.

Rules:
- `intent/blocker` probes 0.8 ahead in x, z, diagonal, head then feet height; only leaves count.
- A blocked collect and a ledge are done, not failed: no attempt, no blacklist. The ledge was a failure once (`:ledge`): issue #93, sim race 38477665389, and gym iron-a9 run 3 fell from a ravine edge.
- The 10 s clock restarts with each resumed collect.

Failure: `:not-picked-up` (drop not reached in 10 s; usually a walk that ended round a corner or a drop behind a wall).

### 19.5 Gather chain (`wood/gather-next`)

How any goal gets a log: walk, dig, collect, each tagged `:intent/for :log`, started one after another from `:plan/last`. `:wood` uses it directly; `:kit` through the `:gather` act.

Order in `gather-next`:
1. `continue-gather`: from `:plan/last`, only when tagged `:log`. Walk → dig the same target; dig → its `:intent/resume` if any, else collect; collect with `:intent/blocked-by` and fewer than `max-clears` (6) clears → dig the leaf with `:intent/resume` = the same collect, `:intent/clears` + 1.
2. `near-walk`: nearest standing trunk in `search-radius` 48, within 12 above or below the eye (`memory/nearest-log`), skipping blacklisted logs and failed trunks; of the `far-candidates` (3) nearest, the first `intent/routable?` accepts. Walks to `trunk-target` (log nearest feet height, up to `max-trunk` 8 up the column).
3. `climb-out`: trunks within `far-radius` 256: walk to the first routable; else, when not `stairs/out?` and the feet are not in `:plan/boxed-at`, `intent/opening`, else `:stairs-up` to `stairs/climb-to` top-of-world, `:intent/for :surface`.
4. `hunt`: under the open sky with logs remembered, a walk `:intent/for :log-hunt` toward the ground nearest the nearest log (`scoop/hunt-targets`, `scoop/first-walk`).
5. `{:plan/wait :no-log}`.

Rules:
- The `:log` tag exists so a walk to a table is never continued as a dig.
- A trunk no route reaches is passed over, never walked at. Issue #75 (sim race 38395033917, twelve `:no-path` a second).
- Stairs only once a trunk is remembered, no route reaches it, and the shaft cannot be walked up. Full runs 29ef069 (stair at spawn, `:stair-not-taken`) and 4528031 (new stair, pickaxe broke at y 49).
- `stairs/out?` is false in a pit (`stairs/pit-rim`). Issues #56, #58, #54 (dug two deep beside a cliff), #63 (pocket under a canopy), #102, #103 (quarry floor), #104 (crevice under leaves).
- Hunt taught by issues #57 (lake shore, forest across) and #87 (trees past the path budget).

Waits and failures: `:no-log` (nothing remembered, or no walk, climb or hunt possible). Child failures are the walk's, dig's, collect's.

### 19.6 Stairs down, stairs up, tunnel (`stairs.clj`)

`{:intent/kind :stairs-down :intent/to-y n}` cuts a staircase one stair at a time until the feet are at or below `to-y`; never digs the block under the feet. `:stairs-up` climbs the same way (`stairs/shapes`). `:tunnel` is level steps only. Each stage is a method of `stairs-stage`.

Stages:
1. `:plan`: `airborne?` (not on ground, not in liquid): wait. Heading `:intent/dir` (default `+x`). Stair cells `safe?` and fresh (not in `:intent/visited`): `:open`. Else `lay` gives cells: `:lay`. Else turn a quarter and count; at `max-turns` (4) refusals, `step-aside`.
2. `:lay`: each cell a child `:place` of a `filler` at `:intent/cell`; then `:open` or `:level`. A refused placement or no filler: `refuse`.
3. `:open`: child dig of the topmost solid cell (`dig-next`, which equips a stowed tool first or fails with `tool-missing`). Child `:target-gone` (`fell?`) counts as done. Child in `refusals` `#{:needs-tool :unsafe}`: `refuse` (turn). Child `:rejected`: dig again via `rejected`, up to `max-rejections` 3, then fail `:rejected`. Any other child failure fails the staircase with that reason.
4. `:step`: walk `onto` the new feet cell. `taken?`: feet half a block past the start in the stair's direction, `intent/centred?` in the cell, and on the ground going up. A stair cell solid again (gravel): back to `:open`. `step-timeout-ticks` 200 (10 s): fail `:stair-not-taken`.
5. `:level`: child digs of the level cells, then walk until the feet are in the feet cell; the 200-tick clock restarts after each dig; fail `:stair-not-taken`.
6. Done: `:stairs-down` at or below `to-y`; `:stairs-up` on the ground at `to-y`, or `out?` unless `:intent/to-height?`.

Rules:
- `stair-cells`: head-up, head, new feet one down. `up-cells`: over the head, new head, new feet one up. `level-cells`: head and feet one along.
- `clear-way?`: no opened cell water, lava or unloaded; no lava in any `exposed` cell; new feet cell not in `:plan/boxed-at`. `safe?` adds a solid floor under the new feet.
- Water beside a stair is allowed, lava is not; a cell that itself borders water is refused by the dig (`:unsafe`) and the stair turns (`water-beside-a-stair-cell-turns-the-stairs`).
- `level-safe?`: stair rules, no opened cell bordering a fluid, and not `too-hard?` (gym iron-a9 run 4).
- `lay` / `floor-laying`: floor against `place/support`, else a holder first. Never over a fluid (`over-fluid?`, looks down `bridge-depth` 16): issue #62 (causeway 26 blocks over a lake). `fillers`: cobblestone, self-dropping stones, dirt; never gravel or sand. Issue #37 (cave lip, cast through).
- `onto` creeps under `creep-speed` 0.06 when nothing beyond the cell stops the body; a stair ends centred or it becomes a shaft (Clj_r18).
- `step-aside`: first heading allowed, then keeps `:intent/level-dir`, never straight back. Going up, ranked by `shore-distance` (dry top within `shore-reach` 48, `lake-ice` is not shore, issue #37), then `dry-head-distance` (`wet-head?` y+1..y+3, issue #61), then toward `:plan/go-at`; a way not `under-falling?` first (issue #43). Visited cells last. Fails `:boxed` when none is allowed or `level-step-limit` is spent (`max-level-steps` 32, plus the plane distance to the landing for a climb; gym iron-a9 run 1).
- `duck` (climbs only): with no fresh level step, a stair down along the heading whose head dries soonest, feet cell added to `:plan/boxed-at` (issue #37, worlds R1/33, R4/38).
- `box`: a `:stairs-up` with `:intent/for :surface` ends done and records its feet in `:plan/boxed-at` instead of failing (issue #61).
- Stairs up: `up-safe?` refuses gravel or sand over the cell above the head. `seal` lays a filler into water beside the cells (`water-beside`, `up-sealed?`) unless the water is over or under (`water-over?`); issue #54. `step-lay` lays the step ahead (issue #61). `climb-to` cuts `climb-stairs` (8) at a time (issue #39).
- `out?`: under the open sky, an open level step under the sky, and no `pit-rim` (`rim-reach` 8, `steppable?`, `step-rises`, `ground-over`).
- Tools: going down or tunnelling, rock that drops only to a pickaxe with none held fails `tool-missing` `:no-pickaxe` (batch iron-a2 dug on by hand at 7500 ms a stone); a stair up is cut by hand (`by-hand?`, issue #40: Clj_r21's stairs up stopped `:no-pickaxe` with the sticks up top). Only the pickaxe counts: snow is dug by hand (issues #42, #72).
- Tunnel: `tunnel-plan` takes the heading when `level-safe?`, else one side kept in `:intent/side`, never back; `:boxed` when neither. Done after `:intent/length` steps.
- `airborne?` from issues #65 (boxed three times mid-fall) and #107 (climb asked mid-jump).
- Gravel refilling a stair: dug again (seventh live iron-kit run).

Failures: `:boxed` (every heading and level step refused, or the limit spent; usually water above or beside, a cave lip with nothing to lay, or lava), `:stair-not-taken` (the body never reached the cell in 10 s; often an unbroken block or a mid-jump start), `:rejected` (server refused three digs of one stair), `:no-pickaxe` (going down, no pickaxe), any child reason (`:out-of-reach`, `:tool-broke`, `:not-held` from an equip).

### 19.7 Mine act (`stone/mine-next`) and redig

A mining row is data: `:goal/act :mine`, `:goal/blocks`, `:goal/depth`. One act for all rows.

| Row | Blocks | Holds | Provides | Depth |
|---|---|---|---|---|
| `:mine-stone` | stone | a wooden pickaxe (by item) | cobblestone | none |
| `:mine-coal` | coal ore, deepslate coal ore | `:tag/pickaxes` | coal | 40 |
| `:mine-iron` | iron ore, deepslate iron ore | a stone pickaxe | raw iron | 16 |
| `:dig-gravel` | gravel | any pickaxe, plus a spare (issue #67) | gravel | 40 |

Order in `mine-next`:
1. `continue-mine`: `intent/chain-next` tagged with the row's id; drops are `drops-of-row` (every loot-table try).
2. `tool-missing`: block drops only to a tool and none is held anywhere: wait `:no-<tool>` (`:no-pickaxe`). Gravel never waits.
3. Tool only in the main inventory: an `:equip` intent.
4. `exposed-of-states`: row blocks within `search-radius` 24 of the eye and `search-height` 12, from loaded terrain (never memory), not blacklisted, with a `beside` cell; of `max-candidates` 3 nearest, the first routable. Walk carries `:intent/pickup? true`, and `:intent/step-down? true` when the stand is a block up.
5. `stairs`: `stair-depth` 6 down, never below `:goal/depth`; nil when already there or the feet are in `:plan/boxed-at`.
6. `tunnel-heading` → `:tunnel` of `tunnel-length` 12. Ranking: dry top over the column 12 ahead (`stairs/dry-top?`), then farthest from the landing; first step `level-safe?` with rock to cut; nil from a boxed cell.
7. `deeper`: `:stairs-down` 6 more, ignoring the row depth, when a stair can be cut or cast and the bottom is more than `floor-margin` 8 away; nil from a boxed cell.
8. `retreat`: in water, `:swim-out` `:intent/for :retreat`; else a walk `:intent/for :retreat` to the end of a `:found` `:away` route of `retreat-distance` 12.
9. `climb`: `:stairs-up` `:intent/for :boxed`, `climb-to` top of world, unless `out?`.
10. `{:plan/wait :boxed}`.

Rules:
- `beside`: horizontal neighbour at the block's level or one lower, the cell under it, or with `step-down?` a neighbour one up (solid under the block, two clear over). Never straight above.
- Floor, head and ceiling blocks count (live run 14; batch iron-a2 run 4).
- Depths: iron peaks at y 16; coal absent below 0 (twelfth live run tunnelled 13 minutes at y 0).
- Boxed tunnels and descents are not restarted from the same cell (full run ab3945e; issue #105).
- Taught by: gym iron-a3/a4 (fixed heading retried), iron-a6 run 3 and iron-a8 run 3 (`deeper` into unsafe stairs), iron-a8 run 2 (260 blocks under the sea).

Flint (`:redig`, act of `:flint-from-gravel`):
- `plan/act :redig` wraps in `place/ashore`. Stages via `stone/link`: `:place` the held gravel (`:intent/passage-ok? true`), `:dig` it, `:collect` with the row's drops. Gravel back: the planner starts another `:redig`; no counter.
- Source detail: a child place failing `:no-spot` while afloat (`afloat-no-spot?`) swaps in a `:swim-out` for `:retreat` and places again from the bank (issue #95).
- One intent from place to pickup: as three intents, the planner went for more gravel instead of digging the placed one.

Waits and failures: `:no-pickaxe` (wait), `:boxed` (wait: nothing to cut, walk or climb), child reasons from walk, dig, collect, stairs, tunnel, place.

### 19.8 Scoop (`scoop/scoop-next`, `intent/run :scoop`)

The `:fill-bucket` row: needs an empty bucket, holds `{:tag/pickaxes 1}`, spares `{:tag/pickaxes 1}`, provides a water bucket; act `:scoop`, which `take-table` precedes. Surface water only: never cave water.

Order in `scoop-next`:
1. `sources`: plain water sources (`blocks/water-base`) open above (`surface-source?`), within `search-radius` 24, `surface-water?` (no more than `memory/surface-water-below` 5 under the eye, or under the open sky), not blacklisted. First in reach and `sees?`: a `:scoop`.
2. `first-walk :scoop` to the bank of the first of 3 routable sources.
3. `first-walk :scoop` to remembered pools within `pool-radius` 96 (`pools-near`).
4. `seen-source` (when `deep?`): a source in reach whose centre the eyes see, scooped with `:intent/aim` at its centre.
5. `from-below`: a source over the feet column under at most `bed-depth` 2 of rock; rock dug with `:intent/exposes-ok?`, then scooped from below.
6. `climb` (`:intent/for :surface`): when deeper than `underground-depth` 8 under `:plan/go-at` and the sky is not open: walk home, else `intent/opening`, else `:stairs-up` via `climb-to`.
7. `first-walk :water-hunt` over `hunt-targets`: eight bearings, ground `hunt-distance` 40 out (between `surface-above` 16 and `surface-below` 24 of the eye, loaded, under the sky), none nearer the landing than now, lowest first.
8. `pit-climb`: deep under the landing, sky open: `way-up` with `:intent/to-height?` (issue #60).
9. `to-bank` (`:intent/for :bank`): stairs up or down toward the seen water's bank height, then a `:tunnel` of `tunnel-out-length` 8 (issue #60).
10. `dig-out`: no open sky: `opening`, else stairs up until `out?` (live run 17, inside a hill).
11. `tunnel-out`: a `:tunnel` of 8 along `stone/tunnel-heading` (gym solid-b1 run 13).
12. `{:plan/wait :no-water}`.

The intent:
1. `:equip`: `place/equip` the bucket (fails `:not-held`).
2. `:aim`: look at `aim-point` (0.85 up the source); target no longer scoopable (or, with `:intent/aim`, no longer a source): fail `:target-gone`. After 3 still ticks and `settle-ms` 300: `use-item`, go `:sent`.
3. `:sent`: one more filled bucket than at start: done. After `answer-ms` 3000 aim again; `max-tries` 3: fail `:no-scoop` (source blacklisted by the planner).

Rules:
- `scoopable?` wants `blocks/air?` over the source; `sees?` wants every ray cell air or water. A plant on the ray is a wall (issue #83, sugar cane). Water under stone is no target (third live iron-kit run).
- `first-walk` drops targets whose walk is already arrived (gym water-a3 run 5).
- `boxed-here?`: no climb, pit climb or dig-out from a cell in `:plan/boxed-at` (issue #61).
- No "bucket in use" flag: only the inventory counts.
- Spare and table: gym iron-a6 runs 2, 5 and iron-a7 runs 1-3; race 38477665442 Clj_r19, r23, r06.

Failures: `:no-water` (wait), `:no-scoop` (three unanswered scoops: ray blocked or not a real surface source), `:target-gone`, `:not-held`.

### 19.9 Place, equip, open-container (`place.clj`)

`{:intent/kind :place :intent/item k}` puts a held block down; success only when the server shows the block. A given `:intent/cell` (build, stairs lay) is placed there; else a spot is found.

Stages:
1. `:equip`: `equip`: held → go on; hotbar → `set-carried-item`; main inventory → window-0 swap click; none → fail `:not-held`.
2. `:spot`: given cell with a `support` neighbour → `:settle`; given cell without one → fail `:no-support`. Else `spot` (passage refused), else `niche` (dug first as a child dig, tool equipped first if stowed), else with `:intent/passage-ok?` `spot` in a passage, else fail `:no-spot`.
3. `:settle`: a chosen target no longer replaceable → back to `:spot`. 3 still ticks and `settle-ms` 300 → `use-item-on`, `:sent`.
4. `:sent`: block name at target → done. Acked (and 500 ms passed) with the cell still replaceable and fewer than `max-refusals` 3 refused → `give-up` (another spot; issue #106). Acked otherwise → fail `:rejected`. `answer-ms` 3000 with no answer → fail `:no-answer`.

Rules:
- `spot`: rings 1-3 round the feet (levels 0, -1, +1), target replaceable and `terrain/confirmed?`, support solid and not a placed container, not a `passage?`, not inside the player, within `place-reach` 4.0. Flowers are not replaceable (Clj_r23).
- `niche`: a wall cell at feet or head level, solid above and below, nothing falling over it, no fluid beside, droppable by held tools, not obsidian or a placed block; side nearest the landing, never ahead. Issues #94, Clj_r12, Clj_r14.
- `dig-niche` waits for the server's confirm, or `answer-ms` after FINISH, before placing (Clj_r01, Clj_r07).
- `place/ashore`: in water, a `:swim-out` for `:retreat` first (issue #95).
- `:equip` intent: done when held. `:open-container`: fail `:out-of-reach` beyond `open-reach` 4.5, `:no-window` after 3000 ms.

Failures: `:not-held`, `:no-support`, `:no-spot`, `:rejected`, `:no-answer`, niche child reasons.

### 19.10 Build (`build.clj`)

A build is a vector of ops; each op has a done-predicate as data (`build/holds?`). The first op not satisfied is worked (`next-op`); nothing about progress is stored, so a build resumes from the world after death or relaunch.

Ops: `:build/op` in `:place :dig :pour :scoop :use-on :stand-in :verify`; `:build/pos`, `:build/stand`, `:build/item`, `:build/aim`, `:build/done`. Predicates: `[:solid p] [:not-solid p] [:firm p] [:filled p] [:block p name] [:lava-source p] [:no-water p] [:dimension name] [:or ...] [:and ...]`.

`portal-plan origin facing h` (issue #124, the rock is the mould):
1. `room-ops`: landing, room (two rows six high, the five-step `stair`, `access` steps up to `h`), floors made firm.
2. `cell-ops` per frame cell in `layers` order (bottom up, never sorted): walls made firm (`firm-ops`), hole and cell over it dug, lava poured low, water poured into the cell directly over the lava, water scooped back.
3. `interior-ops`, `light-op` (flint and steel), `enter-op` (stand until the Nether).
`cast-one-plan`: room and first cell plus a `:verify`.

The `:build` intent: each tick `plan-for` (memoized), `next-op`, `work-op`. `op-child`:
- Lava pour with no lava bucket → `refill`. Water pour with other lava touching the cell → `:stray-lava`.
- `approach-stand` gets the feet to the stand (`reachable-stand`, `room-stand`, `toward-stand`, `stance-block`).
- `:place` into `foundation` (built up to `foundation-depth` 4) or `:no-support`; `:dig` with `:intent/exposes-ok?`; `:pour` / `:use-on` (equip, aim, use, wait `:intent/expect`); `:scoop` with `:intent/aim`; `:stand-in` → `:await`; `:verify` → `:unverified`.
- `work-op`: `:no-station` when the pool is `spent?` ends the build done (new site chosen); any other keyword fails it.

Site and station:
- `choose-site`: beside the `max-sources` 3 nearest lava sources, any depth; `stands-beside`, facings, `station-spots`; `site-ok?` wants the footprint loaded, no fluid in or touching it, and a pool not `spent?` (at least as many reachable sources as cells left). Then fewest `blocks-needed` (at most `site-blocks` 8), then nearest. Issue #142 (lone source, `:no-station` with nine to go).
- `refill`: `station` (`station-among`: 4 to `station-depth` 9 in front, within `station-span` 8 aside, sources in reach). At the stand: scoop the nearest seen source, else dig `sight-block`. Returns `:no-station` or `:no-bucket` when it cannot.
- `plan/next-intent :build`: `held-first` (the row's `:goal/holds` plus cobblestone counted by `blocks-needed`; issue #70) via `make/decide`; then `:build` at the remembered site unless spent, else a chosen one; else `lava-hunt` (`lava-walk`, `uncap`, `toward-lava`; issue #41), else wait `:no-site`.

Rules: a dig never takes obsidian; water above the lava, never beside; the landing is reached at the lava's height (`:no-stand` otherwise); every worked cell is in `footprint`; `kept` cells are never dug for a way; a stand over water is placed from shore (issue #45).

Failures: `:no-site` (wait), `:no-station`, `:no-bucket`, `:no-stand`, `:no-support`, `:stray-lava`, `:no-effect` (pour or use-on with no effect in 3000 ms), `:not-yet` (`:await` past `await-ms` 10000), `:unverified`, child reasons.

### 19.11 Escape (`trap.clj`, `swim.clj`)

Two always-on priority-0 targets preempt whatever runs. The trap is re-derived each tick with hysteresis.

The trap (`trap/trapped-tick`):
- Lava: any contact (`:player/in-lava?`).
- Water: `:plan/wet-ticks` (eye in water) reaches `trap-after-ticks` 20. Wading never preempts.
- Clears after `dry-land?` holds for `dry-ticks-to-clear` 6 ticks. `dry-land?`: on ground, body dry, eye in air, solid non-lily-pad below.
- On clearing, `{:trap/pos :trap/kind :trap/at}` is appended to `:world/traps`; `path/trap-factor` makes routes within `trap-reach` 2 columns cost `trap-cost` 10x for `trap-ttl` 600000 ms.

`:swim-out` stages:
1. `banks`: standable cells within `bank-radius` 16, from `bank-drop` 3 under to `bank-rise` 1 over `float-top` (`float-depth` 1.03 under an open surface), not blacklisted. Kept once picked.
2. No bank: dig the cap (`obstacle`); else `notch` a wall within `notch-radius` 4 (never under a roofed surface, issue #116); else hold jump, look again every `search-every-ticks` 20.
3. `swim-controls`: forward and jump in water, walk out, nothing on dry land.
4. Stuck (`stuck-ticks` 40 without `progress` 0.05): dig `obstacle` (lily pad, head-height ahead, over the head; never feet-height ahead). Source detail: no obstacle fails `:stuck`.
5. Child digs via `dig-afloat` (jump held under an open surface, issue #73) or `dig-or-sink` (sneak to the floor when `dig-from-floor?`, `:intent/dig-when-down`: 5.75 s instead of 28 s).
6. More than `escape-timeout-ticks` 2400: fail `:no-escape`. A `:retreat` swim-out ends itself on `dry-land?`; an escape's is ended by its goal.

`:flee`: `footing` within `flee-radius` 6, one up or down, no lava beside or under; none or past `flee-timeout-ticks` 200: fail `:no-footing`.

`:wade` (gym `:shore`, priority 5, done by `:escaped-water?`): `entry` over pools within `wade-radius` 64 and `wade-below` 16; child walk to the bank; then head on past the water. Source disagrees with the note: `wade-beyond` is 4 blocks ahead of the feet once wet (the note says 8 past the water cell; the source comment says 8 was not enough). Fails `:no-water` (no entry, or `wade-timeout-ticks` 2400), `:shallow` (walked out the far side). Planner waits `:in-water` or `:no-water`.

Dig refusals: `:unsafe` beside a fluid unless `:intent/exposes-ok?`; obsidian never. Issue #92 (scooped pond's edge, notch the bank).

Failures: `:no-escape`, `:stuck`, `:no-footing`, child dig reasons.

### 19.12 Pathfinder (`path/plan`)

`(path/plan world from goal opts)` → `{:path/waypoints :path/cost :path/status :found|:partial|:none :path/from}`. Pure A*; budget in node expansions, so recordings replay.

Rules:
- Cell classes (`classify-id`): `:water`, `:lava`, `:awkward` (partial shape taller than `flat-shape` 0.125, or `awkward-types`: slime, ice, magma, cobwebs), `:solid`, `:clear`, `:unknown` (unloaded). `one-layer-snow` is `:clear` (issue #38).
- Moves (`templates`, rotated to 8): `:walk` 1, `:jump-up` 2, `:diagonal` sqrt 2 (both corners clear), `:drop` 1 + 0.5 per block, at most `max-fall` 3. `landing` refuses water, lava, awkward or unknown under a fall.
- `clear?` excludes lava in the four sides or under, so every move has the lava rule. Water is a wall, never a floor.
- `start-cell`: the feet cell if standable, else the nearest within one block (path-a1 run 5, block edge).
- Goals (`goal-done?`, multimethod with the world): `:near` (range 3), `:block`, `:xz`, `:away`, `:pickup` (`:goal/rise`, `:goal/step-down?`), `:sky` (heuristic weighted by `sky-weight` 4, not admissible).
- `default-max-nodes` 6000. `:partial` when spent, `:none` when the open set empties; both return the route to the nearest node seen. Unknown cells stop a route at the loaded edge.
- No parkour, no digging, no scaffolding.

### 19.13 Fortress tunnel (`fortress.clj`)

`:fortress` (`:goal/dimension "minecraft:the_nether"`) is done by `:at-fortress?`: a brick on record at y >= `brick-min-y` 32 within `reach` 6. Planner: `:tunnel-to` the nearest brick within `sight-radius` 512, else `exploring` `explore` 48 along the first safe axis; outside the Nether wait `:other-dimension`; nowhere safe wait `:no-way`.

Rules:
- `step-plan feet move`: ops (floor placed under the new cell, `opens` dug, stand in the new cell), worked by `build/next-op` and `build/work-op`.
- `safe?` refuses unloaded cells, lava or `unbreakable` cells opened, lava beside an opened cell, a floor that is neither solid nor lava (live run f1-5 fell into the lava sea).
- `moves`: the axis with farther to go, vertical first when more than `climb-past` 3 off, then level, the other axis, sideways.
- Done at the target or after `steps-per-tunnel` 32 steps.

Failures: `:blocked` (no safe move), child reasons; waits `:no-way`, `:other-dimension`.

### 19.14 Intentions as facts (`memory.clj`)

Every intent's story is in memory, never retracted.

- `plan/start-intent` numbers the intent (`:intent/id` from `:plan/intents`) and appends `:started`; `finish-intent` `:done`; `fail-intent` `:failed` with `:intention/reason`; `:abandoned` on a new `:go`, `:goal-met`, or `:preempted`.
- `memory/intentions`: the whole story, oldest first. `memory/intention-as-of world t`: the newest intention still `:started` at t.
- Server answers: `{:answer/kind :ack :answer/sequence n}`, `{:answer/kind :pickup :answer/entity :answer/count}`, `{:answer/kind :death :answer/at}`; read with `memory/answers`.
- What the bot was doing when it died is `intention-as-of` the tick before the death.
- Intents without `:intent/id` (hand-built in tests) are not recorded. Facts carry `:time/now`, so replays build the same story.

---

## 20. The world, the protocol and the game, for a fixer

This section is reference for reading a failure. It comes from the brain notes (`brain/bot/*`, `brain/game/*`, the glossary, rules and conventions), checked against `src/clojurecraft/` at 4d6122b. Where a note and the source disagree, the bullet follows the source and is marked **(source)**.

### 20.1 The design decisions that constrain a fix

Each bullet gives the choice, then the reason. A fix that breaks one of these is wrong, even when it makes the symptom go away.

- **One world value in one atom.** Keys are flat and namespaced (`:player/pos`, `:world/chunks`, `:plan/intent`). An unknown attribute is absent, never nil (`:player/pos` appears at the first teleport). Only `main/apply-event!` writes it, and `spec/::world` requires only `:bot/phase :bot/effects :time/now :time/tick`. Reason: one value can be recorded, diffed and replayed.
- **Pure reducer, effects as data.** `(game/step world event)` returns the next world, and sends and logs go onto `:bot/effects` for the loop to perform. No reducer reads a clock or calls `rand`: both arrive on the tick as `:event/now` and `:event/rand`. Reason: replay. Any new source of nondeterminism must be an event field; the first walk intent called `rand-nth` and replays diverged.
- **The phase changes in one place.** `game/emit` queues the packet and flips `:bot/phase` through `packet/transitions`. A handler that sends without `emit` skips the phase bookkeeping.
- **Goals in play arrive on `:go`.** The event carries `:go/goals` (and `:go/chain? true` on a fresh run); `plan/begin` stores `:plan/goals`, and `plan/goals-for` reads `:goal/until` from the table. Reason: a CLI flag or dynamic var is a hidden input a recording would not contain.
- **Chained runs use milestones.** A reached target joins `:plan/reached` (`plan/reach`), and `choose` skips it from then on. Without this, the first chained run spent the kit's sticks on the pickaxe, went back to `:kit`, and crafted a stick twice a second for five minutes. `:portal-kit` counts the filled water bucket beside the empty one (race 38477665442, issue #49).
- **Memory is facts in DataScript.** `:world/facts` holds append-only observations `{:sight/pos :sight/state :sight/at}`, never retracted. Reason: the plain map it replaced overwrote history (a dug log left no trace), and every question was a hand-written scan. `positions-now` is one Datalog `not-join`: 3.9 ms against 80 ms on 4,534 facts. Cost: the unit suite went from 9 s to 19 s.
- **The protocol is written from scratch.** Protocol 775 is specs in `packet/specs`, one interpreter, JDK sockets and zlib. Reason: MCProtocolLib is a class per packet with mutable sessions, and its 26.1.2 support could not be confirmed. Never add a Java client library.
- **Predict nothing.** Every `container-click` is sent with `:changed [] :cursor nil`, and the world changes only on `container-set-slot`, `container-set-content` and `set-cursor-item`. Reason: a predicted window is a second copy of the truth, the steve wood-lock class of bug. Cost: a lay-out takes about clicks x RTT, never measured as a bottleneck.
- **Manual clicks, not `place-recipe`.** `packet/specs` has no `place-recipe` spec; id 39 is only in `packets.edn`. Reason: it needs display ids from `recipe-book-add`, which we cannot decode, and vanilla places a ghost recipe when ingredients are missing.
- **One stack per ingredient.** `recipe/sources` takes every cell of a group from one stack that covers it, else from several stacks, lowest first (race 38477665442, issue #86: seven plus one cobblestone gave `:no-ingredient` three times). It never reads the grid.
- **One item per cell, and a shift-click take.** One `:craft` intent is one craft. Slot 0 is taken (mode 1) only when it shows exactly `{:item result :count makes}`. Reason: "make all" eats the planks the next craft needs, and a take onto the cursor risks a drop on close (typecraft lost 14 planks).
- **The grid has its own attribute.** Window-0 slots 0-4 live in `:window/grid`, never in `:player/inventory`, and counts read the inventory only. Reason: dropping grid slots strands items (the wood-lock), and counting them double-books what the server may turn into a result.
- **The server judges placement.** `use-item-on` leaves the world alone, and a placement is done only on the server's `block-update`. Reason: ruststeve's predicted blocks became ghost blocks, and steve measured 15-30% of placements rejected.
- **Placement spots are searched in rings.** `place/spot` tries rings 1-3, at feet level, then one down, then one up, with the AABB test `inside-player?` and the target within 4.0 of the eye. Reason: the first design (twelve cells exactly two away) failed `:no-spot` in CI on an uneven forest floor.
- **A natural tree, not a fixture.** The harness runs `locate biome minecraft:forest` with seed `typecraft` and `level-type` normal. Reason: movement is tested from day one, and the first CI run took 1m40s.
- **Public by default.** Functions are `defn`; `defn-` is kept for one-line aliases, and `docs_test` requires a docstring on every public function. Reason: REPL reach into `plan/run-intent`, `sim/sync-window` and `game/load-chunk` when a recorded race goes wrong.
- **Sets over arrays.** Block predicates are `contains?` on sets of state ids, with type hints only on interop. Reason: replay medians were 155 ms for sets against 169 ms for arrays, no difference.
- **Remembered by identity.** `memo/by` keys parts by `identical?`: `memory/positions-in [facts states dim]`, `stone/exposed-cells`, `path/plan-on`. `path/cells` reads each cell once per search, and `terrain/superseded` keeps the overlay identical when a new column touches none of it. Reason: in a ten-bot profile `plan-tick` was 49% of samples; the bots' JVM fell from 12.7% to 4.3% of the machine. In a fix, keep the clock out of memo keys and the overlay identical when nothing changed.
- **One planner.** `make/next-intent :needs` is the only crafting planner; `recipe/next-action` was removed. Fix the needs planner; never add a second answer to the same question.
- **Any log species.** `:gather-log` provides `{:tag/logs 1}`, and candidate rows sort by how many needs are already held. Limit: `blocks/log-items` matches only names ending `_log` (no `*_wood`, no `crimson_stem`).
- **The information model is data.** `model/attributes` has about 120 rows `[attribute meaning when]`, enforced by `every-key-a-running-bot-writes-is-in-the-model` and `every-attribute-in-the-model-has-a-spec`. A new key needs a row and a spec. A meaning never changes; a better representation is a new attribute.
- **Packet specs only grow.** `packet-specs-only-grow` checks against `test/clojurecraft/packet_specs_ever.edn`. You may append a field, never remove one or change its type.
- **Goals are superseded, never edited.** Add a new row, move `:goal/until` to it, and set `:goal/superseded-by` on the old one; `plan/current-id` follows the chain, so old recordings still plan.

### 20.2 The world value and how to ask it

The namespaces group attributes by owner: `bot/*` (identity, phase, effects, `:bot/sequence`), `conn/*`, `time/*`, `player/*`, `control/*`, `window/*`, `world/*` (chunks, blocks, unconfirmed, facts, entities, traps), `plan/*`, `intent/*`, `net/*` (last sent) and `stats/*` (teleports, chunks, unknown, pickups, last-ack, deaths).

#### Terrain (`terrain.clj`, `chunk.clj`, `blocks.clj`)

- `:world/chunks` maps `{[cx cz] column}`; a column is `{:sections [...] :chunk/min-y y}`. The overworld has 24 sections from y -64; the Nether and End have 16 from y 0 (`dimension/min-y-of-sections`).
- A section is `{:single id}` or `{:bits n :palette [...] :longs long[]}`; the palette is nil above 8 bits (direct ids). The index is `(y<<8)|(z<<4)|x`, with `64/bits` values per long, entries never straddling longs, and no long-count prefix in 26.x.
- `chunk/attach` decodes on the reader thread (`:chunk/column`); `game/load-chunk` decodes the bytes itself when the key is absent (replay, sim). A decode error is logged, never thrown.
- `(terrain/block-at world pos)` reads the overlay `:world/blocks` first, then `chunk/block-at`. **nil means not loaded or outside the column, never air.**
- The overlay has three writers. `set-block` takes the server's word (`block-update`, or `section-blocks-update` through `section-update`), confirms an unconfirmed cell and calls `memory/observe`. `predict` records the bot's own guess (the dig's silence overlay) in `:world/unconfirmed {pos time}`. `forget-stale` runs every tick and drops guesses older than `unconfirmed-ms` (3000); `confirmed?` asks whether a cell is not a guess.
- `put-column` replaces the column, drops overlay and unconfirmed entries inside that chunk, and records sightings and pools; `superseded` keeps the overlay's identity when the column held none of its entries.
- `solid-fn` treats unloaded (nil) as solid, so the bot never falls out of the world. Separately, `blocks/solid?` of an id the table does not know is false: two different questions.
- `fluid-fn` / `fluid-at` return `{:fluid/kind :water|:lava :fluid/level 0-15}` or nil; unloaded means no fluid. `blocks/fluid` also treats kelp, seagrass and bubble columns as a water source.
- `chunk-loaded?` asks whether a column is held. `under-sky?` asks whether only air, plants or leaves lie above, up to `top-y` (319); an unloaded column is not under the sky.
- `blocks/passable-types` lists about 100 definition types without full-cube collision. `grass` (the type of `grass_block`) is deliberately absent and `tall_grass` present; `nether_portal` is passable (#8). Leaves are solid; slabs, stairs, fences, walls, doors and snow blocks are full cubes; `snow_layer` is passable.
- `blocks/replaceable?` is the bot's own spot rule: air, grasses, ferns, dead bush, seagrass, vines, roots, lichen; never a flower, flower bed, bush or sapling. `blocks/placeable-into?` is the server's wider rule: those plus any fluid (issue #44).
- State ids at 26.1.2: air 0, cave_air 15293, stone 1, grass_block 8-9, dirt 10, water 86-101, lava 102-117, oak_log 136-138 through mangrove 160-162, oak_leaves 252-279, `:item` entity type 71. Ids change per version; regenerate with `bb datagen`.
- Route classes come from `path/classify`: `:water :lava :awkward :solid :clear :unknown`. `:unknown` is never floor and never clear, so a route stops at the loaded frontier.

#### Memory (`memory.clj`)

`:world/facts` holds five kinds of fact:

- **Sightings** `{:sight/pos :sight/state :sight/at :sight/dimension}`, appended by `observation` only when the state differs from the last fact there; one without a dimension is the overworld's. `watched?` starts a record for logs, `placed-kinds` (`#{:crafting_table :furnace}`) and `landmark-kinds` (fortress bricks, fences, stairs, a lit portal). Stone never: recording it froze every chunk load. A position on record is followed through every change.
- **Pools** `{:pool/chunk :pool/kind :pool/pos :pool/at :pool/dimension}`: one per chunk and kind, the highest surface source nearest the chunk centre, appended on change (293 facts against 600,000 water cells).
- **Intentions** `{:intention/id :intention/event :started|:done|:failed|:abandoned :intention/kind :intention/at}`, plus target, recipe or reason.
- **Answers** `{:answer/kind :ack|:pickup|:death :answer/at}`, plus `:answer/sequence`, or `:answer/entity` and `:answer/count`. A broken block needs no answer: it is a sighting of air.
- **Build sites:** `remember-site` writes `{:site/origin :site/facing}`, and `site` reads the latest (#8).

The queries, each asked in the bot's dimension (`dimension/of`, rules `in-dimension` and `pool-in-dimension`):

- `remembered world pos` (latest state), `history world pos` (oldest first), `latest world` (`{pos {:block/state :block/seen-at}}`).
- `positions-now` (memoised as `positions-in`): positions where a state in the set was seen and nothing outside the set since.
- `nearest world eye radius pred`; tables go through `positions-now`.
- `nearest-log`: the nearest trunk bottom within radius (48, `wood/search-radius`), skipping logs more than 12 blocks above or below the eye. Blacklisted positions are removed before `trunk-bottom?`, so a blacklisted trunk is skipped whole.
- `trunk-bottom?` (a remembered log with none below); `wood/trunk-target` takes the log on that trunk nearest feet height.
- `pools world kind`; `nearest-surface-water`, never more than `surface-water-below` (5) under the eye, since deeper water is cave water, the aquifer trap.
- `answers world kind`, `intentions world`, `intention-as-of world t` (what the bot was doing at `t`, for example the tick before a death).

Memory's edges:

- **Air is sticky:** a position on record keeps every later state.
- **A chunk load records logs but not air,** so a log broken while unloaded stays a log until an update or the bot's own dig.
- **A chunk load clears the overlay, not memory.**
- **Time zero:** `:time/now` is 0 until the first tick, so the first sightings carry 0. There is no eviction.
- **Terrain answers "is water beside me now", never memory;** memory's pools answer "where can I walk for water".

#### Physics (`physics.clj`)

- **The step:** `(physics/step solid? fluid-at world controls)`; the three-arity form passes `no-fluid`. `game/on-tick` runs it only when `game/physics-ready?` (play, positioned, loaded, in a loaded chunk) and not `:player/dead?`.
- **Controls** are `:control/forward? :control/jump? :control/sneak? :control/yaw :control/look`. `:player/controls` is state that persists, not an effect.
- **It writes** `:player/pos :player/vel :player/on-ground? :player/horizontal-collision? :player/jump-ticks :player/in-water? :player/in-lava? :player/eye-in-water? :player/fall-distance`.
- **One tick:** jump, input acceleration along yaw, an AABB sweep on y then x then z against full cubes, then gravity, drag and friction.
- **Constants:** `tick-ms` 50, `gravity` 0.08, `vertical-drag` 0.98, `half-width` 0.3, `height` 1.8, `eye-height` 1.62, `jump-velocity` 0.42, ground inertia 0.546, air 0.91, `air-acceleration` 0.02, `ground-acceleration` about 0.1, `negligible` 0.003, jump cooldown 10 ticks, forward input 0.98, `bank-probe` 0.6.
- **Pinned behaviour:** walking 0.216 blocks a tick; a jump peaks 1.2-1.3; resting vy is -0.0784, never 0. No step-up, sprint or strafe; non-full cubes are full cubes. Look: south yaw 0, east yaw -90, looking down is positive pitch.
- **In water and lava:** `in-fluid?` contracts the box by `water-contract` (0.001 0.401 0.001) or `lava-contract` (0.1 0.4 0.1); `eye-in-water?` tests the block at y+1.62, never feet+1. Constants: `liquid-acceleration` 0.02, `water-inertia` 0.8, `water-gravity` 0.005, `lava-inertia` 0.5, `lava-gravity` 0.02, `swim-up` / `swim-down` 0.04.
- **The only lift out of water** is `out-of-liquid-impulse` (0.3): it fires when the body collided horizontally and the box moved by `[vx (+ vy 0.6) vz]` would be free. Swim-up alone never clears a one-block bank. The body floats with feet 1.03 under the surface and moves 0.1 blocks a tick. Flow currents (`getFlow`) are **not modelled**: a river does not push the bot.
- **Fall distance** accumulates while vy is negative out of a liquid and resets on landing or in a liquid; nothing consumes it yet.
- **Breath:** `game/breathe` keeps `:player/air` within 0-300, minus 1 a tick with the eye under, plus 4 otherwise. It is an estimate: own-entity metadata is not decoded.
- **Movement packets:** `move-player-pos-rot` on change, `move-player-status-only` every 20 ticks otherwise. The flags byte is `0x01` on the ground; typecraft's `0x80` is wrong.

#### Paths (`path.clj`)

- **The call:** `(path/plan world from goal opts)` returns `{:path/waypoints :path/cost :path/status :found|:partial|:none :path/from}`. The budget is node expansions (`max-nodes` 6000), not ms, so a replay finds the same route.
- **Moves:** `:walk` 1, `:jump-up` 2, `:diagonal` √2 (both corners clear), `:drop` 1 + 0.5 per block up to `max-fall` 3. A drop needs a `landing`; water, lava, an awkward block or an unloaded chunk under it refuses it.
- **Cells:** `clear?` refuses lava in the four sides or below; `standable?` needs a solid floor and clear body and head. `start-cell` searches from a neighbour when the feet sit on a block edge (batch path-a1 run 5).
- **Goals:** `:near :block :xz :away :pickup :sky` through the `goal-done?` and `heuristic` multimethods; `:sky` weights height by `sky-weight` 4 and is not admissible. `trap-factor` multiplies cost by 10 within two columns of a trap in `:world/traps` younger than ten minutes.
- **Never:** water as floor, parkour, digging through, scaffolding. Awkward blocks are routed around. One layer of snow is `:clear` (issue #38: 39 bots froze on a snowfield).

### 20.3 The protocol

#### Packets and specs

- A packet is a flat map with `:packet/name` and the wire's field names, unqualified. Never a bare `:name` field (`packet-name-collision`): the hello field is `:username`.
- `packet/specs` maps `[state dir name]` to `[[key type] ...]`; **65 rows (source)**, where the packet-table note counts 58 at 18afddc. Types are primitives (`:varint :f64 :string :uuid :position :bytes :rest :slot` ...), `[:vec T]`, or a nested pair vector for a struct. `packet/values` names enum numbers (`:dig :face :hand :next-state :client-command :chat-mode :main-hand :particle-status :game-type`); a missing name throws.
- `decode` reads only listed fields and ignores the rest of the frame. `encode` is c2s only and throws on a missing spec. Ids come from `resources/clojurecraft/packets.edn`, generated by `bb datagen`; never hand-edit it.
- Three synthetic packets: an id with no spec becomes `{:packet/name :unknown :packet/id n :packet/known name}`, counted in `:stats/unknown`; a decode exception becomes `:decode-error` (logged); a socket failure becomes `:closed`, which sets `:bot/closed`.
- A `:slot`'s components decode through `component-codecs` (damage, max damage, max stack size, repair cost as varints; unbreakable empty). Any other component truncates the rest of that packet (`:truncated true`), with item id and count still read. Before the codecs, a worn pickaxe hid the water bucket behind it (gym batch iron-a2 run 3, `:no-scoop`).

#### Phases and login

- Phases run `:handshake -> :login -> :configuration -> :play`, and back to `:configuration` on `start-configuration`. `packet/transitions`: `intention -> login`, `login-acknowledged -> configuration`, `finish-configuration -> play`, `configuration-acknowledged -> configuration`. `conn`'s writer flips its copy after encode and before write; `game/emit` flips `:bot/phase`. Same table, no shared state.
- `hello` carries `:bot/uuid`, the MD5 v3 UUID of `OfflinePlayer:<name>` (`bytes/offline-uuid`); the server kicks on a mismatch. `login-compression` sets the threshold inside `conn` (default 256) before the next frame is read, and the game ignores it. Frames are `[len][data]`, or `[len][size][payload]` with size 0 meaning raw. No encryption: offline servers.
- Replies (`game/on-packet` on `[phase name]`): `login-finished` gets `login-acknowledged`; `select-known-packs` gets `[]`; `finish-configuration` is echoed; `login` gets `client-information`; `keep-alive` and `ping` are echoed in both phases; `chunk-batch-finished` gets `chunk-batch-received 20.0`; `start-configuration` gets `configuration-acknowledged`.
- `player-position` goes to `apply-teleport`: relative flag bits 1/2/4 for x y z and 8/16 for yaw and pitch, velocity zeroed, `:player/synced-at`, then `accept-teleportation` and `move-player-pos-rot`. The first teleport also emits `player-loaded`; without it the server never finishes loading the player, and nothing, pickup included, ticks.

#### The connection and effects

- `conn/open!` starts reader and writer threads, with `:in` buffering 1024 and `:out` 256. Bounded channels back-pressure TCP and drop nothing. 10 s connect timeout, TCP_NODELAY.
- Varints sign-extend to int32 (-1 is `ff ff ff ff 0f`). A position packs x 26, z 26, y 12 bits in wire order x, z, y. `section-blocks-update` packs x 22, z 22, y 20 (decoded in `terrain/section-update`).
- An effect is `{:effect/kind :send :effect/packet p}` or `:log`. `apply-event!` swaps, reads the effects, clears them in the atom, then performs each, so a reducer can assume `:bot/effects` is empty when a step starts. `game/compose` passes the same event to every reducer.

#### Block updates, acknowledgements and sequences

- `block-update` goes to `terrain/set-block`, `section-blocks-update` to `terrain/section-update`, and `block-changed-ack` sets `:stats/last-ack` and appends an `:ack` answer.
- `:bot/sequence` is incremented for each `player-action` (START, FINISH), `use-item-on` and `use-item`. An answer has come when `:stats/last-ack` is at or past ours.
- **After a break**, the breaker gets `block-update` (state 0) and then the ack, in that order (seen live on 26.1.2). **An ack alone means not broken:** the server queued an early FINISH behind another one and dropped it, or refused and put the block back.
- **Dig confirm** waits up to `confirm-ticks` (60, 3 s) after FINISH. The target leaving its START state ends the dig. An ack with the block standing is waited on, since under delayed destroy a loaded server breaks seconds later (#64). A block still standing at 60 ticks fails `:rejected`. Total silence trusts the overlay via `terrain/predict`.
- **A placement** is done on the `block-update`. When the ack reaches our sequence and 500 ms pass without it, a cell answered empty is given up (`place/give-up`, `:intent/refused`, up to `max-refusals` 3), else `:rejected`. No answer at all fails `:no-answer` after 3000 ms.

#### What is never predicted

Placed blocks; window slots and the cursor (the bot claims empty and drops `:window/cursor`); a break the server acked but did not do; pickups (the client sends nothing, and `take-item-entity` with our id as collector appends a `:pickup` answer); health (from `set-health`; air is only estimated). The single guess is the dig's silence overlay, kept in `:world/unconfirmed` until the server's update or 3000 ms.

#### Death, and what is not modelled

- `set-health <= 0` calls `game/die`: `:player/dead? true`, controls cleared, `:stats/deaths`, a `:death` answer, `:plan/intent` and `:plan/last` dropped, and `client-command :action 0` emitted. `respawn` resets dead, entities, velocity, fall distance and air. `change-dimension` drops columns and overlay and sets `:player/loaded? false`, so `player-loaded` is sent again. A hand-built `{:packet/name :respawn}` fails the spec; use `fixtures/respawn`.
- Not modelled: `attack` (c2s 1), `place-recipe` (39), `set-time` (s2c 113) and every other id missing from `packet/specs` (live, they arrive as `:unknown`); entity metadata, so the server's air value is never read; encryption.

### 20.4 The loop, recording and replay, telemetry

#### The loop

- `main/step` is `(game/compose game/step plan/step)`. `run-loop!` runs `alts!!` over conn's `:in`, the events channel (buffer 16) and a timeout to the next tick on a fixed 50 ms grid: `{:event/kind :tick :event/now (System/currentTimeMillis) :event/rand (rand)}`. A nil from `:in` becomes `:closed` "socket closed".
- `-main` defaults: host 127.0.0.1, port 25571, name `Clj_wood`, `--until wood`, timeout 120 s, hold 0. With `--events stdin`, `read-events!` checks each EDN line with `external-event` and drops a bad one; otherwise `go-when-loaded!` puts `:go` the first time `:player/loaded?` holds, without writing the world.
- `stop?` holds on the goal predicate (`plan/done?`, or `:player/loaded?` for `--until play`), a failed plan, or the wall deadline (checked outside the reducer), as do `:bot/closed` and `:bot/disconnected`.
- **RESULT** is one stdout line. `:ok` is `main/ok?`; `:reason` is `:goal`, `:disconnected`, `:closed`, a `:plan/reason` (`:no-log`, `:stale-window`, `:stuck`, `:no-landing` ...) or `:timeout`; `:held` is `recipe/counts`; `:plan` and `:stats` hold the `plan/*` and `stats/*` keys. Exit 0 when ok, else 1. CI greps for `:ok true` on a line starting `RESULT {`. The hold for an outside judge is not recorded.

#### What a recording holds (`--record path`, `record/tap!`)

- An optional first line `{:record/commit sha}`, written when `--commit` is given (the gym passes its own commit).
- One EDN line per event (`:start` with `:start/host :start/port :start/name`, packets, ticks, `:go`, `:closed`), written through `record/wire`, which drops the derived `:chunk/column` and keeps the wire bytes. Byte arrays print as `#clojurecraft/bytes "base64"`.
- After any event that produced effects, `{:record/effects [...]}` (it prints as `#:record{:effects ...}`, so grep that form). Last comes `{:record/result ...}`: the recording ends at RESULT.
- The tap runs before the swap, so the file is exactly what the reducer saw. Its channel (`tap-buffer` 4096) blocks the loop rather than drop a line. `.gz` files read directly (`record/entries`).

#### How replay verifies it

- `clojure -M:replay path` folds `main/step` from `game/init` as `Clj_replay`. `record/verify` compares each event's `:send` effects (`record/sent`; logs ignored) with the recorded ones: `:replay/effects :identical`, the first `{:record/mismatch {:index :event :recorded :replayed}}`, or `:record/no-effects` for old files.
- `main/replayed` judges the fold with the run's own `--until` and `ok?`: `:replay/result :identical`, the recorded result to compare, or `:not-recorded`. `:replay/commit {:recorded :running}` and `:replay/code :same|:other|:unrecorded|:unknown` say whose code folded it, with a stderr warning at `:other`.
- `bb replay <file>` folds at the recording's commit in `.worktrees/<sha>`; `--here` uses the working tree on purpose. Issue #111: a file folded with newer code looked truncated, but it was whole and replayed `:identical` at its own commit (1d31d21).
- `dev/clojurecraft/probe.clj` has `fold` (first world a predicate holds of), `at-ms` (a clock time), `story` (every intent's start, end and reason from memory) and `around` (slices round the feet: `#` solid, `.` clear, `W` water, `L` lava, `~` awkward, `g` falling). Run `clojure -M:dev -m clojurecraft.probe story run.edn`, or `... around run.edn <ms>`.
- **Sim snapshots:** `simrace/snapshot` writes `<set>-<n>-<seed>.edn.gz` (bot world and model state one tick before the stop). `issues_test` plays each on for two simulated minutes and fails if the signature recurs. `bb issue-check fetch <n>` fetches them into `test/issues/<n>/`, and `clojure -M:simrace --snapshot f` plays one by hand.

#### Telemetry (`--telemetry path`, `watch.clj`)

- A watch on the atom diffs whole successive values (`watch/changes`): plain attributes under `:telemetry/changed`, maps under `:telemetry/patched` with only the changed entries. `ignored` skips the clock, effects, chunks, facts and the overlay.
- Lines are offered (`a/offer!`) to a channel of 1024, so a slow writer drops and counts lines, never slowing the loop. Telemetry is a view: it is not recorded, and a replay never needs it.

### 20.5 Windows, crafting and smelting

#### Attributes

- `:window/grid` holds window-0 slots 0-4 verbatim, and `:window/state-id` window 0's latest state id.
- `:window/cursor` is absent when empty. `carried` and `set-cursor-item` set it; every `window/click` drops it.
- `:window/open` is `{:window/id :window/menu-type :window/slots :window/state-id}` plus `:window/data {property value}`. `open-screen` creates it, and `container-close` either way drops it.
- `:player/held-slot` is 0-8; a `set-held-slot` outside that range is ignored.

#### Layouts (`inventory/layouts`, shared with the sim)

| Window | Own slots | Player slots | Hotbar starts | Menu type |
|---|---|---|---|---|
| Window 0 | 0 result, 1-4 grid, 5-8 armour (head to feet = player 39-36) | main 9-35, hotbar 36-44, offhand 45 (46 slots) | 36 | none |
| Crafting table | 0 result, 1-9 grid | main 10-36, hotbar 37-45 | 37 | 12 |
| Furnace | input 0, fuel 1, output 2 | from slot 3 (39 slots) | 30 | 14 |

Name slots with `inventory/slot-in` (`[:hotbar n] [:main n] [:armour n] [:offhand] [:result] [:grid n] [:input] [:fuel] [:output]`). An unknown menu type has no layout, so every slot of it is stored as the container's own.

#### State ids and clicks

- The server stamps every window update with a state id, and a click echoes the last one seen. A current-id click is answered with one `container-set-slot` per changed slot, each under a new state id; a stale-id click with a whole `container-set-content` and the real cursor. The cursor is sent only when it differs from the click's claim. **A click that changes nothing gets no packet at all.** A click for a window that is not open is ignored silently.
- Clicks are named in `inventory`: `left-click` (b0 m0), `right-click` (b1 m0), `shift-click` (m1), `swap-click` (m2, button = hotbar index). The packet test pins a click with no prediction as `12 00 07 00 24 01 00 00 00`.
- `window/click` emits, drops `:window/cursor`, and records `:intent/awaiting :intent/awaiting-window :intent/sent-at`; it is for use inside an intent only. `window/waiting` returns nil, `:waiting` while that window's state id has not moved, or `:overdue` after `answer-timeout` (3000 ms).
- On overdue, `window/lost` arranges a resync and restarts a stage that sends no blind next click; the `max-lost`-th loss (3) fails `:stale-window`. The resync (`window/resync`) is a `resync-click` with `stale-state-id` -1 that puts the cursor down in a free slot, answered with the full window and the real cursor. A loaded server answers late rather than never (#131, #132).
- **Phantom cursor:** the server adopts the click's `cursor` field as its record, so a click that really empties the cursor gets no cursor packet. Hence `window/click` forgets the cursor as it sends; otherwise the next "put down" is a no-op click that times out `:stale-window`.

#### Crafting

- `recipe/recipes` holds 1030 shaped and shapeless crafting recipes, with ingredients as sets of item names (tags resolved at datagen). `recipe/match` (used only by the sim, for slot 0) matches shaped at any offset and mirrored, shapeless by set assignment, first in table order. `datagen/shrink` trims pattern padding as vanilla does.
- `(recipe/clicks inventory r size slot-of)` lays one craft: left-click the source, right-click each cell, left-click the source to put back the rest. Sticks from planks in hotbar 0 are `36 b0, 1 b1, 3 b1, 36 b0`. It returns nil when the inventory cannot cover the recipe.
- `:craft` stages:
  - **`:settle`:** `:unknown-recipe`; for `:inventory`, close an open container first; wait 5 s for a state id (`:no-window`); put a loaded cursor down (`:inventory-full`); shift-click the grid empty; plan the clicks (`:no-ingredient`).
  - **`:click`:** one planned click per round trip.
  - **`:verify`:** take slot 0 only when it is exactly the expected result; anything else fails `:wrong-result`, and nothing for 5 s `:no-result`.
  - **`:take`:** wait for the held count to reach before + makes (5 s, `:not-taken`); a table craft then emits `container-close`.
- **2x2 against the table.** `make/craft-row` adds `{:block/crafting_table :near}` to any recipe that does not fit 2x2. `resolve-block-need` meets it with a remembered placed table within 24 blocks, else the `:place-table` row. `make/at-container` uses the open table window, else opens the table in reach (`:open-container`, `open-reach` 4.5), else walks to it. The window must show menu type 12, or the craft fails `:no-table-window`. Closing the table returns its grid items. `make/take-table` digs up the table before any row that goes out (gather, mine, scoop).
- **Junk crafts:** two planks side by side make a pressure plate, and one plank alone a button. That is why `:settle` empties the grid and `:verify` checks slot 0.

#### Smelting

- `make/smelt-row` needs input, then `{:fuel/ticks t}`, then `{:block/furnace :near}`, so a smelt is chosen only once the fuel is held. `smelt/batch` sizes it; a batch of 0 waits `:no-fuel`.
- `smelt/burn-order` is coal and charcoal, then planks. Never a log (300 ticks against 1200 for its four planks); never a stick (100 ticks, and a pickaxe handle, issue #36); charcoal is never made. `make/burn` nets fuel in the same order. `fuel-clicks` loads only `ceil(ticks / burn)` items from one stack: four raw iron take one coal, three take two planks.
- `:smelt` stages:
  - **`:settle`** empties the furnace slots; **`:load`** lays fuel, then input.
  - **`:cook`** waits until `loaded?` (5 s, `:not-loaded`) and sets `cook-by` = now + input x cook ticks x 50 ms + 5 s. When the fire is out with input left, it lays the next fuel stack (`:out-of-fuel` when none). Past `cook-by` it fails `:not-cooked`.
  - **`:take`** has 5 s (`:not-taken`); **`:close`** puts the cursor down, empties the furnace and closes.
- A lost smelt click is recovered inside the intent by a resync, because what is left on the cursor or in the furnace is invisible to the planner. A furnace's own ticks move the state id with nobody clicking, so judge a click by the slot clicked.
- Timing (the sim, from the 26.1.2 code): 200 ticks per item. Four raw iron on one coal give the fourth ingot at tick 800, with 801 ticks of burn left. A plank burns 300 ticks. With no fuel and no fire, progress unwinds 2 a tick. A closed furnace keeps cooking. A smelt abandoned mid-cook leaves its input inside, unknown to the planner.

### 20.6 Vanilla 26.1.2 mechanics that matter

#### Dig time (`dig/ms`, the game's rule)

- `damage/tick = speed / hardness / (30 if harvest? else 100)`, and `ticks = ceil(1 / damage)` (0 when damage >= 1), giving ms = ticks x 50. `speed` is the tier's speed when the tool's kind is `blocks/tool-of` the block, else 1.0; each penalty (`:eyes-in-water?`, `:off-ground?`) divides it by 5. Hardness -1.0 is unbreakable (nil); an unknown block falls back to 3000 ms (`intent/dig-time`, `sim/dig-ms`). A log by hand (hardness 2.0) is 60 ticks, 3000 ms; leaves (0.2) are 6 ticks, 300 ms.
- **The note `mc-dig-and-pickup` says tool speeds, tiers and penalties are "not modelled yet (issue #9)"; `dig.clj` models all three (source).**
- `dig/harvest?` is `Player.hasCorrectToolForDrops`. A block outside `needs-tool-names` drops for anything; otherwise only a tool of its kind whose `tier-rank` reaches `blocks/needs-tier`. Ranks: wooden and golden 0, stone and copper 1, iron 2, diamond and netherite 3. Stone takes any pickaxe, iron ore a stone one; without harvest the block breaks and drops nothing.
- `dig/best-tool` picks the fastest harvesting tool of the block's kind. `dig/drop-item` is the sure drop (cobblestone from stone, raw_iron from iron ore); gravel's flint is a 0.1 roll before its sure gravel.
- **FINISH timing:** the server takes FINISH once 70% of its own break time has passed since START (`sim/break-threshold`); earlier is an abort. The bot sends it at `intent/finish-delay` = `ms x 1.15 + 100` (`finish-margin`, `finish-slack`): 3550 ms for a log by hand, 1442 for stone with a wooden pickaxe. That holds while the server keeps above about 12 ticks a second.
- **Delayed destroy:** a FINISH for the block being broken is acked at once, and the break comes by the server's clock, seconds later on a loaded server.
- Before START the dig settles: three still ticks plus `settle-ms` (150), or `settle-deadline` (2000). Stillness is horizontal only.
- The dig refuses obsidian (`intent/never-dug`). A block with water or lava among its six neighbours, or under gravel or sand with fluid on top (`fluid-over-falling?`, looked for up to `falling-reach` 16), fails `:unsafe` unless `:intent/exposes-ok?`. A tier no held tool reaches fails `:needs-tool`. Only hotbar tools are chosen; a tool in the main inventory needs an `:equip` intent first.

#### Reach

Dig fails `:out-of-reach` beyond `intent/reach` + 0.5 = 4.5 from the eye to the block centre. Place is `place/place-reach` 4.0, and opening a container `place/open-reach` 4.5. The sim's bucket ray reaches `sim/use-range` 4.5 from the eye at y+1.62.

#### Placing rules (`use-item-on`, c2s 66)

- Fields: `hand, pos, face, cursor-x/y/z, inside-block, world-border-hit, sequence`. Faces: 0 down, 1 up, 2 north, 3 south, 4 west, 5 east; the block goes into `pos + face`. The bot always clicks the support's top face (face 1) with cursor `[0.5 1.0 0.5]` and swings, after 3 still ticks and 300 ms.
- The server places only into a cell its replaceable tag lists: air, grasses, ferns, dead bush, vines and so on, plus water or lava. A flower or flower bed is refused (race 38477665442, Clj_r23 on wildflowers).
- **No block goes into any player's box**, the bot's own included (issue #106). A right-click on a crafting table or furnace opens it instead of placing, so `spot` never uses one as support.
- Answers: accepted is `block-update` with the new state, a slot update, then the ack; rejected is a `block-update` restating the destination, then the ack; on a container, `open-screen` then `container-set-content`. A furnace's placed state depends on its facing, so placement is judged by block name.

#### Buckets (`use-item`, c2s 67)

In 775 a bucket uses `use-item`, which carries yaw and pitch. That `use-item-on` with a bucket does nothing is the siblings' finding; flint and steel does use `use-item-on`. An empty bucket ray-casts `SOURCE_ONLY` and fills from the first source. A filled one ray-casts `NONE` through fluids to the first solid block and pours into the cell before it. A scoop is answered with `block-update` (source to air), the ack, and a whole `container-set-content` for window 0.

#### Water and lava (`fluid.clj`, from `FlowingFluid`, `WaterFluid`, `LavaFluid`, `LiquidBlock`)

- A fluid state is `{:fluid/kind :fluid/amount 1-8 :fluid/source? :fluid/falling?}`; the legacy level is 0 for a source, `8 - amount` for a flow, plus 8 when falling.
- `fluid/kinds`: water drops 1, slope 4, ticks every 250 ms (5 ticks), and converts to source; lava drops 2, slope 2, every 1500 ms (30 ticks); lava in the Nether drops 1, slope 4, every 500 ms.
- `new-liquid`: two or more water sources beside a cell, on a solid block or a source, make a new source; else it falls at 8 under its kind; else it takes the most of its kind beside it minus the drop. So a flow recedes block by block once its source is scooped.
- `spread` goes down first, and on the level only when it cannot go down (or three sources stand beside it), toward the sides nearest a way down (`slope-distance`).
- `mix` acts at once: lava touched by water **above or on one of the four sides (never below)** becomes obsidian from a source and cobblestone from a flow. Lava flowing down into water makes that water stone. The Nether evaporates water and has fast lava. Not modelled: waterlogged blocks as flows, lava's random slow-down, fire.

#### Gravity blocks and pickup

- `blocks/falling-types` are the jar's `FallingBlock` subclasses (sand, red sand, gravel, concrete powder, anvils, dragon egg) plus `BrushableBlock`. A falling block looks under itself 2 ticks (`fall-delay-ms` 100) after the cell below changes, and falls if free, gaining `fall-gravity` 0.04 a tick with `fall-drag` 0.98: one block takes seven ticks. Gravel refills a niche dug under it (Clj_r14 dug the same niche seven times).
- Pickup needs no client packet. The server collects an item when the player's box, inflated by (1, 0.5, 1), touches it after a 10-tick delay. The client then gets `take-item-entity`, `remove-entities` and an inventory packet, seen live 0.5-1 s after FINISH. `player-loaded` must have been sent.

#### Server ticks and settings

- **Ticks:** 20 a second (`tick-ms` 50). A vanilla server ticks on one thread at about 2 ms per bot, so one server stops near 25 bots. Drowning: at zero air the server deals 2 damage every 20 ticks; a full breath is 300 ticks.
- **Settings:** `pause-when-empty-seconds` defaults to 60 and ours write 0; any new server script must too. `spawn-protection` defaults to 16, which reverts a non-op's breaks after the ack; ours write 0. Gamerules are snake_case (`keep_inventory`), and camelCase returns an error string, not an exception. **The note says clojurecraft sets none; `race.clj` sends `gamerule keep_inventory true` (source).**

| Dimension | min_y | Height | Sections | Notes |
|---|---|---|---|---|
| Overworld | -64 | 384 | 24 | |
| Nether | 0 | 256 | 16 | logical height 128, ceiling, scale 8.0 |
| End | 0 | 256 | 16 | |

**The `mc-dimensions` note says `chunk/decode` would read 24 sections in the Nether; decode now takes the count from the column (source).**

### 20.7 The server model (`sim.clj`)

`(sim/step sim event)` takes a client packet (`{:sim/kind :packet}`) or a tick (`{:sim/kind :tick :sim/now ms}`) and collects packets for the client in `:sim/out`. It is driven three ways: `sim/run` and `sim/run+` fold bot and model on one thread at 50 ms a tick; `sim/play-on` resumes from a snapshot and returns `:before`, the pair one tick before the stop; `sim/connect!` puts the model behind a channel pair shaped like `conn/open!`'s, so `main`'s own loop runs against it.

#### What it models faithfully

- The handshake, login teleport and chunk streaming (the whole captured world at login unless `:view-distance`), and keep-alives every 15 s.
- The server's block view, `sim/block-at`: placed blocks, then broken positions (air), then the columns.
- Breaks: `finish-dig` breaks at 70% of `full-ms` with the update before the ack; an early FINISH is a delayed destroy (`tick-delayed`). Loot comes from the game's tables with seeded rolls (`:sim/rng`). Tools wear and break (`wear`), and a tool stacks to 1 (issue #36).
- Pickup after 500 ms inside the inflated box.
- Window 0, a crafting table (menu type 12) and a furnace (14), with vanilla click rules (`click-view`), state ids and prediction-based answers (`sync-window`); slot 0 by `recipe/match`. Furnaces cook every tick, open or not.
- `use-item-on` placement under `blocks/placeable-into?`, a solid clicked block and no player overlap; a rejection is the old state plus the ack.
- Buckets (`scooped`, `poured`, `quench`), and fluids flowing by `fluid.clj` (`wake-fluids`, `tick-fluids` in position order). **The server-model note still says "fluids do not flow" in one sentence; `sim/tick` runs `tick-fluids` (source, issue #125).**
- Gravel and sand falling (`tick-falls`). Flint and steel lighting a complete frame (`frame-around`, `light`), with the dimension changing after `portal-ms` 4000. Drowning from the reported eye (`drown`), and respawn on `client-command` 0.

#### Inputs and violations

- `sim/init` takes inputs, never hidden randomness: `:columns` with `:spawn`, `:decoded`, `:view-distance`; faults `:drop-clicks #{ordinal}`, `:drop-breaks #{ordinal}`, `:lag-ticks`, `:slow-breaks f`, `:kill-at ms`; `:inventory {[:hotbar 0] item}`, `:respawn`, `:dimension`, `:nether {:column :at}`; luck from `:seed` (loot) and `:tick-seed` (`:event/rand`, 0.5 every tick when absent).
- `:sim/violations` records `[:click-on-empty-result mode]`, `[:close-with-cursor item]`, `[:click-inventory-while-open window-id]` and `[:place-into-player pos]`. The end-to-end tests require `[]`.

#### What it does not model

- **Server lag.** The only lag inputs are a uniform in-order delay (`:lag-ticks`), slow breaks, and dropped clicks or FINISHes; the clock ticks exactly every 50 ms. No reordered or duplicated packets, no lost `set-cursor-item`, no server-initiated resyncs. The late-ack failures of a 25-bot server (#59, #66, #131, #132) were found on real runners.
- **Set-time packets.** `set-time` has no spec, the model never sends it, and nothing in bot or model knows the time of day.
- **The captured world's edge.** `capture.clj` takes radius 12 (25x25 columns) per landing. Past it, `sim/block-at` is nil, the bot sees unloaded cells (solid to physics, `:unknown` to paths), and a run that needs more stops at the edge.
- **Client-side water push.** The bot's physics has no flow currents, and the model takes the reported position as truth (`move-player-pos*` sets `:sim/player-pos`), with no movement check and no corrective teleport. A real server's rubber-band never happens in the sim.
- **Placement details.** `use-item-on` is not reach-checked (only the bucket ray is), and entities other than the player are ignored. The model places a block's lowest state, so facing is lost.
- **Break penalties.** `full-ms` has no water or off-ground penalty, which errs toward accepting.
- **Stacking.** Only tools are unstackable; buckets and flint and steel stack to 64. `insert` fills the main inventory before the hotbar.
- **Furnace details.** The empty bucket from lava-bucket fuel, experience, the lit block state and hoppers are not modelled, and a furnace never opened does not tick. A falling block landing in a cell no longer free (an item drop) and harm to a player under it are not modelled.
- **Comparison and recipes.** Window sync compares whole slot values, not hashes. Model and bot share `recipe/match`, so they agree by construction; only a live run checks it against vanilla.

#### How a sim-only failure can be the model's limit

First ask whether the failure touches a row above: the edge, reach, a stack of buckets, a missing push, or exact timing. If a real race on the same signature does not fail, suspect the model and fix `sim.clj` first, with the jar's code as the reference. Issue #44 (198 runs) was the model refusing a block placed into water, which a real server accepts; issue #36 was tools stacking in the model, so one break emptied both pickaxes. The reverse holds too: a real-only failure under load (delayed destroy #64, acks 200-300 ms late, refilled servers) needs a fault input (`:slow-breaks`, `:drop-breaks`, `:lag-ticks`) before a sim test can hold the fix.

### 20.8 Gotchas already paid for

- **Grass type is grass.** `grass_block`'s type is `grass`, so a non-solid list copied from ruststeve makes the ground passable. Keep `grass` out of `passable-types`; the test asserts id 8 solid and 2248 (`short_grass`) passable.
- **Resting vertical velocity.** vy is -0.0784 standing, so a three-axis stillness check never passes. `intent/still?` checks horizontal velocity and `:player/on-ground?` (or afloat).
- **Packet name collision.** A wire field `name` overwrote the packet's identity (ClassCastException on `hello`). Use `:packet/name`, and never a `:name` field.
- **An early FINISH aborts silently.** Keep `finish-delay` at 1.15x + 100 ms or later; the `props_test` property holds it for every block and tool.
- **Drop under the trunk.** Digging the bottom log from above dropped it into a hole a 1.8-tall player cannot enter. `wood/trunk-target` digs the log nearest feet height.
- **Leaf canopy over the drop.** Leaves at head height block the walk to a drop. `:collect` detects the stall, `intent/blocker` names the leaf, and the gather chain digs it (up to six). Second variant: a ledge under leaves one above the head, so `step?` also checks over the step and over the player.
- **Phantom cursor.** The server adopts the claimed empty cursor, so forget the cursor on every click. Never claim a non-empty cursor without tracking it the same way.
- **Junk crafts.** A dirty grid makes a pressure plate or a button. Empty the grid in `:settle`, and verify slot 0 before taking.
- **Memory edges.** A log broken while unloaded stays a log, and air is sticky: memory is not terrain.
- **Recipe patterns shrink.** Vanilla trims blank padding (the spyglass is one wide); seven of the 1,030 recipes failed until `datagen/shrink`.
- **A worn tool truncated slot packets.** An undecodable component ended `container-set-content`. Decode through `component-codecs`, else mark the packet `:truncated`.
- **Never place into a guess.** A niche placed into before the slow server's break is refused (Clj_r01, Clj_r07); `spot` skips unconfirmed cells and `dig-niche` waits.
- **Never into a flower, never on a table, never twice into a body.** Each cost three `:rejected` in race 38477665442 or 38515209959.
- **Get ashore first.** Afloat at a pond's edge, every cell round the feet is water; `place/ashore` swims out before `:place` and `:redig` (issue #95). `place/droppable?` also takes a stowed pickaxe for a niche, equipped first (issue #94).
- **A stale guess.** Physics fell through a guessed air the server kept as stone (Clj_r19); `forget-stale` drops guesses after 3000 ms.
- **A start on a block edge.** `feet-cell` floored into rock and every plan was `:none`; search from `path/start-cell`.
- **A snowfield.** `snow_layer` was awkward at every depth and 39 bots never moved; one layer is `:clear` now.
- **Nether columns.** Reading them at overworld height put brick 64 blocks off (ruststeve); read the height from the column.
- **`player-loaded`.** Without it nothing ticks for the player, and it is sent again after a dimension change.
- **`set-held-slot` outside 0-8** broke the world spec until it was ignored (found by the totality property).
- **A furnace moves the state id by itself.** Check the slot you clicked.
- **A fold by other code is another run.** Fold a recording at its own commit with `bb replay` (issue #111).
- **Server settings.** Any new `server.properties` needs `pause-when-empty-seconds=0` and `spawn-protection=0`. Changing `--name` changes the offline UUID, and so the player data.

### 20.9 A short glossary of the project's words

- **World:** the one immutable map of namespaced attributes, held in one atom.
- **Event:** a map with `:event/kind`: `:start`, `:packet`, `:tick`, `:go` or `:closed`.
- **Effect:** a map in `:bot/effects` (`:send`, `:log`), performed by the loop, never by a reducer.
- **Packet:** a flat map with `:packet/name` and the wire's field names.
- **Phase:** `:handshake`, `:login`, `:configuration` or `:play`, advanced only by emitting a transition packet.
- **Intent:** the value in `:plan/intent` (`{:intent/kind :dig ...}`), advanced one tick at a time by `intent/run`.
- **Goal:** a row in `resources/clojurecraft/goals.edn` (`:goal/needs :goal/provides :goal/done? :goal/act`), given meaning by `plan/done-by` and `plan/act`.
- **Needs / provides:** a goal row's maps of `:item/<name> n`, `:tag/<tag> n`, `:block/<name> :near` or `:fuel/ticks n`.
- **Needs planner:** `make/next-intent :needs`, which walks provides back through producer rows to one row whose needs are met.
- **Sighting:** a fact `{:sight/pos :sight/state :sight/at}` in `:world/facts`. **Pool:** one water or lava fact per chunk and kind.
- **Overlay:** `:world/blocks`, block updates and guesses over the columns; `:world/unconfirmed` holds the guesses.
- **Trunk bottom:** a remembered log with no log below it. **Gather chain:** walk, dig, collect toward a log (`wood/gather-next`).
- **Window 0:** the player's own screen; slots 0-4 are the 2x2 grid, kept in `:window/grid`.
- **View:** a map describing one window for the click rules (`:view/id :view/state-id :view/size :view/grid :view/slot-of`).
- **Menu type:** the registry index `open-screen` names: 12 is the crafting table, 14 the furnace.
- **State id:** the counter the server stamps on window updates and a click echoes; an unanswered click is one whose state id never moves.
- **Prediction:** the `changed` slots and `cursor` a 775 click claims; we claim nothing. **Resync:** a click with state id -1, answered with the whole window and the real cursor.
- **Stale window:** `:stale-window`, a window intent failing on its third unanswered click.
- **Junk craft:** a pressure plate or button made from leftover planks. **Kit:** the `:kit` goal, a table and four sticks (`--until table`).
- **Until:** `--until <name>`, matched against `:goal/until` by `plan/goals-for`; the ids travel on `:go/goals`. **Chain / reached:** a fresh run's milestones (`:go/chain? true`, `:plan/reached`).
- **Landing:** `:plan/status :landing`, the wait between a fixture's `:go` with `:go/at` and the bot standing there.
- **Sim:** the pure server model in `sim.clj`. **Violation:** an entry in `:sim/violations`.
- **Snapshot:** a sim run's bot world and model state one tick before a failure, played on by `issues_test`.
- **Recording:** an EDN file of every event, its effects and the RESULT, replayable with no server.
- **Gym:** one goal run with its prerequisites given over RCON, judged by `RESULT` plus an RCON truth read. **RESULT:** the one stdout line a run prints.
- **Delayed destroy:** the server acks a FINISH and breaks the block later by its own clock.
- **775 / 26.1.2:** the protocol number, and the server version every `game` pin refers to.

---

## 21. The run, stage by stage

The goal table, `resources/clojurecraft/goals.edn`, read row by row. A run with `--until <stage>` (the races use `nether`) plays every target up to that one in priority order (`plan/through`); a target already reached stays reached (`:plan/reached`). An issue's signature names the target the run was on: `[:ingots :boxed]` means the bot had every target before `:ingots` and stopped on the way to the ingots. Producers are how a target's needs are met; recipes add one generated producer row each (`make/craft-rows`).

### 21.1 Targets, in order

| Priority | Target | `--until` | Provides / done | Plan or act | Holds / spares |
|---|---|---|---|---|---|
| 0 | `:escape-water` | always | `:not-trapped-in` | plan :escape, act :swim-out |  |
| 0 | `:escape-lava` | always | `:not-trapped-in` | plan :escape, act :flee |  |
| 1 | `:wood` | `wood` | `{:tag/logs 1}` | needs planner (make) |  |
| 2 | `:kit` | `table` | `{:item/crafting_table 1 :item/stick 4}` | needs planner (make) |  |
| 3 | `:pickaxe` | `pickaxe` | `{:item/wooden_pickaxe 1}` | needs planner (make) |  |
| 4 | `:cobblestone` | `cobblestone` | `{:item/cobblestone 3}` | needs planner (make) |  |
| 5 | `:shore` | `shore` | `:escaped-water?` | plan :wade |  |
| 6 | `:stone-pickaxe` | `stone-pickaxe` | `{:item/stone_pickaxe 1}` | needs planner (make) |  |
| 7 | `:furnace` | `furnace` | `{:block/furnace :near}` | needs planner (make) |  |
| 8 | `:ingots` | `ingots` | `{:item/iron_ingot 4}` | needs planner (make) |  |
| 9 | `:bucket` | `bucket` | `{:item/bucket 1}` | needs planner (make) |  |
| 10 | `:water-bucket` | `water-bucket` | `{:item/water_bucket 1}` | needs planner (make) |  |
| 11 | `:flint-and-steel` | `flint-and-steel` | `{:item/flint_and_steel 1}` | needs planner (make) |  |
| 12 | `:iron-kit` | `iron-kit` | `{:item/flint_and_steel 1 :item/water_bucket 1}` | needs planner (make) |  |
| 13 | `:portal-kit` | `portal-kit` | `{:item/water_bucket 1 :item/bucket 1}` | needs planner (make) |  |
| 14 | `:cast-one` | `cast-one` | `:built?` | plan :build, build :cast-one | `holds {:tag/pickaxes 1}` |
| 15 | `:portal` | `portal` | `:built?` | plan :build, build :portal | `holds {:tag/pickaxes 1}` |
| 16 | `:nether` | `nether` | `:built?` | plan :build, build :nether | `holds {:tag/pickaxes 1}` |
| 17 | `:fortress` | `fortress` | `:at-fortress?` | plan :fortress |  |

What each target means, in the table's own words:

- **`:escape-water`**: out of water onto dry land, before anything else
- **`:escape-lava`**: off lava onto footing, before anything else
- **`:wood`**: hold one log
- **`:kit`**: a crafting table and four sticks, from logs
- **`:pickaxe`**: a wooden pickaxe, placing a crafting table to make it
- **`:cobblestone`**: three cobblestone, dug with the wooden pickaxe
- **`:shore`**: wade into the nearest water on purpose until trapped, and be out of it again (the gym's water trial)
- **`:stone-pickaxe`**: a stone pickaxe, from cobblestone and sticks in a placed crafting table
- **`:furnace`**: a furnace placed near, crafted from eight cobblestone in a placed crafting table
- **`:ingots`**: four iron ingots, the iron kit's: raw iron mined with a stone pickaxe and smelted with planned fuel
- **`:bucket`**: an empty bucket, from three iron ingots
- **`:water-bucket`**: a bucket filled at surface water, never cave water
- **`:flint-and-steel`**: flint and steel, from an iron ingot and flint dug out of gravel
- **`:iron-kit`**: the iron kit the portal needs: flint and steel and a filled water bucket (four ingots in all: one in the flint and steel, three in the bucket), the flint and steel first so all the iron is mined in one trip down and the climb out ends at the water
- **`:portal-kit`**: what a portal cast spends that the iron kit does not hold: an empty bucket to carry lava from the pool to each hole, beside the filled water bucket poured over it and scooped back (issue #124). The few blocks a cast places where the rock is missing are asked for by the build itself, counted from the plan for the place chosen (build/held-first, blocks-needed), never a constant. The water bucket is counted here too: a chained run reaches a step once and never asks for it again, and race 38477665442 reached this one holding its only bucket still empty, then filled it, and the cast found no bucket for the lava (issue #49)
- **`:cast-one`**: one obsidian cast in the first cell of the frame, cut into the rock beside a lava pool (issue #124): the hole dug, lava poured into it, water poured into the cell straight over it and scooped back (the gym's cast-one stage); a pickaxe is held for the way to the lava and the digs, made before the build starts (sim race 38477665389 dug for its stance with none and failed :needs-tool, issue #70)
- **`:portal`**: a nether portal cast from lava and water into holes cut in the rock beside a lava pool, a block placed only where the rock is missing, never mined, and lit with flint and steel (issue #124); a pickaxe is held, as for cast-one
- **`:nether`**: the portal cast and lit, then stood in until the dimension is the Nether; a pickaxe is held, as for cast-one
- **`:fortress`**: within 6 blocks of a nether fortress's bricks, reached by a tunnel that never opens a cell beside lava (issue #11)

### 21.2 Producers

| Producer | Act | Needs | Provides | Blocks, depth | Holds / spares |
|---|---|---|---|---|---|
| `:gather-log` | `:gather` | `{}` | `{:tag/logs 1}` |  |  |
| `:mine-stone` | `:mine` | `{}` | `{:item/cobblestone 1}` | `[:stone]` | `holds {:item/wooden_pickaxe 1}` |
| `:mine-coal` | `:mine` | `{}` | `{:item/coal 1}` | `[:coal_ore :deepslate_coal_ore], depth 40` | `holds {:tag/pickaxes 1}; spares {:tag/pickaxes 1}` |
| `:mine-iron` | `:mine` | `{}` | `{:item/raw_iron 1}` | `[:iron_ore :deepslate_iron_ore], depth 16` | `holds {:item/stone_pickaxe 1}; spares {:item/stone_pickaxe 1 :item/stick 4}` |
| `:dig-gravel` | `:mine` | `{}` | `{:item/gravel 1}` | `[:gravel], depth 40` | `holds {:tag/pickaxes 1}; spares {:tag/pickaxes 1}` |
| `:flint-from-gravel` | `:redig` | `{:item/gravel 1}` | `{:item/flint 1}` | `[:gravel]` |  |
| `:fill-bucket` | `:scoop` | `{:item/bucket 1}` | `{:item/water_bucket 1}` |  | `holds {:tag/pickaxes 1}; spares {:tag/pickaxes 1}` |
| `:place-table` | `:place` | `{:item/crafting_table 1}` | `{:block/crafting_table :near}` |  |  |
| `:place-furnace` | `:place` | `{:item/furnace 1}` | `{:block/furnace :near}` |  |  |

Each producer's reasons, in the table's own words:

- **`:gather-log`**: chop the nearest remembered trunk and pick the log up
- **`:mine-stone`**: dig the nearest exposed stone with a pickaxe, or cut stairs down to stone
- **`:mine-coal`**: dig the nearest exposed coal ore with any pickaxe, or cut stairs down toward it, no deeper than y 40 (the game makes coal densest high up, around y 96, and none below y 0). A spare pickaxe is made while one is still held, from what is carried (sticks, cobblestone, the table): the coal for the ingots is dug at depth after the iron, and race 38477665442 bot Clj_r20 tunnelled for it with its last pickaxe, a wooden one, beside two sticks, a table and 249 cobblestone, until it broke and the run stopped :no-pickaxe (issue #36)
- **`:mine-iron`**: dig the nearest exposed iron ore with a stone pickaxe, or cut stairs down toward it and tunnel at y 16, where the game makes iron densest. A spare pickaxe is made before more ore is dug, gathering the wood when a walk to a trunk or stairs up to one can be started, so the last one is not worn out at the bottom. A walk back up the dug shaft is not that: it reaches the sky, not a tree. Four sticks are kept too, two more pickaxes' handles, gathered up at the trees before the descent: the stairs down, the tunnels and the climb out for the water dig some 390 blocks, three stone pickaxes' worth, and race 38477665442 bots Clj_r23 and Clj_r06 left the forest with three logs, made three pickaxes and broke the last on the climb (issue #39)
- **`:dig-gravel`**: dig the nearest exposed gravel, by hand or with a shovel; a pickaxe is held for the stairs down when none is in sight, and a spare kept as the coal row keeps one: the gravel is tunnelled for at y 40 after the water, and race 38477665442 bot Clj_r19 wore both its pickaxes out in those tunnels with two sticks and 245 cobblestone in hand, its table left at the furnace, and the run stopped :no-pickaxe (issue #67)
- **`:flint-from-gravel`**: place a held gravel and dig it, again until it drops flint (the game's loot table: one time in ten)
- **`:fill-bucket`**: scoop a surface water source with an empty bucket, walking to the nearest one in sight or remembered; a pickaxe is held for the stairs up out of a mine, and a spare made when what is held makes one, since one wears out on the way (a stone pickaxe lasts 131 blocks, a climb of fifty stairs digs about 150) and there may be no table or wood up there to make another
- **`:place-table`**: put a held crafting table down near the player, in a passage if nowhere else: it is taken along when the bot goes on, so the way is free again
- **`:place-furnace`**: put a held furnace down near the player

The rules for changing this table: a row is never edited into a different meaning or deleted. To fix one, add the fixed row under a new id, move `:goal/until` to it, and mark the old row `:goal/superseded-by <new id>`, so a recording whose `:go` names the old row still replays.

---

## 22. Every failure reason, what it means, where it is raised

A signature is `[target reason]`. The reason is the keyword the bot stopped with; this table says what it usually means, the functions that raise it (search them first: `grep -n ':<reason>' src/clojurecraft/*.clj`), and every issue so far that carried it (section 23 has their fixes). The meaning is the common case, not the only one: read the code that raised it in your issue.

### `:no-path`

A walk found no route: the pathfinder failed `intent/max-replans` times in a row (`intent/run :walk`), or the plan saw too many unwalkable targets (`plan/max-unwalkable`). Also a stand or trunk no route reaches (`build/reachable-stand`, `wood/near-walk`, `wood/climb-out`). Usually terrain the moves do not cover (a step up beside water, a one-layer snow cell, a ledge), or a target chosen without asking for a route first.

Raised in: `intent/run :walk`, `intent/routable?`, `plan/fail-intent`, `wood/near-walk`, `wood/climb-out`, `build/reachable-stand`, `stairs/seal`.

Issues: #38 (wood, closed, fixed by #101); #40 (ingots, closed, fixed by #101); #45 (cast-one, closed); #47 (wood, closed); #51 (cast-one, closed); #53 (ingots, closed); #54 (water-bucket, closed, fixed by #113); #56 (stone-pickaxe, closed, fixed by #101); #58 (furnace, closed, fixed by #101); #63 (pickaxe, closed); #75 (portal-kit, closed, fixed by #78); #162 (portal, open).

### `:no-log`

The wood hunt has no log to walk to or climb for: none remembered within reach and no hunt heading left (`wood/hunt`, `wood/gather-next`; the plan waits `:no-log` until `wait-timeout`). Treeless landings, trunks only behind water, or a hunt that only sets out toward remembered trees (#57).

Raised in: `wood/hunt`, `wood/gather-next`, `wood/climb-out`, `stairs/pit-rim`.

Issues: #57 (wood, open, fixed by #101); #87 (ingots, open, fixed by #101); #89 (portal-kit, open, fixed by #101); #90 (flint-and-steel, closed); #102 (stone-pickaxe, closed, fixed by #114); #103 (furnace, closed); #104 (kit, closed, fixed by #114); #108 (water-bucket, open); #110 (pickaxe, closed).

### `:boxed`

A staircase or tunnel has no safe next cell: every stair and level step borders water or lava, is refused, or was already stood on (`stairs/boxed?`, `stairs/box`); the mine's climb or descent the same (`stone/climb`, `stone/deeper`). Usually under a lake, an aquifer's sheet or the sea; see `stairs/duck` and `:plan/boxed-at` (#37, #61).

Raised in: `stairs/boxed?`, `stairs/box`, `stairs/duck`, `stone/climb`, `stone/deeper`, `stone/mine-next`, `scoop/boxed-here?`.

Issues: #37 (ingots, closed, fixed by #149); #55 (ingots, closed); #61 (water-bucket, closed, fixed by #166); #65 (cobblestone, closed, fixed by #101); #71 (portal-kit, open, fixed by #113); #105 (flint-and-steel, closed, fixed by #114); #130 (cast-one, closed); #150 (wood, open).

### `:no-pickaxe`

A dig needs a pickaxe and none is held, and none can be made from what is carried (`stairs/tool-missing`, `stone/tool-missing`). A spare not made in time (`:goal/spares`, `make/spare-worth?`) or tools worn out deep down (#36, #39, #67).

Raised in: `stairs/tool-missing`, `stone/tool-missing`, `intent/opening`, `make/spare-worth?`.

Issues: #36 (ingots, closed); #39 (water-bucket, closed, fixed by #101); #43 (portal-kit, closed, fixed by #101); #46 (ingots, closed); #52 (water-bucket, closed); #67 (flint-and-steel, closed, fixed by #101); #109 (bucket, closed).

### `:rejected`

The server answered a dig or a place with the block unchanged, `max-rejections`/`max-refusals` times on the same cell (`intent/dig-stage :confirm`, `place/place-stage :sent`, `stairs/rejected`). A dig finished early by the bot's clock on a lagging server (#88), a block placed into the bot's own body (#44), or a cell the server protects.

Raised in: `intent/dig-stage :confirm`, `place/place-stage :sent`, `place/give-up`, `stairs/rejected`.

Issues: #44 (cast-one, open, fixed by #113); #64 (stone-pickaxe, closed, fixed by #168); #84 (pickaxe, closed, fixed by #101); #88 (ingots, closed, fixed by #168); #106 (furnace, closed, fixed by #114); #144 (cobblestone, closed).

### `:timeout`

The run used its whole time (`main/result`). In the real race a bot the job's end stops is cut, never filed; a filed `:timeout` comes from the sim race's 90 simulated minutes: something kept the bot busy without failing (a back-and-forth retreat, a slow route). Judged by replaying the run from its landing (15.6).

Raised in: `main/result`, `simrace/judged`.

Issues: #62 (ingots, closed, fixed by #153); #69 (water-bucket, open, fixed by #101); #85 (cobblestone, closed, fixed by #101); #135 (cast-one, open); #161 (portal-kit, open).

### `:no-spot`

A place found no cell to put the block in (`place/place-stage :spot`): none free, supported and reachable, none ashore, no niche to dig (`place/niche`, `place/dig-niche`, `place/ashore`).

Raised in: `place/place-stage :spot`, `place/niche`, `place/dig-niche`, `place/ashore`, `stone/afloat-no-spot?`.

Issues: #68 (ingots, closed); #94 (portal-kit, open, fixed by #114); #95 (flint-and-steel, closed); #148 (water-bucket, open).

### `:stair-not-taken`

A staircase cut its stair but the feet did not end up on it (`stairs/stairs-stage :step`/`:level`): a slab, a gap, a fall.

Raised in: `stairs/stairs-stage :step`, `stairs/stairs-stage :level`, `wood/climb-out`.

Issues: #59 (cobblestone, closed, fixed by #101); #66 (ingots, closed, fixed by #101); #107 (furnace, closed, fixed by #114).

### `:no-shovel`

A block in a staircase's way needs a tool the bot cannot make there (`stairs/tool-missing`).

Raised in: `stairs/tool-missing`.

Issues: #42 (cobblestone, closed, fixed by #101); #48 (cobblestone, closed); #72 (ingots, closed, fixed by #101).

### `:stuck`

A walk made no progress for its budget (`intent/run :walk`), the needs planner could not resolve a need into a producer (`make/decide`, `make/resolve-need`, depth `make/max-depth`), or a swim-out made no headway.

Raised in: `intent/run :walk`, `make/decide`, `make/resolve-need`, `swim/intent/run :swim-out`.

Issues: #156 (portal-kit, open); #157 (ingots, open).

### `:stale-window`

A window intent (craft, smelt, place, equip, scoop, pour) lost its third click in one intent: the server did not answer within the window, and two resyncs did not recover (`window/lost`, `window/max-lost`). Server load (#131, #132).

Raised in: `window/lost`, `window/resync`.

Issues: #131 (portal-kit, closed, fixed by #139); #132 (ingots, closed).

### `:no-site`

No site for the portal: no remembered lava with a site beside it whose pool can finish the frame, and the lava hunt has nowhere left to look (`build/lava-hunt`, `plan/next-intent :build`).

Raised in: `build/lava-hunt`, `build/site-ok?`, `plan/next-intent :build`.

Issues: #41 (cast-one, closed); #50 (cast-one, closed).

### `:no-escape`

Trapped in water and `:swim-out` found no bank to climb and no floor to dig from.

Raised in: `swim/intent/run :swim-out`.

Issues: #73 (flint-and-steel, closed, fixed by #113); #92 (portal-kit, open, fixed by #114).

### `:needs-tool`

A dig needs a harvest tool the bot does not hold (`intent/choose-tool`, `intent/dig-stage :settle`); for the build, a pickaxe not held before the build starts (#70).

Raised in: `intent/choose-tool`, `intent/dig-stage :settle`, `build/held-first`, `stairs/too-hard?`.

Issues: #70 (cast-one, closed); #163 (portal, open).

### `:unsafe`

A dig refused: it would open water or lava onto the bot or leave it over a drop (`intent/dig-stage :settle`, `stairs/refusals`, `stairs/up-sealed?`).

Raised in: `intent/dig-stage :settle`, `stairs/refusals`, `stairs/seal`.

Issues: #171 (flint-and-steel, open).

### `:target-gone`

The block to dig or the water to scoop was not there any more when the action came (`intent/dig-stage :settle`, `scoop/scoop-stage :aim`): it flowed away, fell, or another action took it.

Raised in: `intent/dig-stage :settle`, `scoop/scoop-stage :aim`, `stairs/fell?`.

Issues: #91 (cast-one, open, fixed by #101).

### `:not-picked-up`

A collect timed out without the item reaching the inventory (`intent/run :collect`): the drop fell out of reach, into water, or behind a block.

Raised in: `intent/run :collect`, `plan/fail-intent`.

Issues: #151 (flint-and-steel, open).

### `:no-water`

The water hunt has no surface water in sight or remembered and no way to cut to any (`scoop/scoop-next`, `scoop/to-bank`, `scoop/climb`). Dry landings, deep under a landing, water with no route (#60).

Raised in: `scoop/scoop-next`, `scoop/to-bank`, `scoop/climb`, `scoop/dig-out`, `scoop/tunnel-out`.

Issues: #60 (water-bucket, closed, fixed by #147).

### `:no-station`

A lava pour of the cast has no stand that reaches a lava source (`build/refill`, `build/work-op`); a site whose pool is spent (#142).

Raised in: `build/refill`, `build/spent?`, `build/work-op`.

Issues: #142 (portal, closed, fixed by #160).

### `:no-stand`

No reachable stand for a build op (`build/approach-stand`).

Raised in: `build/approach-stand`, `build/reachable-stand`.

Issues: #74 (cast-one, open, fixed by #101).

### `:no-scoop`

The bucket did not fill after `scoop/max-tries` uses (`scoop/scoop-stage :sent`): aimed at a flowing cell, out of reach.

Raised in: `scoop/scoop-stage :sent`.

Issues: #83 (water-bucket, closed).

### `:no-landing`

The race's landing never delivered a `:go` to the bot within its time (`plan/landing-tick`): an infrastructure-shaped failure of the fixture or the server's load.

Raised in: `plan/landing-tick`.

Issues: #167 (wood, open).

### `:no-ingredient`

A craft or smelt found an ingredient missing from the window when it settled (`craft/stage :settle`, `smelt/stage :settle`).

Raised in: `craft/stage :settle`, `smelt/stage :settle`, `recipe/sources`.

Issues: #86 (furnace, closed, fixed by #101).

### `:no-bucket`

A lava pour found no empty bucket to carry the lava (`build/refill`) (#49).

Raised in: `build/refill`.

Issues: #49 (cast-one, closed, fixed by #101).

### `:inventory-full`

A craft, smelt or resync found no free slot for the result (`craft/stage :settle`, `smelt/put-down`, `window/resync`).

Raised in: `craft/stage :settle`, `smelt/put-down`, `window/resync`.

Issues: #172 (portal-kit, open).

### `:crashed`

The bot's run threw an exception (caught in `race/play-one!`, `simrace/play!`). The RESULT carries `:error`; read it first.

Raised in: `race/play-one!`, `simrace/play!`.

Issues: #152 (wood, open).

### `:ledge`

An older reason, no longer raised by the code: see its issue's closing PR.

Issues: #93 (flint-and-steel, closed, fixed by #101).

---

## 23. Every bot-failure issue so far

All issues labelled `bot-failure`, open and closed, oldest first, as of 2026-10-14 (refresh with the command at the end). The signature is in the title; the closing PR is the fix to read first when an issue comes back "regressed", and the fastest way to learn how a stage's failures have been solved before. Closed with no PR: closed by hand (superseded, or proven fixed by a later round).

| Issue | State | Signature | From | Closed by |
|---|---|---|---|---|
| #36 | closed | ingots: no-pickaxe | real,sim |  |
| #37 | closed | ingots: boxed | real,sim | #149 Duck a boxed climb under the water over it |
| #38 | closed | wood: no-path | real,sim | #101 Fix every old-style bot-failure issue from the first race round |
| #39 | closed | water-bucket: no-pickaxe | real,sim | #101 Fix every old-style bot-failure issue from the first race round |
| #40 | closed | ingots: no-path | real,sim | #101 Fix every old-style bot-failure issue from the first race round |
| #41 | closed | cast-one: no-site | real,sim |  |
| #42 | closed | cobblestone: no-shovel | real,sim | #101 Fix every old-style bot-failure issue from the first race round |
| #43 | closed | portal-kit: no-pickaxe | real,sim | #101 Fix every old-style bot-failure issue from the first race round |
| #44 | open | cast-one: rejected | real,sim | #113 Fix the six bot-failure issues left open after the first round |
| #45 | closed | cast-one: no-path | real,sim |  |
| #46 | closed | ingots: no-pickaxe | ? |  |
| #47 | closed | wood: no-path | ? |  |
| #48 | closed | cobblestone: no-shovel | ? |  |
| #49 | closed | cast-one: no-bucket | real,sim | #101 Fix every old-style bot-failure issue from the first race round |
| #50 | closed | cast-one: no-site | ? |  |
| #51 | closed | cast-one: no-path | ? |  |
| #52 | closed | water-bucket: no-pickaxe | ? |  |
| #53 | closed | ingots: no-path | ? |  |
| #54 | closed | water-bucket: no-path | real,sim | #113 Fix the six bot-failure issues left open after the first round |
| #55 | closed | ingots: boxed | ? |  |
| #56 | closed | stone-pickaxe: no-path | real | #101 Fix every old-style bot-failure issue from the first race round |
| #57 | open | wood: no-log | real,sim | #101 Fix every old-style bot-failure issue from the first race round |
| #58 | closed | furnace: no-path | real,sim | #101 Fix every old-style bot-failure issue from the first race round |
| #59 | closed | cobblestone: stair-not-taken | real | #101 Fix every old-style bot-failure issue from the first race round |
| #60 | closed | water-bucket: no-water | real,sim | #147 Cut toward water seen that no route reaches |
| #61 | closed | water-bucket: boxed | real,sim | #166 Take the water from under it, not by climbing away |
| #62 | closed | ingots: timeout | real,sim | #153 Never lay a floor over water |
| #63 | closed | pickaxe: no-path | real,sim |  |
| #64 | closed | stone-pickaxe: rejected | real | #168 Stretch a dig by the server's tick length |
| #65 | closed | cobblestone: boxed | real,sim | #101 Fix every old-style bot-failure issue from the first race round |
| #66 | closed | ingots: stair-not-taken | sim | #101 Fix every old-style bot-failure issue from the first race round |
| #67 | closed | flint-and-steel: no-pickaxe | real,sim | #101 Fix every old-style bot-failure issue from the first race round |
| #68 | closed | ingots: no-spot | real,sim |  |
| #69 | open | water-bucket: timeout | sim | #101 Fix every old-style bot-failure issue from the first race round |
| #70 | closed | cast-one: needs-tool | real,sim |  |
| #71 | open | portal-kit: boxed | sim | #113 Fix the six bot-failure issues left open after the first round |
| #72 | closed | ingots: no-shovel | sim | #101 Fix every old-style bot-failure issue from the first race round |
| #73 | closed | flint-and-steel: no-escape | sim | #113 Fix the six bot-failure issues left open after the first round |
| #74 | open | cast-one: no-stand | real,sim | #101 Fix every old-style bot-failure issue from the first race round |
| #75 | closed | portal-kit: no-path | real,sim | #78 Ask a near trunk for a route before walking at it (#75) |
| #83 | closed | water-bucket: no-scoop | real |  |
| #84 | closed | pickaxe: rejected | real | #101 Fix every old-style bot-failure issue from the first race round |
| #85 | closed | cobblestone: timeout | real | #101 Fix every old-style bot-failure issue from the first race round |
| #86 | closed | furnace: no-ingredient | real | #101 Fix every old-style bot-failure issue from the first race round |
| #87 | open | ingots: no-log | real,sim | #101 Fix every old-style bot-failure issue from the first race round |
| #88 | closed | ingots: rejected | real | #168 Stretch a dig by the server's tick length |
| #89 | open | portal-kit: no-log | real,sim | #101 Fix every old-style bot-failure issue from the first race round |
| #90 | closed | flint-and-steel: no-log | real,sim |  |
| #91 | open | cast-one: target-gone | sim | #101 Fix every old-style bot-failure issue from the first race round |
| #92 | open | portal-kit: no-escape | sim | #114 Fix the twelve new-style bot-failure issues from the second round |
| #93 | closed | flint-and-steel: ledge | sim | #101 Fix every old-style bot-failure issue from the first race round |
| #94 | open | portal-kit: no-spot | sim | #114 Fix the twelve new-style bot-failure issues from the second round |
| #95 | closed | flint-and-steel: no-spot | real,sim |  |
| #102 | closed | stone-pickaxe: no-log | real | #114 Fix the twelve new-style bot-failure issues from the second round |
| #103 | closed | furnace: no-log | real,sim |  |
| #104 | closed | kit: no-log | real,sim | #114 Fix the twelve new-style bot-failure issues from the second round |
| #105 | closed | flint-and-steel: boxed | real,sim | #114 Fix the twelve new-style bot-failure issues from the second round |
| #106 | closed | furnace: rejected | real | #114 Fix the twelve new-style bot-failure issues from the second round |
| #107 | closed | furnace: stair-not-taken | real | #114 Fix the twelve new-style bot-failure issues from the second round |
| #108 | open | water-bucket: no-log | sim |  |
| #109 | closed | bucket: no-pickaxe | sim |  |
| #110 | closed | pickaxe: no-log | real,sim |  |
| #124 | closed | Cast the portal as a player does: into the rock beside any lava pool, two buckets, a few blocks | ? | #136 Cast the portal into the rock beside a lava pool, as a player does |
| #130 | closed | cast-one: boxed | sim |  |
| #131 | closed | portal-kit: stale-window | real | #139 Resync a lost click in every window intent |
| #132 | closed | ingots: stale-window | real |  |
| #135 | open | cast-one: timeout | sim |  |
| #142 | closed | portal: no-station | sim | #160 Choose a site whose lava can finish the frame |
| #144 | closed | cobblestone: rejected | real |  |
| #148 | open | water-bucket: no-spot | sim |  |
| #150 | open | wood: boxed | real |  |
| #151 | open | flint-and-steel: not-picked-up | sim |  |
| #152 | open | wood: crashed | sim |  |
| #156 | open | portal-kit: stuck | sim |  |
| #157 | open | ingots: stuck | real |  |
| #161 | open | portal-kit: timeout | sim |  |
| #162 | open | portal: no-path | sim |  |
| #163 | open | portal: needs-tool | sim |  |
| #167 | open | wood: no-landing | real |  |
| #171 | open | flint-and-steel: unsafe | sim |  |
| #172 | open | portal-kit: inventory-full | sim |  |

Refresh:

```sh
gh issue list --label bot-failure --state all --limit 500 --json number,state,title --jq '.[] | [.number, .state, .title] | @tsv' | sort -n
```

---

## 24. The attributes a fix reads and writes

From `src/clojurecraft/model.clj`, the information model: every key the running bot may hold, with its meaning and when it exists. `model_test` fails when the bot writes a key that is not listed, `docs_test`/`spec` when a listed key has no spec. A fix that adds a key adds its row there and its spec in `spec.clj` (10.4). The groups a fixer meets most are below; the file has the rest (the connection, packets, races, samples).

### 24.1 `:player/*`

| Key | Meaning | When |
|---|---|---|
| `:player/pos` | feet-centre position [x y z] | from the first teleport |
| `:player/vel` | velocity per tick [vx vy vz]; vy is about -0.078 at rest | always |
| `:player/look` | [yaw pitch] in degrees | always |
| `:player/on-ground?` | last vertical move hit ground while falling | always |
| `:player/horizontal-collision?` | last horizontal move was blocked | always |
| `:player/jump-ticks` | ticks until a jump is allowed again | always |
| `:player/fall-distance` | blocks fallen since the last landing or liquid; vanilla hurts past 3 | after the first physics tick |
| `:player/in-water?` | the body (contracted) overlaps water, by the physics this tick | after the first physics tick |
| `:player/in-lava?` | the body (contracted) overlaps lava, by the physics this tick | after the first physics tick |
| `:player/eye-in-water?` | the eye (y + 1.62) is inside water, by the physics this tick | after the first physics tick |
| `:player/air` | breath left, 0-300 ticks: an estimate, down 1 under water, up 4 out | after the first physics tick |
| `:player/dead?` | health reached zero; cleared by the server's respawn | while dead |
| `:player/loaded?` | player_loaded was sent: the server lets the bot act | always |
| `:player/dimension` | the dimension the last respawn named (\"minecraft:the_nether\"); the overworld before one | after a respawn |
| `:player/controls` | what the planner wants physics to do next tick (:control/*) | always |
| `:player/inventory` | {player-slot {:item id :count n}}: the server's word, slots 0-40 | always |
| `:player/held-slot` | selected hotbar slot 0-8 | always |
| `:player/entity-id` | the bot's entity id | from the play login packet |
| `:player/teleport-id` | id of the last teleport accepted | from the first teleport |
| `:player/synced-at` | ms of the last teleport | from the first teleport |
| `:player/health` | health from set_health | after the first set_health |

### 24.2 `:plan/*`

| Key | Meaning | When |
|---|---|---|
| `:plan/wet-ticks` | consecutive ticks the eye has been under water | while planning |
| `:plan/dry-ticks` | consecutive ticks on dry land (trap/dry-land?) | while planning |
| `:plan/trap` | the trap the player is in, :water or :lava, with hysteresis (trap/trapped-tick) | while trapped |
| `:plan/trap-at` | the [x z] column where the current trap began | while trapped |
| `:plan/status` | :landing, :active, :done or :failed | from the :go event |
| `:plan/goals` | the target goal ids in play | from the :go event |
| `:plan/chain?` | a run that plays every step up to its target (--chain true): reached steps stay reached | a chained :go |
| `:plan/reached` | the targets a chained run has reached; choose skips them (plan/reach) | a chained plan |
| `:plan/reached-after` | each reached target: ms from the plan going active to reaching it (plan/reach) | a chained plan, once a target is reached |
| `:plan/go-at` | the landing a fixture named | from a :go with :go/at |
| `:plan/since` | ms the status last changed | from the :go event |
| `:plan/blacklist` | targets that failed, skipped when choosing; never the cell a :place or a :redig chose | from the :go event |
| `:plan/trunk-failures` | failed gather intents per trunk column [x z]; a column that failed twice is skipped when choosing a log | from the :go event |
| `:plan/attempts` | intent failures in a row, a walk that failed :no-path aside (:plan/unwalkable) | from the :go event |
| `:plan/unwalkable` | walks in a row that failed :no-path; max-unwalkable of them fail the plan | from the :go event |
| `:plan/boxed-at` | feet cells where a staircase or a tunnel died :boxed: no descent or tunnel starts there again, no stair steps onto one | after a :boxed staircase or tunnel |
| `:plan/intents` | intents started so far; the next one's :intent/id | after the first intent |
| `:plan/intent` | the intent being executed | while one runs |
| `:plan/last` | the last intent that ended, with its outcome | after one ends |
| `:plan/reason` | why the plan failed | after it failed |
| `:plan/waiting` | why the planner is waiting | after the first wait |
| `:plan/waiting-since` | ms the current wait began | while waiting |
| `:plan/wait` | a planner answer meaning: nothing to do yet, for this reason | returned by next-intent, never stored |

### 24.3 `:world/*`

| Key | Meaning | When |
|---|---|---|
| `:world/traps` | every trap escaped: {:trap/pos [x z] :trap/kind :trap/at}, for routes to keep away from | after the first escape |
| `:world/chunks` | {[cx cz] column}: chunks the server has sent and not forgotten | always |
| `:world/blocks` | {[x y z] state}: block updates and own breaks over the chunks | always |
| `:world/unconfirmed` | {[x y z] ms}: cells the bot's own dig emptied in the overlay before the server said so, and when; the server's block-update there (the break, or the block put back) takes each out, and one the server stays silent on for terrain/unconfirmed-ms is forgotten, the chunk's block back in view | after the first own break |
| `:world/entities` | {entity-id {:entity/type :entity/pos :entity/seen-at}}: tracked items | always |
| `:world/facts` | memory: DataScript value of append-only observations, never retracted | always |

### 24.4 `:trap/*`

| Key | Meaning | When |
|---|---|---|
| `:trap/pos` | the column [x z] a trap began in | inside :world/traps |
| `:trap/kind` | what the trap was, :water or :lava | inside :world/traps |
| `:trap/at` | ms the trap cleared | inside :world/traps |

### 24.5 `:window/*`

| Key | Meaning | When |
|---|---|---|
| `:window/grid` | the 2x2 crafting grid of window 0: {cell item}, never inventory | always |
| `:window/cursor` | the item on the cursor | while something is carried |
| `:window/state-id` | window 0's last server state id | after the first window 0 content |
| `:window/open` | the open container {:window/id :window/menu-type ...} | while one is open |
| `:window/id` | container window id | inside :window/open |
| `:window/menu-type` | container menu type id (12 is a crafting table) | inside :window/open |
| `:window/slots` | the open container's slots | inside :window/open |
| `:window/data` | the open container's numbered properties {property value} (a furnace: 0 fuel left, 1 fuel total, 2 cook progress, 3 cook total) | inside :window/open, once the server sends one |

### 24.6 `:sight/*`

| Key | Meaning | When |
|---|---|---|
| `:sight/pos` | an observation's block position | a fact in :world/facts |
| `:sight/state` | the state id observed there | a fact in :world/facts |
| `:sight/at` | ms of the observation | a fact in :world/facts |

### 24.7 `:pool/*`

| Key | Meaning | When |
|---|---|---|
| `:pool/chunk` | the chunk [cx cz] a pool fact summarises | a fact in :world/facts |
| `:pool/kind` | the pool's fluid, :water or :lava | a fact in :world/facts |
| `:pool/pos` | the chunk's highest surface source of that fluid, nearest its centre | a fact in :world/facts |
| `:pool/at` | ms the pool was seen | a fact in :world/facts |

### 24.8 `:intention/*`

| Key | Meaning | When |
|---|---|---|
| `:intention/id` | which intent a fact is about (its :intent/id) | a fact in :world/facts |
| `:intention/event` | what happened to it: :started :done :failed or :abandoned | a fact in :world/facts |
| `:intention/kind` | the intent's kind | a fact in :world/facts |
| `:intention/at` | ms it happened | a fact in :world/facts |
| `:intention/target` | the intent's target | a fact, when the intent had one |
| `:intention/recipe` | the intent's recipe | a fact, when the intent had one |
| `:intention/reason` | why it failed or was abandoned (:goal-met, :preempted) | a :failed or :abandoned fact |

### 24.9 `:answer/*`

| Key | Meaning | When |
|---|---|---|
| `:answer/kind` | which server answer to the bot's own action: :ack, :pickup, or :death (health reached zero) | a fact in :world/facts |
| `:answer/at` | ms the answer arrived | a fact in :world/facts |
| `:answer/sequence` | the block-interaction sequence the server acknowledged | an :ack fact |
| `:answer/entity` | the item entity the bot picked up | a :pickup fact |
| `:answer/count` | how many items that pickup gave | a :pickup fact |

### 24.10 `:control/*`

| Key | Meaning | When |
|---|---|---|
| `:control/forward?` | walk forward | inside :player/controls |
| `:control/jump?` | jump when allowed | inside :player/controls |
| `:control/yaw` | heading to walk on | inside :player/controls |
| `:control/look` | [yaw pitch] to face | inside :player/controls |
| `:control/sneak?` | sneak: in a liquid, swim down | inside :player/controls |

### 24.11 `:intent/*`

| Key | Meaning | When |
|---|---|---|
| `:intent/bank` | the cell a :swim-out swims to, chosen once | while a :swim-out has one |
| `:intent/wet?` | a :wade has had the body in water at some point | while a :wade runs |
| `:intent/water` | the water cell a :wade enters from its bank (:intent/target) | while a :wade has an entry |
| `:intent/dig-when-down` | the cell a :swim-out will dig once it has sunk to the floor | while a :swim-out sinks |
| `:intent/next-search` | the tick a :swim-out with no bank yet looks for one again | while a :swim-out rises with no bank |
| `:intent/exposes-ok?` | a :dig that may open a cell beside water or lava (an escape digs beside the water it is in) | on an escape's child dig |
| `:intent/passage-ok?` | a :place that may put its block in a passage, since it is dug again at once (a gravel for flint) | on a redig's child place |
| `:intent/kind` | executor: :walk :dig :collect :craft :place :open-container :stairs-down :stairs-up :tunnel :tunnel-to :smelt :redig :scoop :equip :build :pour :use-on :await | every intent |
| `:intent/status` | :active, :done or :failed | every intent |
| `:intent/id` | the intent's number in this run, linking its facts in memory | every started intent |
| `:intent/goal` | the target goal id the intent serves; another target chosen preempts it | intents the planner starts |
| `:intent/reason` | why it failed, or why the planner abandoned it | a failed or abandoned intent |
| `:intent/target` | block position it acts on or walks to | most intents |
| `:intent/for` | what the intent serves (:log, :log-hunt, a mining row's id), so chains continue only their own | gather-chain intents |
| `:intent/pickup?` | a walk toward a block to mine from beside: it ends where the dug block's drop lands in the pickup box | a :mine walk |
| `:intent/step-down?` | a :mine walk toward a block in the floor: it may end a block up beside it, the drop landing in the hole the dig leaves | a :mine walk to a block that leaves a step down |
| `:intent/cell` | the cell a :place puts its block in, given (a build's), not chosen | a build's :place |
| `:intent/click-face` | the face of the clicked block a placement clicks, toward the cell it fills | :place, once it has a spot |
| `:intent/build` | which plan a :build works: :cast-one, :portal or :nether | :build |
| `:intent/origin` | a build's frame origin [bx by bz] | :build |
| `:intent/facing` | the side of the frame a build works from: :south :north :east :west | :build |
| `:intent/access` | a build's lava height over its room's floor, which its access steps climb to (build/station) | :build |
| `:intent/op` | the plan op a :build or a :tunnel-to step works now; a new op drops the child | :build, :tunnel-to |
| `:intent/aim` | the point a :pour or :use-on looks at to act, and a :scoop through a cell's open side | :pour, :use-on, a side :scoop |
| `:intent/expect` | the predicate (build/holds?) that shows a :pour, :use-on or :await worked | :pour, :use-on, :await |
| `:intent/fluid` | what a :scoop takes: :water (when absent) or :lava | a :scoop for lava |
| `:intent/steps` | how many steps a :tunnel-to takes before the planner looks again | :tunnel-to |
| `:intent/taken` | steps a :tunnel-to has taken | :tunnel-to, after its first step |
| `:intent/move` | the step a :tunnel-to is taking, [dx dy dz], chosen when it starts | :tunnel-to, during a step |
| `:intent/from` | the feet a :tunnel-to's step started from, where its ops are worked | :tunnel-to, during a step |
| `:intent/trunk` | bottom log of the trunk a gather chain works; a failure blacklists it whole | gather-chain intents |
| `:intent/stage` | executor stage, e.g. :settle :digging :click :verify :take | staged intents |
| `:intent/since` | ms the stage began | staged intents |
| `:intent/started` | ms the intent began | walk and dig |
| `:intent/still` | ticks the bot has been still | settling intents |
| `:intent/face` | block face it acts on | dig |
| `:intent/next-swing` | ms of the next arm swing | dig |
| `:intent/finish-at` | ms after which FINISH may be sent | dig |
| `:intent/slot` | hotbar slot the dig holds its tool in (0 for the hand) | dig, from START |
| `:intent/tool` | item id of the tool the dig holds, nil for the hand | dig, from START |
| `:intent/dig-ms` | ms the server needs to break the target with that tool | dig, from START |
| `:intent/state` | the target's state id at START; the cell still in it when the ack of FINISH comes means the server did not break it | dig, from START |
| `:intent/confirm-left` | ticks left to wait for the server's word after FINISH | dig, :confirm stage |
| `:intent/to-y` | feet height at or below which the staircase is done | stairs-down |
| `:intent/to-height?` | a :stairs-up that ends only at :intent/to-y: the open sky over a pit's floor is not out | a climb out of a pit under the sky |
| `:intent/dir` | [dx dz] heading the next stair is cut toward | stairs-down, once planned |
| `:intent/turns` | quarter turns taken after refused stairs; four fail :boxed | stairs-down, after a refusal |
| `:intent/rejections` | digs of the current stair (or level step) the server did not do, each dug again; stairs/max-rejections fail :rejected | stairs-down, after a child dig rejected |
| `:intent/cells` | the three cells the current stair opens, top first | stairs-down, while a stair is cut |
| `:intent/level-cells` | the two cells a level step opens, head then feet, when every stair down was refused | stairs-down, during a level step |
| `:intent/lay` | the cells a block is laid in, in order, before a stair or level step is cut: a floor and its holder (a holder under the block under the feet, then the floor under the new feet) for a stair down, a missing step for a stair up, or the water cells beside a stair up (a seal, at most the four neighbours of each of three cells) | a staircase, while cells are laid |
| `:intent/level-steps` | level steps this staircase (past stairs/max-level-steps it fails :boxed) or tunnel (done at its length) has taken | stairs-down and tunnel, after a level step |
| `:intent/length` | level steps a tunnel cuts | tunnel |
| `:intent/side` | the side a tunnel steps to, keeping its heading, where the heading is refused | tunnel, once one is taken |
| `:intent/level-dir` | the way the staircase's last level step went; the next keeps it while allowed | stairs-down and stairs-up, after a level step |
| `:intent/visited` | the feet cells a staircase's level steps and ducks have stood on; a step or stair onto one comes after every other way (stairs/step-aside) | stairs-down and stairs-up, after a level step |
| `:intent/rise` | which way the feet go for the stair being cut when it is not the staircase's own: -1 for a climb's stair down under water (stairs/duck) | stairs-up, from a duck until its stair is taken |
| `:intent/child` | the child intent run in the intent's place by its own executor (stairs: the :dig cutting one cell; redig: the place, dig or collect it is on) | stairs-down, while digging; redig; place, while digging a niche |
| `:intent/started-y` | feet height when the current stair began; a step is judged by going below it | stairs-down, per stair |
| `:intent/started-tick` | tick the current stair or its step began | stairs-down, per stair |
| `:intent/best-dist` | closest it has come to the target | walk and collect |
| `:intent/best-tick` | tick of the last progress | walk and collect |
| `:intent/detour-until` | tick a detour ends | walk, while detouring |
| `:intent/detour-yaw` | heading offset of the detour | walk, while detouring |
| `:intent/detours` | detours taken | walk |
| `:intent/waypoints` | the route's feet cells still to walk, from clojurecraft.path | walk, once planned |
| `:intent/route` | how the route ended: :found, :partial (budget spent) or :none (no way on) | walk, once planned |
| `:intent/at` | index of the next waypoint | walk, once planned |
| `:intent/replans` | routes planned for this walk; more than intent/max-replans fails :no-path | walk, once planned |
| `:intent/planned-chunks` | chunk columns loaded when the route was made (kept for the record; a new chunk no longer replans by itself) | walk, once planned |
| `:intent/logs-before` | logs held when collecting began | collect, before :intent/drops |
| `:intent/drops` | the item ids a collect is for: one more of any of them than it began with ends it | collect, redig |
| `:intent/drops-before` | items of :intent/drops held when collecting began | collect |
| `:intent/blocked-by` | leaf block over the drop | collect, when the drop is covered |
| `:intent/clears` | leaf blocks cleared for this drop | a collect resumed after clearing |
| `:intent/resume` | the intent to run after clearing a blocker | a dig that clears a blocker |
| `:intent/recipe` | recipe id to craft | craft |
| `:intent/window` | :inventory or :table | craft |
| `:intent/clicks` | clicks still to send | craft, :click stage; smelt, :load stage |
| `:intent/result` | result item id | craft, smelt |
| `:intent/makes` | items one craft yields; for a smelt, the batch | craft, smelt |
| `:intent/before` | result items held before crafting, smelting or scooping | craft, smelt, scoop |
| `:intent/smelt` | furnace recipe id to cook (smelting.edn) | smelt |
| `:intent/count` | how many of the input the batch cooks | smelt |
| `:intent/lost` | clicks the server lost in this intent; the intent resumed from its slots | craft, smelt, place, equip, scoop, pour, after a lost click |
| `:intent/resync` | the next click is a resync: the cursor after a lost click is unknown | craft, smelt, place, equip, scoop, pour, from a lost click until the resync is sent |
| `:intent/cook-by` | ms by which the batch must be cooked; the cook stage fails after it | smelt, :cook stage, once the furnace is loaded |
| `:intent/awaiting` | state id a click waits on | after a click |
| `:intent/awaiting-window` | window id that click went to | after a click |
| `:intent/sent-at` | ms a click or placement was sent | after sending |
| `:intent/item` | item to place | place, redig |
| `:intent/against` | block the placement is against | place |
| `:intent/refused` | cells the server refused a placement into with nothing in them (a body stood there); the next spot is another, place/max-refusals of them fail :rejected | place, after such a refusal |
| `:intent/sequence` | sequence number of the placement, or of the bucket's use | place and scoop, after sending |
| `:intent/tries` | scoops sent at this source; the last of scoop/max-tries fails :no-scoop | scoop, after a scoop went unanswered |

---

## 25. Every namespace, in a line or two

From each namespace's own docstring (`src/clojurecraft/<name>.clj`), first sentence or two. Before changing one, read its whole docstring and its brain note.

| Namespace | Lines | What it is |
|---|---|---|
| `blocks` | 253 | Block-state, item and entity-type tables generated from the vanilla reports, and the hardness, harvest and tool-material tables generated from the game's own classes and tags (resources/clojurecraft/*.edn). Lookups are by numeric id; nothing here is mutable. |
| `build` | 1137 | Building from a plan that is data (issue #8, the portal cast). A plan is a vector of ops, flat maps. |
| `bytes` | 194 | Wire primitives of the Minecraft protocol. Readers take a java.nio.ByteBuffer whose position is the cursor; writers take a java.io.DataOutputStream. |
| `capture` | 186 | Real worlds for the server model: a real server's chunk columns round each landing of a landing set, saved byte for byte as the server sent them, one world file a landing. clojure -M:capture --port 25565 --rcon-port 25575 --rcon-pass S \\ --landings cache/worlds/R1/landings.edn --set R1 --out cache/sim-worlds/R1 [--radius 10] The model (sim.clj) then holds such a world whole and plays the bot in it with no Java ... |
| `chunk` | 153 | Chunk columns as values. A column is {:sections [section ...] :chunk/min-y y}: 24 sections from y -64 in the overworld, 16 from y 0 in the Nether and the End, as many as the server sent (a column without :chunk/min-y is an overworld one). |
| `claims` | 43 | The one thing racing bots share (issue #17, docs/RICH-HICKEY.md "Conveyance, bounded"): which landing cells are taken. Claims are values and the claims are a value; `claim` is a pure reducer, first come first served, so no cell is ever granted twice. |
| `cli` | 51 | What every command-line entry point shares: reading --key value arguments, and one timestamped line on stderr (stdout is each process's own channel: RESULT for the bot, the :go event for the fixture, a report for the gym and a plan), and starting another process. |
| `conn` | 117 | The socket. Frames in, frames out, compression, and which protocol state the wire is in. |
| `craft` | 112 | The :craft intent: one craft of a recipe, in the player's own 2x2 grid or in an open crafting table. {:intent/kind :craft :intent/recipe :stick :intent/window :inventory} ; or :table Stages, each advanced one tick at a time against the world value: :settle put a loaded cursor down, shift-click every item left in the grid back into the inventory, then plan the clicks from the inventory (window 0 also closes any ... |
| `dig` | 85 | How long a block takes to break, and whether it drops anything, as pure functions of the block, what is held and the two conditions the game penalises (the eyes in water, the feet off the ground). This is vanilla's own rule (the siblings reproduced it from the client): damage per tick = speed / hardness / (30 when the tool harvests the block, else 100) ticks = ceil(1 / damage), or 0 when damage >= 1 where speed ... |
| `dimension` | 32 | The dimensions as data (resources/clojurecraft/dimensions.edn, generated from the server jar's dimension types): each one's name, registry id, lowest block y and height. Which one the bot is in is :player/dimension, set by login and respawn; before either it is the overworld. |
| `failure` | 42 | What a failed run was doing and why, as a value both races share: a run against the model (simrace) and a run on a real server (race) that stop the same way carry the same signature, and one signature is one issue however many runs hit it (bb failures). |
| `fixture` | 137 | What every fixture asks the server, over RCON, and the one thing it tells the bot. The forest harness (clojure -M:harness), the gym's land and judge, the landing-set builder and a plan are all fixtures: each waits for a player, loads columns, teleports onto a surface, judges a landing by the server's own answers, and hands the bot a :go event. |
| `fluid` | 233 | Water and lava as the server moves them: the rules of vanilla's FlowingFluid, WaterFluid, LavaFluid and LiquidBlock (26.1.2), as values and pure functions over a cell lookup. A fluid state is a map, the server's FluidState: {:fluid/kind :water/:lava :fluid/amount 1-8 :fluid/source? bool :fluid/falling? bool}. |
| `fortress` | 187 | Reaching a nether fortress (issue #11): the :fortress goal and the :tunnel-to intent (stone's :tunnel digs along a direction for ore; this one goes to a target). Finding the fortress is passive: its bricks are sightings like logs (memory/landmark-kinds), remembered as each chunk loads, in the dimension they were seen in. |
| `game` | 473 | The world as one value, and the protocol as a pure reducer over events. (step world event) → world' The world is a flat map of namespaced attributes (:player/pos, :world/chunks, :bot/phase ...), open and sparse: an attribute the bot does not know yet is simply absent. Events are maps: {:event/kind :start :start/host h :start/port p :start/name n} ; connection, all optional {:event/kind :packet :event/packet pkt} ... |
| `gym` | 640 | The gym: real runs of one goal on GitHub runners, each on its own vanilla server and its own landing, judged twice, by the bot's RESULT and by the server's own answer to a truth command. Runs are processes speaking data, like everything else: clojure -M:gym trial ... start the bot as its own JVM, land it over RCON, send its :go through its stdin, judge it from its log and the server; one more attempt on the same ... |
| `harness` | 55 | The forest fixture, as its own process. It never touches the bot: it waits until the bot is online, finds a forest and lands the bot there using only RCON, judges the landing by asking the server, and then speaks to the bot through a queue - one EDN event on stdout, which the bot reads from stdin: clojure -M:harness --rcon-port 25575 --rcon-pass S --name Clj_wood --until wood \\ / clojure -M:run --port 25565 ... |
| `intent` | 590 | Intents are values: {:intent/kind k :intent/status :active/:done/:failed ...}. The planner puts one in :plan/intent; `run` advances it by one tick, returning the world with the intent, :player/controls and effects updated. |
| `inventory` | 222 | The player's items and screen as values: which slot of which window is which inventory slot, how a slot update changes :player/inventory, :window/grid and :window/open, and what the bot holds. Pure functions of the world; the packet handlers in game call them. |
| `landings` | 144 | Landing sets for the gym: fixed, pre-checked places in a pregenerated world, so run k of every batch lands on the same spot and batches compare like with like (paired runs). A set is data in ci/gym.edn: a centre far from world spawn, n grid cells spacing apart, the biome each cell snaps to, and the radius pregenerated around each landing. landings! builds the set against a running server over RCON: each cell ... |
| `main` | 303 | The loop: the one place with an atom, a clock, randomness and effects. Reads packets and external events, feeds the reducer, drains :bot/effects, prints RESULT. clojure -M:run --port 25571 [--name N] [--until play/wood/table/pickaxe] [--chain true] [--events stdin] [--timeout-ms 120000] [--hold-ms 0] [--record run.edn [--commit sha]] [--telemetry t.edn] clojure -M:replay run.edn With --events stdin the bot reads ... |
| `make` | 464 | The needs planner and the acts of the goal table. A need is a key and an amount: :item/<name> n and :tag/<item-tag> n are counted in the inventory, :block/<name> :near is a remembered block within reach of a walk, and :fuel/ticks n is fuel that burns n game ticks (a smelt's), counted from what the inventory would burn. |
| `memo` | 58 | A pure function's answers remembered by its inputs, where the inputs are a world's parts. A world's parts are values and never change: the facts, the chunks, the block overlay. |
| `memory` | 404 | What the bot has seen, as facts with time. The store is a DataScript database held as a value in the world (:world/facts). |
| `model` | 380 | The information model, in writing: every attribute the bot knows, with a one-line meaning and when it exists. docs/RICH-HICKEY.md starts here ("Write the attribute list with namespaced names and one-line meanings. Everything after this is a function over that model."). |
| `packet` | 371 | Packet specs as data, and one interpreter that reads and writes them. A packet is a flat map with a :packet/name; its fields are the wire's own names, unqualified, because they are scoped by the packet. |
| `path` | 453 | A route over the block grid as a pure function of the world value (issue #4). (plan world from goal opts) → {:path/waypoints [[x y z] ...] :path/cost n :path/status s} A node is the feet cell [x y z]: standing there means a floor at y-1 and the body and head clear. Moves are data (`moves`): what cell each leads to, what it costs, and which cells must be what for it to be allowed; `neighbors` interprets them. |
| `physics` | 273 | Vanilla land and liquid movement as a pure function over the world's :player/* attributes. Full-cube collision only; no step-up (a one-block ledge needs a jump, as in vanilla). (step solid? world controls) → world' dry: no fluid anywhere (step solid? fluid-at world controls) → world' with fluids solid? is (fn [x y z] bool) over block coordinates; fluid-at is (fn [x y z] → nil / {:fluid/kind :water/:lava ... |
| `place` | 399 | Right-clicking blocks: placing a held block, and opening a container. {:intent/kind :place :intent/item :crafting_table} {:intent/kind :open-container :intent/target [x y z]} Both send use-item-on and judge success only by what the server reports: a block update that shows the block (placing), or an open-screen with the window's contents (opening). Nothing is predicted; a rejected placement comes back as a block ... |
| `plan` | 368 | Goals are data; planning is a function; executing is open. `goals` is the table in resources/clojurecraft/goals.edn: rows of {:goal/id :goal/priority :goal/needs :goal/provides :goal/done? :goal/act}. Two open registries, keyed by data in the row, give it meaning: `done-by` (keyed by :goal/done?, the name of a predicate) and `act` (keyed by :goal/act, what to do once the row's needs are met). `next-intent` (keyed ... |
| `plans` | 341 | Run one plan file across many jobs. A plan (ci/plan/<label>.edn) is data: how many jobs, how wide, and the experiments to run. |
| `race` | 495 | A race (issue #17): many bots on one server, from where each lands to the race's target, the server always full (issue #127): :race/bots slots, each starting a fresh bot the moment its bot ends, for the job's length (run-ms), so no bot waits for another and a slow one plays as long as it likes; a bot the job's end stops is cut (failure/cut), never a failure. clojure -M:race --config ci/race.edn --port 25565 ... |
| `rcon` | 93 | Minimal RCON client, used by the harness for fixtures and by CI as the independent judge. `clojure -M:rcon --host H --port P --pass S <command words>` prints the response. |
| `recipe` | 200 | Crafting as data. `recipes` is the vanilla crafting table (resources/clojurecraft/recipes.edn, tags already resolved to item-name sets); everything here is a pure function over it. - `match`: what a grid crafts (the server's rule, used by the sim). - `clicks`: the container clicks that lay one craft into the grid, from the inventory value. - `needs`: what one craft consumes; clojurecraft.make turns every recipe ... |
| `record` | 142 | Every input is a value, so a run is a file. `tap` writes each event as one EDN line and, after it, the effects that event produced as one {:record/effects [...]} line (events with no effects get none). `events` reads the events back; `replay` folds a reducer over them with no server; `verify` also checks that the reducer, given the same events, asks for exactly the same effects. Byte arrays print as ... |
| `replay` | 12 | clojure -M:replay run.edn → fold the whole bot over a recording, print RESULT. No server. |
| `scoop` | 484 | Filling a bucket with water: the :scoop act of the goal table and the :scoop intent. {:intent/kind :scoop :intent/target [x y z]} ; a water source within reach The act looks for a surface water source in the loaded terrain near the bot (never deeper than memory/surface-water-below under the eye: cave water is the aquifer trap the siblings drowned in), scoops it when it is in reach, else walks to it, else walks to ... |
| `sim` | 1579 | A pure model of the vanilla server: enough of it to take a bot from handshake to a held log with no Java process. (step sim event) → sim', where event is a packet the client sent ({:sim/kind :packet :sim/packet p}) or a tick ({:sim/kind :tick :sim/now ms}); packets for the client accumulate in :sim/out. Rules modelled: login/configuration handshake, one teleport to spawn, one chunk column, keep-alives, block ... |
| `simrace` | 337 | The sim race: the whole bot, from empty hands at a landing to the race's target, against the server model (sim.clj) in captured real worlds (capture.clj), as many runs as a machine's cores can play, with no Java server. clojure -M:simrace --worlds cache/sim-worlds/R1 [--seeds 8] [--until nether] [--max-minutes 90] [--threads n] [--out data/simrace] A run is a pure function of three inputs: the world file, the ... |
| `smelt` | 262 | The :smelt intent: cook a held input in an open furnace and take the result. {:intent/kind :smelt :intent/smelt :iron_ingot_from_smelting_raw_iron :intent/count 4} Stages, each advanced one tick at a time against the furnace window's view: :settle put a loaded cursor down and shift-click anything left in the furnace back into the inventory, then plan the loading clicks: the fuel first (only as much as the batch ... |
| `spec` | 682 | Specs for the information model: world attributes, events, effects, intents, packets. Shape lives here (what an attribute is); what a function requires is stated by that function. |
| `stairs` | 908 | Going down safely: the :stairs-down intent, which cuts a staircase one stair at a time in the direction it faces until the feet are at or below a height, and never digs the block under the feet (a blind drop is how the siblings' bots died). Where a stair has no floor under its new feet (the staircase broke into a cave) and a block to lay one from is carried, the floor is laid first, from cobblestone, against the ... |
| `stone` | 371 | The :mine act of the goal table (clojurecraft.make runs it, as :gather runs clojurecraft.wood): get what a row's blocks drop. A mining row names its blocks as data (:goal/blocks, stone for cobblestone, coal or iron ore, gravel) and how deep it may cut looking for them (:goal/depth, none for stone). |
| `swim` | 455 | Getting out of water and away from lava (issue #5). Requiring this namespace registers the escape goals' rules: a goal is done when the planner's trap (trap/trapped-tick) is not of its kind, its next intent is :swim-out or :flee, and the two executors are defmethods of intent/run. :swim-out picks one bank and keeps it (the siblings re-picked every tick and swam in circles): the nearest cell the feet could stand ... |
| `terrain` | 143 | The blocks around the bot as values: chunk columns kept as they arrived, an overlay of block updates and the bot's own breaks over them, and the questions physics and the planner ask of both. Every change here is also handed to memory, so what was seen outlives the chunk. |
| `trap` | 54 | Whether the player is trapped in water or lava, re-derived every tick from the physics' flags and the terrain. The planner calls trapped-tick before it chooses a goal, so the escape goals (clojurecraft.swim) can preempt whatever runs; swim reads dry-land? to know when the bot is out. |
| `watch` | 73 | Observers of the world atom. docs/RICH-HICKEY.md: "Telemetry becomes a watcher that diffs successive values and writes facts. Neither can slow the loop or see a torn state, and neither is mentioned in the game code at all." A watch sees two whole values (old and new), never a half-written one. |
| `window` | 126 | Clicking in a window, as values. A view describes one window the way the click rules need it: which window id, its latest state id, its own slots (a crafting grid, a furnace's input, fuel and output), and where each player-inventory slot appears in it. |
| `wood` | 188 | Getting a log: walk to the nearest remembered trunk, dig the log nearest the bot's own feet height (so the drop lands where the bot can stand, not in a hole under the rest of the trunk), pick up the drop. The :gather act of the goal table (clojurecraft.make) runs this. |

---

## 26. The tests, and how to write one you cannot run

You will not run a test locally (rule 1.3); the PR's `unit / unit` check runs them all on a runner in two to four minutes. That makes a test you write blind worth more care, not less: read the tests around it first and copy their shape.

### 26.1 Where tests live

One test namespace per source namespace, in `test/clojurecraft/`: `build_test.clj` for `build.clj`, `scoop_test.clj` for `scoop.clj`, and so on. A few cover more than one namespace:

| Test namespace | What it holds |
|---|---|
| `plan_test.clj` | the planner end to end over canned events: a world built by hand, the reducer folded over `:go`, ticks and packets; the wood, kit and stairs scenarios (`shaft`, `quarry`, `crevice`, `canopy-pocket`, `sky-shaft`) |
| `sim_test.clj` | the whole bot against the server model, from a kit or from spawn, in small built worlds; the portal from a kit, the cast, the timeouts. The largest test namespace |
| `props_test.clj`, `make_test.clj` | test.check properties: every keep-alive answered, the phase stays in the transition table, `step` is total, the needs planner never picks a goal whose needs are unmet |
| `issues_test.clj` | the issue check: every snapshot under `test/issues/<n>/` judged (`simrace/judged`); `every-snapshot-fits-on-github` |
| `recordings_test.clj` | promoted recordings under `test/recordings/` replay with every effect verified |
| `model_test.clj`, `docs_test.clj`, `style_test.clj` | every key the bot writes is in `model.clj`; every public function has a docstring; no `if` nested in an `if` |
| `packet_test.clj`, `packet_specs_ever.edn` | packet decoding; specs only ever grow (`packet-specs-only-grow`) |
| `problems_test.clj` | `problems.edn` is well formed, a row per stage |
| `fixtures.clj`, `world.clj`, `runner.clj` | shared helpers (below) and the parallel test runner (eftest) |

### 26.2 Building a world by hand

A test builds the smallest world that has the situation, calls the function, and asserts the answer.

- `clojurecraft.world` (test/clojurecraft/world.clj): `world/column` takes a map of `[x y z]` to block state and returns a decoded chunk column (`direct-section`, `single-section`, `column-bytes` underneath). Block states come from `blocks/default-state` (`(blocks/default-state :stone)`), or the named constants (`blocks/air`, `blocks/water-base`, `blocks/lava-base`).
- Most source-adjacent test namespaces define their own builders at the top. In `scoop_test.clj`: `standing` (a world with the feet at a place and a column of blocks round it), `intent-step`, `wide`, `deep-in`, the fixtures `pond`, `cave-pool`, `low-ground`, `mine`. In `build_test.clj`: `open-ground`, `standing-at`, `ops-of`, `obsidian-at`, the test.check generators `gen-origin`, `gen-facing`, `gen-height`. In `plan_test.clj`: `world-state`, `run` (fold the composed reducer over events), `remembered`, `walking`.
- A world for a decision function needs only the keys that function reads (the selection `fdef`s in `spec.clj` list them: `spec/requires`). Unknown chunks read as solid, so a world without a chunk under a cell treats it as rock.

### 26.3 The shape of a good test

```clojure
(deftest a-lone-lava-source-is-no-site
  ;; sim race 38664661309 (issue #142): five worlds chose a site beside one source,
  ;; cast one cell from it and failed :no-station with nine cells to go
  (let [w (standing-at (open-ground {[10 62 10] blocks/lava-base}) [6 63 8])]
    (is (nil? (build/choose-site w)) "one source cannot finish a frame")))
```

- Name it for the behaviour, as a sentence (`a-climb-under-water-ducks-before-it-is-boxed`).
- The comment at its top names the issue and the round.
- Assert the decision, not the mechanism: the intent's kind and target, the wait's reason, the site or `nil`. A test that asserts which helper was called breaks on the next refactor.
- Make it fail without the fix. You cannot run it to see it fail; reason it through against the old code, and when unsure, push the test alone first and let the PR's `unit / unit` show it red, then push the fix.
- Add the test to the brain note's `sourceRefs` (`test/clojurecraft/<ns>_test.clj#<test-name>`).

### 26.4 When the suite goes red on something you did not touch

- Another test asserted the behaviour you changed: read it. If it asserts the old, wrong behaviour, update it and say so in the PR body; if it guards a rule your change broke, your change is wrong.
- An earlier issue's snapshot under `test/issues/` now recurs: your rule conflicts with that issue's fix. Read its PR; find the version that satisfies both.
- `model_test` or `docs_test`: a key without its model row or spec, a public function without a docstring.
- Flaky? Rarely. The sim is deterministic by seed. Rerun once (`gh run rerun <id> --failed`); if it fails the same way, it is real.

---

## 27. The CI, the workflows and their artifacts

Everything heavy runs here. Know which workflow made what you are reading.

| Workflow | Runs on | What it does | Artifacts |
|---|---|---|---|
| `race.yml` | every push to `main` (code, not docs), a schedule backstop, by hand | the real race: 8 runners x 2 servers x 25 bot slots on the pregenerated landing sets, refilled as bots end, 330 minutes; reports every 20 minutes per runner (`bb churn batch`) | `race-<sha>-<runner>`: `s1/`, `s2/` with `race.log`, `server.log`, `samples.edn.zst`, `milestones.edn.zst`, a recording per slot |
| `simrace.yml` | the same | the sim race: 8 runners, the bot against the model in the captured worlds, seed after seed, every core; the same 20-minute reports | `simrace-<sha>-<runner>`: `runs.edn`, `simrace.log`, `snapshots/` |
| `test.yml` | every push, any branch | `unit` (via `unit.yml`), one forest shard of the sim property, `workflows` (actionlint) | none |
| `unit.yml` | called by `test.yml` and `issue.yml`; by hand | every test but the forest property (`bb test`), then the static checks (`bb check`) | none |
| `issue.yml` | a pull request from a `fix/*` branch | `check`: every `Fixes #n` labelled `sim` must have snapshots; then `issues_test` judges every snapshot; plus `unit / unit` | none |
| `probe.yml` | by hand | one command on a branch, with the sim worlds asked for (section 8) | `probe-<id>`: whatever was written under `out/` |
| `gym.yml` | by hand | N trials of one goal on real servers, one pregenerated landing per trial, judged by RESULT and the server's truth commands | `gym-...` recordings and logs |
| `world.yml`, `simworld.yml` | by hand when a key changes (and called) | build a landing set's world once and cache it; capture the sim worlds from it once | caches, not artifacts |
| `pages.yml` | dispatched by each report | deploys the results page from `gh-pages` | the Pages artifact |
| `plan.yml`, `timer.yml`, `capacity.yml`, `full.yml` | by hand or schedule | plan files of experiments, the capacity study, one fresh-world attempt from spawn | their own |

Facts that matter when reading CI:

- The rounds are cancelled and restarted by every code merge to `main` (`cancel-in-progress` on push). They keep their artifacts and run their last report through a cancel.
- Artifacts are kept 7 days. A reproduction in an older comment may name one that is gone.
- Required checks on `main`: `unit / unit` and `check`. `check` reports "skipping" on a PR that is not from a `fix/*` branch, which GitHub counts as passing.
- A PR with merge conflicts runs no checks at all.
- Job logs are only available when the job ends; a running race's logs are not readable until then.
- The runners are 20 in all. The rounds hold 16; checks, probes and gyms share the other four and queue when they are full. A queued check is not a failed one.

---

## 28. `RICH-HICKEY.md` in this code: the patterns to copy

Section 0.2 is the essay; this is where each commitment lives in the code, so a fix can copy the pattern instead of inventing one.

- **One value, one writer.** The world is one map in one atom; only the loop in `main` swaps it, with `(swap! world* step event)`. Every other namespace is a function of the value. A fix never adds an atom, a `def` that changes, or a side table.
- **The reducer and its composition.** `game/step` is `(step world event) -> world`; the planner `plan/step` has the same signature and is composed after it (`game/compose`). Tests compose them the same way (`(def step (game/compose game/step plan/step))` in `plan_test`).
- **Events are maps; time and randomness are inputs.** `{:event/kind :tick :event/now ms :event/rand r}`. Deadlines are absolute ms in the world (`:intent/finish-at`), compared against `:time/now`. A fix that needs "a while" stores a deadline, never sleeps.
- **Effects are data.** A function that wants to send a packet adds `{:effect/kind :send :effect/packet p}` to `:bot/effects`; the loop performs and clears them. Logging is an effect too (`game/say`).
- **Open dispatch.** Packets: `game/on-packet` on `[phase name]`. Intents: `intent/run` on `:intent/kind` (and stage multimethods like `intent/dig-stage`, `stairs/stairs-stage`, `scoop/scoop-stage`, `place/place-stage`). Goals: `plan/done-by` on `:goal/done?`, `plan/act` on `:goal/act`, `plan/next-intent` on `:goal/plan`. A new behaviour is a new `defmethod`, usually in the namespace that owns the subject.
- **Goals and builds are data.** `goals.edn` rows; `build/portal-plan` returns a vector of ops, each with a `:build/done` predicate read from the world (`build/holds?`), and the first op not done is the one worked. Nothing records progress: a death or a restart resumes at the first unsatisfied op.
- **Facts with time.** `memory.clj`: sightings, intentions and the server's answers as DataScript facts, never retracted; "the nearest log I have seen and not seen gone" is a query. A fix that needs to remember something writes a fact or a namespaced world key (`:plan/boxed-at`), never a hidden field.
- **The information model.** `model.clj`: every attribute with its meaning and lifetime; `spec.clj`: its spec; the selection `fdef`s say which attributes each world-reading function needs.
- **Rules as small functions, evidence in the docstring.** Look at any function in `stairs.clj` or `scoop.clj`: a name that says the rule, a docstring that states it and the run that taught it, a body of a few lines. Constants are top-level `def`s with a comment (`(def max-turns 4) ; refusals before a level step, or :boxed`).
- **`cond`, `reduce`, no records, no macros.** `style_test` fails on an `if` nested in an `if`; `reduce` (with `reduced`) over `loop` unless the loop is hot or runs until a condition.
- **Accretion.** A goal row that was wrong is superseded (`:goal/superseded-by`), not rewritten; a key never changes meaning; a packet spec only gains fields.
- **I/O fenced last.** `conn`, `rcon`, `main`, `record`, `watch`, `replay`, `race`, `harness`: everything after a `;;;; I/O ;;;;` fence. A bot fix should touch none of them.

