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
- **Specs** live in `spec.clj` (every attribute in `model`, every packet derived from `packet/specs`) and are instrumented in tests; properties in `props_test.clj` and `make_test.clj`; the whole bot runs against the pure server model in `sim.clj` (`sim_test.clj`), including in 100 worlds test.check generates, with no Java process.
- **The hammock**: every stage of the run has a problem statement in `resources/clojurecraft/problems.edn` (needs, risks, done in world terms, the last recorded failure), written before its goal; when a run fails in a new way, its row is updated.
- I/O lives only below a `;;;; I/O ;;;;` fence, in `conn` (the socket and its threads), `rcon`, `main` (the loop, the clock, stderr), `record` (recording files), `watch` (telemetry), `replay`, and `harness`, the RCON fixture, which is its own process (`clojure -M:harness`) and reaches the bot only as EDN events on its stdin (`--events stdin`). Everything else is values in, values out.

Every public function has a docstring stating what it returns and the invariant it relies on; `docs_test.clj` fails otherwise. Functions are public: `defn-` only for a one-line local alias (`now`, `set-intent`), never to hide a domain function from the REPL. A new attribute gets a row in `model/attributes` and a spec in the same change. A namespace runs top-down from data to helpers to its entry point; any I/O comes last, after a `;;;; I/O: ... ;;;;` fence. Walk a collection with `reduce` (with `reduced` to stop early), not `loop`, unless the loop is hot (the packet decoders) or iterates until a condition (the sim's driver). Prefer a new pure function over a flag; a map over a record; data over a protocol; a defmethod over an edit to a case. Never change the meaning of an attribute: add a new name beside it.

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
src/clojurecraft/intent.clj   open executors (multimethod on :intent/kind): :walk :dig :collect, and the leaf blocker
src/clojurecraft/craft.clj    the :craft executor: settle, lay the grid with clicks, verify, take
src/clojurecraft/place.clj    the :place and :open-container executors: a spot, use-item-on, the server's answer
src/clojurecraft/window.clj   window views (inventory or table) and the click that predicts nothing
src/clojurecraft/recipe.clj   the vanilla crafting table as data: match, clicks, needs
src/clojurecraft/plan.clj     loads goals.edn; registries done-by, act, next-intent; the planner; :plan/*
src/clojurecraft/make.clj     the needs planner: recipe rows, netting needs against inventory, the acts
src/clojurecraft/wood.clj     the gather chain toward a log (walk → dig → collect)
src/clojurecraft/model.clj    the information model: every attribute, its meaning, when it exists (model_test keeps it true)
src/clojurecraft/spec.clj     specs for attributes, events, effects, intents, packets; fdefs on the reducers
src/clojurecraft/record.clj   the recording: a channel tap, reading it back, replay and verify
src/clojurecraft/replay.clj   clojure -M:replay: fold a recording with no server, print RESULT
src/clojurecraft/sim.clj      pure server model for socket-free end-to-end runs
src/clojurecraft/harness.clj  the fixture process: RCON forest landing, prints one {:event/kind :go} for the bot's stdin
src/clojurecraft/rcon.clj     RCON client (fixtures, CI judge); `clojure -M:rcon`
src/clojurecraft/watch.clj    observers of the atom: --telemetry diffs successive worlds, bounded, off-thread
src/clojurecraft/main.clj     loop, effects, RESULT line, --record, --telemetry, replay
dev/clojurecraft/datagen.clj  vanilla --reports → resources/clojurecraft/*.edn
dev/clojurecraft/brain.clj    the brain checker (clojure -M:brain)
```

## Commands

```bash
nix shell nixpkgs#jdk25 nixpkgs#clojure      # this Mac has neither on PATH
clojure -M:test                               # unit tests, no server needed
./local-server.sh start|stop                  # vanilla 26.1.2 on game 25571 / RCON 25581
clojure -M:harness --rcon-port 25581 --rcon-pass "$(cat data/local-server/rcon.pass)" --name Clj_wood --until wood \
  | clojure -M:run --port 25571 --name Clj_wood --until wood --events stdin   # land in a forest, then run
clojure -M:run --port 25571 --until play --hold-ms 20000     # just connect and stay in-world
clojure -M:run ... --record data/runs/x.edn                   # record every event
clojure -M:replay data/runs/x.edn                             # replay it with no server
clojure -M:run ... --telemetry data/runs/t.edn                # one EDN line per change of the world
clojure -M:fmt fix src test dev                               # format (cljfmt)
clojure -M:brain                                              # brain checker (must be zero)
clojure -M:rcon --port 25581 --pass "$(cat data/local-server/rcon.pass)" data get entity Clj_wood Inventory
scripts/datagen.sh                            # regenerate the EDN tables (needs the jar)
```

The bot prints one `RESULT {...}` EDN line on stdout and exits 0 when `:ok true`. CI (`.github/workflows/wood.yml`) boots a vanilla server on one runner, pipes the harness into the bot, and judges by that line plus an independent RCON inventory read.

## Server facts

Vanilla **26.1.2 = protocol 775**, offline mode, Java 25, compression threshold 256, seed `typecraft`. Ports in use on this Mac: ruststeve 25567/25568 (RCON 25577/25578), steve 25569/25570 (25579/25580) — never touch those; ours are 25571/25581. The shared game box (`144.24.32.76`) belongs to steve/ruststeve races; do not point this bot at it without being asked. 26.x gamerules are snake_case (`keep_inventory`).

## Rules

- Conventional-commit prefixes. **Never** add AI attribution (`Co-Authored-By`, "Generated with Claude", session links) anywhere. Never push or open a PR without explicit approval.
- `resources/clojurecraft/*.edn` are generated; regenerate with `scripts/datagen.sh`, don't hand-edit. Two are hand-written: `goals.edn` (the goal table) and `problems.edn` (a problem statement per stage, written before the stage's goal; `problems_test` holds it).
