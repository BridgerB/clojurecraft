# CLAUDE.md

clojurecraft: a from-scratch Minecraft Java Edition bot in Clojure whose purpose is to beat the Ender Dragon with no human input. It is the third bot in the `mc` monorepo next to steve (TypeScript) and ruststeve (Rust) and is deliberately built the opposite way: no mutable Bot object, no framework.

## The brain

`brain/` is the verified knowledge base (method: `/Users/bridger/Developer/BRAIN.md`). Read `brain/_index.md` first, then one MOC, then the few notes a question needs; never dump it. Every verified note cites `path#symbol` anchors at a pinned commit. When a change touches a subsystem, update its note in the same change and run `clojure -M:brain` (four invariants at zero: broken, ambiguous, orphans, unresolved sourceRefs). Sibling-research notes are `draft` until someone opens their anchors.

## Style (the point of the project)

Read `docs/hickey.md` first; it is the design brief. In short:

- **The world is one value in one atom**, written only by the loop in `main`. It is a flat map of namespaced attributes (`:player/pos`, `:world/chunks`, `:plan/intent`, `:bot/phase` ...), open and sparse: what the bot does not know is absent, never nil-filled.
- **The protocol is a pure reducer** `(step world event) → world'` in `game`; the planner `plan/step` has the same signature and is composed after it. Events are maps: `{:event/kind :start}`, `{:event/kind :packet :event/packet p}`, `{:event/kind :tick :event/now ms :event/rand r}`, `{:event/kind :go}`, `{:event/kind :closed}`. The clock and randomness are inputs; nothing inside a reducer reads a clock or calls `rand`.
- **Effects are data** in `:bot/effects` (`{:effect/kind :send :effect/packet p}`, `{:effect/kind :log ...}`); the loop drains and performs them. The protocol phase advances only in `game/emit`, from `packet/transitions`.
- **Packets are maps with a `:packet/name`**; specs are data (`packet/specs`), ids are generated from the vanilla reports. Unknown ids decode to `{:packet/name :unknown}` and are counted, never thrown.
- **Dispatch is open**: multimethods for packets (`game/on-packet` on `[phase name]`), intents (`intent/run` on `:intent/kind`) and goal rows (`plan/done-by` on `:goal/done?`, `plan/act` on `:goal/act`, `plan/next-intent` on `:goal/plan`). A new packet, intent or goal is a `defmethod` in a new namespace. The goal table is data, `resources/clojurecraft/goals.edn`; recipes add generated rows (`make`).
- **Memory is facts with time** (`memory`): `:world/facts` is a DataScript value of append-only facts, never retracted, queried with Datalog: observations `{:sight/pos :sight/state :sight/at}` (kept after chunks unload), the bot's own intentions (`:intention/*`: started, done, failed, abandoned; `intention-as-of` answers what it was doing at any time) and the server's answers to its actions (`:answer/*`: acks, pickups).
- **Every run is a file**: `--record run.edn` writes each event and the effects it produced, through a bounded channel tap with its own writer thread; `clojure -M:replay run.edn` folds the reducer over it with no server and verifies every effect. Keep this true (no hidden inputs). Observers watch the atom and never touch the reducers: `--telemetry` writes what changed, dropping rather than slowing the loop.
- **Specs** live in `spec.clj` (every attribute in `model`, every packet derived from `packet/specs`) and are instrumented in tests; properties in `props_test.clj` and `make_test.clj`; the whole bot runs against the pure server model in `sim.clj` (`sim_test.clj`), including in the 2,000 worlds test.check generates on every push, with no Java process.
- **The hammock**: every stage of the run has a problem statement in `resources/clojurecraft/problems.edn` (needs, risks, done in world terms, the last recorded failure), written before its goal; when a run fails in a new way, its row is updated.
- I/O lives only below a `;;;; I/O ;;;;` fence, in `conn` (the socket and its threads), `rcon`, `main` (the loop, the clock, stderr), `record` (recording files), `watch` (telemetry), `replay`, `race` (many bots, a thread each, the claims loop, the landing fixture and the board), and `harness`, the RCON fixture, which is its own process (`clojure -M:harness`) and reaches the bot only as EDN events on its stdin (`--events stdin`). Everything else is values in, values out.

Every public function has a docstring stating what it returns and the invariant it relies on; `docs_test.clj` fails otherwise. Functions are public: `defn-` only for a one-line local alias (`now`, `set-intent`), never to hide a domain function from the REPL. A new attribute gets a row in `model/attributes` and a spec in the same change. A function that reads the world states which attributes it needs as a selection `fdef` (`spec/requires`, listed in `spec/selected`): schema and selection stay apart. A namespace runs top-down from data to helpers to its entry point; any I/O comes last, after a `;;;; I/O: ... ;;;;` fence. Branch with `cond`, never an `if` nested in an `if` (`style_test.clj` holds it). Walk a collection with `reduce` (with `reduced` to stop early), not `loop`, unless the loop is hot (the packet decoders) or iterates until a condition (the sim's driver). Prefer a new pure function over a flag; a map over a record; data over a protocol; a defmethod over an edit to a case. Never change the meaning of an attribute: add a new name beside it. A packet spec only gains fields, appended: record the new spec in `test/clojurecraft/packet_specs_ever.edn` (`packet-specs-only-grow` holds it).

## Layout

```
src/clojurecraft/bytes.clj    varint/string/uuid/position readers and writers
src/clojurecraft/packet.clj   specs as data + decode/encode + protocol state transitions
src/clojurecraft/conn.clj     socket, framing, zlib, reader/writer threads → core.async chans
src/clojurecraft/chunk.clj    paletted chunk sections as values, block-at, find-blocks
src/clojurecraft/blocks.clj   generated block/item/entity tables, solid?, log?
src/clojurecraft/physics.clj  vanilla land movement over :player/* attributes, look-at
src/clojurecraft/memory.clj   observation facts in DataScript, with time, after chunks unload; Datalog queries
src/clojurecraft/game.clj     the protocol reducer: handshake, keep-alive, teleports, every packet's handler, ticks
src/clojurecraft/inventory.clj the player's items and screen as values: slot maps, window state, what is held
src/clojurecraft/terrain.clj  the blocks around the bot: columns, the block overlay, block-at, solid-fn; feeds memory
src/clojurecraft/path.clj     a route for the feet over the block grid: A* with a node budget, moves and goals as data
src/clojurecraft/dig.clj      a block's break time and drop as a pure function of block, held item and penalties, over the generated tables
src/clojurecraft/intent.clj   open executors (multimethod on :intent/kind): :walk (follows a route) :dig (with the best tool) :collect, and the leaf blocker
src/clojurecraft/stairs.clj   the :stairs-down executor: a staircase of child :dig intents, refusing water, lava and drops
src/clojurecraft/stone.clj    the :mine act: a row's blocks (stone, ore, gravel) walk → dig → collect, or stairs down to its depth
src/clojurecraft/swim.clj     the escape goals' rules and executors: :swim-out, :flee, and :wade (the shore trial)
src/clojurecraft/craft.clj    the :craft executor: settle, lay the grid with clicks, verify, take
src/clojurecraft/smelt.clj    the :smelt executor: settle, lay the fuel and the input, cook, take, close; fuel in burn order
src/clojurecraft/place.clj    the :place and :open-container executors: a spot, use-item-on, the server's answer
src/clojurecraft/scoop.clj    the :scoop act and executor: surface water in sight or remembered, aim, use-item, the water bucket
src/clojurecraft/build.clj    a build plan as data (the portal mold): ops with done-predicates, the :build intent, the site
src/clojurecraft/fortress.clj the Nether leg: the :fortress goal and the :tunnel-to intent, each step a plan of ops, never beside lava
src/clojurecraft/dimension.clj the dimensions as data (dimensions.edn): names, ids, heights; the one the bot is in
src/clojurecraft/window.clj   window views (inventory, table or furnace), the click that predicts nothing, the resync click
src/clojurecraft/recipe.clj   the vanilla crafting table as data: match, clicks, needs
src/clojurecraft/plan.clj     loads goals.edn; registries done-by, act, next-intent; the planner; :plan/*
src/clojurecraft/trap.clj     trapped in water or lava, re-derived every tick with hysteresis; dry land
src/clojurecraft/make.clj     the needs planner: recipe rows, netting needs against inventory, the acts
src/clojurecraft/wood.clj     the gather chain toward a log (walk → dig → collect)
src/clojurecraft/model.clj    the information model: every attribute, its meaning, when it exists (model_test keeps it true)
src/clojurecraft/spec.clj     specs for attributes, events, effects, intents, packets; fdefs on the reducers
src/clojurecraft/record.clj   the recording: a channel tap, reading it back, replay and verify
src/clojurecraft/replay.clj   clojure -M:replay: fold a recording with no server, print RESULT
src/clojurecraft/sim.clj      pure server model for socket-free end-to-end runs; holds a whole world of columns
src/clojurecraft/capture.clj  real worlds for the model: a real server's chunk columns round each landing, one world file a landing
src/clojurecraft/simrace.clj  the sim race: the whole bot in captured worlds against the model, every core, failures by signature
src/clojurecraft/harness.clj  the fixture process: RCON forest landing, prints one {:event/kind :go} for the bot's stdin
src/clojurecraft/claims.clj   landing cells as values: the pure claims reducer (first come first served) and the spiral of cells
src/clojurecraft/race.clj     the race: N bots (main/run-bot!, each its own atom and loop) on one server, one claims channel, the board
src/clojurecraft/fixture.clj  what every fixture asks the server over RCON: wait, load, teleport, judge a landing; the :go event
src/clojurecraft/rcon.clj     RCON client (fixtures, CI judge); `clojure -M:rcon`
src/clojurecraft/gym.clj      the gym: registry, one trial (land, judge, retry a disconnect), report (`clojure -M:gym`)
src/clojurecraft/plans.clj    one plan file across many jobs (`clojure -M:plan`, `ci/plan/`): `:plans/*`, because `:plan/*` belongs to `plan.clj`
src/clojurecraft/landings.clj landing sets: grid, biome snap, surface checks, pregeneration
src/clojurecraft/watch.clj    observers of the atom: --telemetry diffs successive worlds, bounded, off-thread
src/clojurecraft/main.clj     loop, effects, RESULT line, --record, --telemetry, replay
src/clojurecraft/cli.clj      what every entry point shares: --key value options, a timestamped stderr line
dev/clojurecraft/datagen.clj  vanilla --reports and the inner jar's data → resources/clojurecraft/*.edn
dev/clojurecraft/jar_probe.clj hardness and tool materials read from the game's own classes, for datagen
dev/clojurecraft/brain.clj    the brain checker (clojure -M:brain)
```

## Commands

```bash
direnv allow                                  # once per clone: entering the directory then loads the flake's shell (.envrc)
nix develop                                   # Java 25, Clojure, clj-kondo, clojure-lsp, lefthook, zstd (flake.nix,
                                              # pinned by flake.lock); entering it installs the git hooks
nix flake update                              # move every tool to today's nixpkgs; match the :lint and :lsp
                                              # versions in deps.edn, which CI runs from Maven
bb tasks                                      # every command below as a Babashka task (bb.edn); bb ci is what CI's unit job runs
bb nrepl                                      # nREPL with the bot, the tests and repl/user.clj (clojure -M:dev:nrepl); its comment block runs the bot against the model
clojure -M:test                               # every test, namespaces and vars in parallel (eftest); -n ns, -v ns/var, -x ns
bb local-server start|stop                    # vanilla 26.1.2 on game 25571 / RCON 25581 (bb/clojurecraft/server.clj)
clojure -M:harness --rcon-port 25581 --rcon-pass "$(cat data/local-server/rcon.pass)" --name Clj_wood --until wood \
  | clojure -M:run --port 25571 --name Clj_wood --until wood --events stdin   # land in a forest, then run
clojure -M:run --port 25571 --until play --hold-ms 20000     # just connect and stay in-world
clojure -M:run ... --record data/runs/x.edn                   # record every event
bb replay data/runs/x.edn                                     # replay it with no server, at the commit that made it (.zst and .gz too; --here for the working tree's code)
clojure -M:race --config ci/race.edn --port 25571 --rcon-port 25581 --rcon-pass "$(cat data/local-server/rcon.pass)"   # a race on one server (race.yml: ten runners)
clojure -M:capture --port 25571 --rcon-port 25581 --rcon-pass "$(cat data/local-server/rcon.pass)" --landings l.edn --set S --out d   # real worlds for the model
clojure -M:simrace --worlds cache/sim-worlds/R1 --seeds 2       # the bot in captured worlds against the model (simrace.yml: ten runners)
clojure -M:simrace --world cache/sim-worlds/R1/7.edn.gz --seed 3 --seeds 1   # one run again, as an issue reproduces it
clojure -M:simrace --snapshot test/issues/37/R2-16-3.edn.gz   # a failed run's moment played on: does its signature recur?
bb issue-check fetch 37                                        # an issue's snapshots into test/issues/37/, for a fix/37-* PR to commit
bb failures report <dir> <title>                                # failed runs by signature, one issue each, one comment a round (the races' report jobs)
bb win release <dir> <run> <sha>                                # a real race's winners become a release with their recordings (race.yml)
bb page round <dir> real|sim <run> <commit> <at>                # a round's facts from those runs; bb page wall <dir> ranks the failures by stage;
bb page build rounds.edn out/                                   # the results page from the rounds (the report jobs publish it to gh-pages)
clojure -M:run ... --telemetry data/runs/t.edn                # one EDN line per change of the world
clojure -M:fmt fix|check                                      # format (zprint, settings in .zprint.edn); the pre-commit hook does it for staged files
clojure -M:lint --lint src dev test                           # lint (clj-kondo, held at zero findings); the pre-commit hook lints staged files
clojure -M:splint src dev test/clojurecraft                   # idioms (Splint, held at zero; rules off in .splint.edn with reasons); the hook too
clojure -M:lsp clean-ns                                       # sort and prune ns forms (clojure-lsp, settings in .lsp/config.edn); the hook too
clojure -M:lsp diagnostics --raw                              # no unused public vars (clojure-lsp); the hook checks staged files, CI the repo
clojure -M:brain                                              # brain checker (must be zero)
clojure -M:plan plan --plan ci/plan/soak.edn                  # the job matrix for one plan file (also work, report, pull)
clojure -M:rcon --port 25581 --pass "$(cat data/local-server/rcon.pass)" data get entity Clj_wood Inventory
bb datagen                            # regenerate the EDN tables (needs the jar)
```

The bot prints one `RESULT {...}` EDN line on stdout and exits 0 when `:ok true`. CI is the workflows below, and the two that race on every merge to main: `race.yml` (real servers on the race's pregenerated landing sets, ten runners) and `simrace.yml` (the bot in the same worlds, captured once by `simworld.yml`, against the model, ten runners), each ending in a report that files failures as issues, one per signature (`brain/bot/tooling/sim-race.md`), and appends the round to the results page on `gh-pages` (`bb page`, `brain/bot/tooling/race.md`). A `fix/<issue>-*` pull request gets two checks: `issue.yml`, where `issues_test` plays the issue's failing moments (snapshots under `test/issues/<issue>/`) on against the model and holds that its signature does not recur, and `unit.yml`, every test but the forest property plus the static checks (the same job `test.yml` runs on every push), so a fix that breaks an older assertion is seen before `main` is. `test.yml` runs the tests and the brain checker on every push; its forest property is eight sim shards. `gym.yml` runs every goal once on push and N trials of one goal on dispatch, one runner and one pregenerated landing per trial, judged by RESULT plus the server's truth commands and reported with a 95% interval (`clojure -M:gym`, `brain/bot/tooling/gym-on-runners.md`). `world.yml` builds a landing set's world once and caches it. `plan.yml` runs one plan file (`ci/plan/`, `clojure -M:plan`) as many jobs. `timer.yml` dispatches the next plan every twenty minutes. `full.yml` is one fresh world and one bot with nothing given, from spawn, playing every step up to the current stage (`plan/current-until`, `--chain`) for 5.5 hours. It runs on a pull request. A later push to that request cancels its own attempt. Another request keeps its own. Write code, push, and read the runs: the runners do the compute.

## Words

GitHub's words stay GitHub's. Actions is the product, a workflow is one yaml file, a job is one occupancy of a runner, and a runner is the machine. The cap of 20 counts jobs.

- **test** is the suite workflow. **gym** is the real-terrain workflow. One run of one goal on one landing is a **trial** (the matrix job in `gym.yml`).
- **world** builds a landing set once (`world.yml`, `bb prepare-world`). The namespace is `clojurecraft.worlds` (`bb/clojurecraft/worlds.clj`), because `clojurecraft.world` is the test fixture that builds chunk columns.
- **plan** is one file of experiments run together (`ci/plan/<label>.edn`, `plan.yml`, `clojure -M:plan`). The namespace is `clojurecraft.plans` and the keys are `:plans/*`. `clojurecraft.plan` and `:plan/*` are the bot's planner in `plan.clj`; a plan file does not use them.
- **job** is one share of a plan (`--job N`, matrix key `job`, artifact `plan-<label>-job-<n>`).
- **shard** is only a seeded slice of a sim property (`:exp/shards`, and the same word on `test.yml`'s forest matrix).
- **soak** is the repeated plan, `ci/plan/soak.edn`. **timer** (`timer.yml`) dispatches the next file in `ci/plan/queue/`, or the soak when the queue is empty.
- **full** is the one fresh-world attempt (`full.yml`): nothing given, from spawn, every step up to the current stage, for 5.5 hours. It is a pull-request check, one attempt per request, always that request's latest push.

## Server facts

Vanilla **26.1.2 = protocol 775**, offline mode, Java 25, compression threshold 256, seed `typecraft`. Ports in use on this Mac: ruststeve 25567/25568 (RCON 25577/25578), steve 25569/25570 (25579/25580) — never touch those; ours are 25571/25581. The shared game box (`144.24.32.76`) belongs to steve/ruststeve races; do not point this bot at it without being asked. 26.x gamerules are snake_case (`keep_inventory`).

## Rules

- Conventional-commit prefixes. **Never** add AI attribution (`Co-Authored-By`, "Generated with Claude", session links) anywhere; the commit-msg hook (`scripts/check-commit-msg`) refuses a message that has any. Never push or open a PR without explicit approval.
- `resources/clojurecraft/*.edn` are generated; regenerate with `bb datagen`, don't hand-edit. Two are hand-written: `goals.edn` (the goal table) and `problems.edn` (a problem statement per stage, written before the stage's goal; `problems_test` holds it).
