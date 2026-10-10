# clojurecraft

A Minecraft Java Edition bot written from scratch in Clojure, built to beat the Ender Dragon with no human input. The world is one immutable value, the protocol is a pure reducer over events, packets and their specs are plain data, memory is an append-only set of facts queried with Datalog, and I/O is fenced off at the edges (the socket, RCON, the loop, the recording and telemetry files). Every run can be recorded and replayed with no server. The test fixture is a separate process that speaks to the bot only through a pipe of EDN events.

Current milestone: from a natural forest on a vanilla 26.1.2 server, gather logs, craft planks, sticks and a crafting table, place it, and craft a wooden pickaxe (`--until wood`, `table` or `pickaxe`). The goals ahead, each with a written problem statement, are in `resources/clojurecraft/problems.edn`.

```bash
clojure -M:test                       # unit tests
./local-server.sh start               # local vanilla server (25571 / RCON 25581)
clojure -M:harness --rcon-port 25581 --rcon-pass "$(cat data/local-server/rcon.pass)" --name Clj_wood --until wood \
  | clojure -M:run --port 25571 --name Clj_wood --until wood --events stdin
```

The bot prints `RESULT {:ok true ...}` and exits 0 when the goal is met. `.github/workflows/gym.yml` runs each goal on GitHub runners, one server and one pregenerated landing per run, and judges it by that line plus the server's own truth commands. The design brief is `docs/hickey.md`.
