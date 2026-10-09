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
- **Dispatch is open**: multimethods for packets (`game/on-packet` on `[phase name]`), intents (`intent/run` on `:intent/kind`) and goals (`plan/goal-done?`, `plan/next-intent` on `:goal/id`). A new packet, intent or goal is a `defmethod` in a new namespace; `plan/goals` is a table.
- **Memory is facts with time** (`memory`): sightings keyed by position with `:block/state` and `:block/seen-at`, kept after chunks unload.
- **Every run is a file**: `--record run.edn` writes each event; `clojure -M:replay run.edn` folds the reducer over it with no server. Keep this true (no hidden inputs).
- **Specs** live in `spec.clj` and are instrumented in tests; properties in `props_test.clj`; the whole bot runs against the pure server model in `sim.clj` (`sim_test.clj`) with no Java process.
- I/O lives in exactly four namespaces: `conn` (socket), `rcon`, `harness` (RCON fixture, an observer of the atom), `main`. Everything else is values in, values out.

Prefer a new pure function over a flag; a map over a record; data over a protocol; a defmethod over an edit to a case. Never change the meaning of an attribute: add a new name beside it.

## Layout

```
src/clojurecraft/bytes.clj    varint/string/uuid/position readers and writers
src/clojurecraft/packet.clj   specs as data + decode/encode + protocol state transitions
src/clojurecraft/conn.clj     socket, framing, zlib, reader/writer threads → core.async chans
src/clojurecraft/chunk.clj    paletted chunk sections as values, block-at, find-blocks
src/clojurecraft/blocks.clj   generated block/item/entity tables, solid?, log?
src/clojurecraft/physics.clj  vanilla land movement over :player/* attributes, look-at
src/clojurecraft/memory.clj   sightings: what the bot has seen, with time, after chunks unload
src/clojurecraft/game.clj     the world reducer: handshake, keep-alive, teleports, chunks, inventory, ticks
src/clojurecraft/intent.clj   open executors: :walk :dig :collect (multimethod on :intent/kind)
src/clojurecraft/plan.clj     goal table + planner; plan state under :plan/*
src/clojurecraft/wood.clj     the :wood goal (walk → dig → collect)
src/clojurecraft/spec.clj     specs for attributes, events, effects, intents; fdefs on the reducers
src/clojurecraft/record.clj   event recorder and replay
src/clojurecraft/sim.clj      pure server model for socket-free end-to-end runs
src/clojurecraft/harness.clj  RCON forest landing; watches the atom, sends {:event/kind :go}
src/clojurecraft/rcon.clj     RCON client (fixtures, CI judge); `clojure -M:rcon`
src/clojurecraft/main.clj     loop, effects, RESULT line, --record, replay
dev/clojurecraft/datagen.clj  vanilla --reports → resources/clojurecraft/*.edn
```

## Commands

```bash
nix shell nixpkgs#jdk25 nixpkgs#clojure      # this Mac has neither on PATH
clojure -M:test                               # unit tests, no server needed
./local-server.sh start|stop                  # vanilla 26.1.2 on game 25571 / RCON 25581
clojure -M:run --port 25571 --rcon-port 25581 --rcon-pass "$(cat data/local-server/rcon.pass)"
clojure -M:run --port 25571 --until play --hold-ms 20000     # just connect and stay in-world
clojure -M:run ... --record data/runs/x.edn                   # record every event
clojure -M:replay data/runs/x.edn                             # replay it with no server
clojure -M:fmt fix src test dev                               # format (cljfmt)
clojure -M:brain                                              # brain checker (must be zero)
clojure -M:rcon --port 25581 --pass "$(cat data/local-server/rcon.pass)" data get entity Clj_wood Inventory
scripts/datagen.sh                            # regenerate the EDN tables (needs the jar)
```

The bot prints one `RESULT {...}` EDN line on stdout and exits 0 when `:ok true`. CI (`.github/workflows/wood.yml`) boots a vanilla server on one runner, runs the bot, and judges by that line plus an independent RCON inventory read.

## Server facts

Vanilla **26.1.2 = protocol 775**, offline mode, Java 25, compression threshold 256, seed `typecraft`. Ports in use on this Mac: ruststeve 25567/25568 (RCON 25577/25578), steve 25569/25570 (25579/25580) — never touch those; ours are 25571/25581. The shared game box (`144.24.32.76`) belongs to steve/ruststeve races; do not point this bot at it without being asked. 26.x gamerules are snake_case (`keep_inventory`).

## Rules

- Conventional-commit prefixes. **Never** add AI attribution (`Co-Authored-By`, "Generated with Claude", session links) anywhere. Never push or open a PR without explicit approval.
- `resources/clojurecraft/*.edn` are generated; regenerate with `scripts/datagen.sh`, don't hand-edit.
