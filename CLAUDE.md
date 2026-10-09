# CLAUDE.md

clojurecraft: a from-scratch Minecraft Java Edition bot in Clojure whose purpose is to beat the Ender Dragon with no human input. It is the third bot in the `mc` monorepo next to steve (TypeScript) and ruststeve (Rust) and is deliberately built the opposite way: no mutable Bot object, no framework.

## Style (the point of the project)

- The world is **one value in one atom**, written only by the loop in `main`.
- The protocol is a **pure reducer**: `(step state event) → {:state :effects}` in `game`; the goal (`wood`) is another reducer with the same signature, composed after it. Events are `[:start]`, `[:packet pkt]`, `[:tick now-ms]`, `[:go]`, `[:closed reason]`. Effects are data: `[:send pkt]`, `[:log s]`.
- Packets are flat maps with a `:name`; packet **specs are data** (`packet/specs`), ids come from the vanilla reports (`resources/clojurecraft/packets.edn`); one interpreter reads and writes them. Unknown ids decode to `{:name :unknown ...}` and are counted, never thrown.
- Time enters only through `[:tick now]`; deadlines are absolute ms in state. Tests feed canned ticks.
- I/O lives in exactly three namespaces: `conn` (socket), `rcon`, `main`. Everything else is bytes-in/values-out.
- Physics is a pure function `(physics/step solid? player controls)`.

Keep it that way. Prefer a new pure function over a flag; prefer a map over a record; prefer data over a protocol.

## Layout

```
src/clojurecraft/bytes.clj    varint/string/uuid/position readers and writers
src/clojurecraft/packet.clj   specs as data + decode/encode + protocol state transitions
src/clojurecraft/conn.clj     socket, framing, zlib, reader/writer threads → core.async chans
src/clojurecraft/chunk.clj    paletted chunk sections as values, block-at, find-blocks
src/clojurecraft/blocks.clj   generated block/item/entity tables, solid?, log?
src/clojurecraft/physics.clj  vanilla land movement, AABB collision, look-at
src/clojurecraft/game.clj     the reducer: handshake, keep-alive, teleports, chunks, inventory, ticks
src/clojurecraft/wood.clj     the goal policy: find → walk → settle → dig → collect → done
src/clojurecraft/rcon.clj     RCON client (fixtures, CI judge); `clojure -M:rcon`
src/clojurecraft/main.clj     loop, effects, RCON forest landing, RESULT line
dev/clojurecraft/datagen.clj  vanilla --reports → resources/clojurecraft/*.edn
```

## Commands

```bash
nix shell nixpkgs#jdk25 nixpkgs#clojure      # this Mac has neither on PATH
clojure -M:test                               # unit tests, no server needed
./local-server.sh start|stop                  # vanilla 26.1.2 on game 25571 / RCON 25581
clojure -M:run --port 25571 --rcon-port 25581 --rcon-pass "$(cat data/local-server/rcon.pass)"
clojure -M:run --port 25571 --until play --hold-ms 20000     # just connect and stay in-world
clojure -M:rcon --port 25581 --pass "$(cat data/local-server/rcon.pass)" data get entity Clj_wood Inventory
scripts/datagen.sh                            # regenerate the EDN tables (needs the jar)
```

The bot prints one `RESULT {...}` EDN line on stdout and exits 0 when `:ok true`. CI (`.github/workflows/wood.yml`) boots a vanilla server on one runner, runs the bot, and judges by that line plus an independent RCON inventory read.

## Server facts

Vanilla **26.1.2 = protocol 775**, offline mode, Java 25, compression threshold 256, seed `typecraft`. Ports in use on this Mac: ruststeve 25567/25568 (RCON 25577/25578), steve 25569/25570 (25579/25580) — never touch those; ours are 25571/25581. The shared game box (`144.24.32.76`) belongs to steve/ruststeve races; do not point this bot at it without being asked. 26.x gamerules are snake_case (`keep_inventory`).

## Rules

- Conventional-commit prefixes. **Never** add AI attribution (`Co-Authored-By`, "Generated with Claude", session links) anywhere. Never push or open a PR without explicit approval.
- `resources/clojurecraft/*.edn` are generated; regenerate with `scripts/datagen.sh`, don't hand-edit.
