# clojurecraft

A Minecraft Java Edition bot written from scratch in Clojure, built to beat the Ender Dragon with no human input. The world is one immutable value, the protocol is a pure reducer over events, packets and their specs are plain data, and I/O is confined to the socket, RCON and the main loop.

Current milestone: connect to a vanilla 26.1.2 server, find a natural tree, walk to it, break one log and pick it up.

```bash
clojure -M:test                       # unit tests
./local-server.sh start               # local vanilla server (25571 / RCON 25581)
clojure -M:run --port 25571 --rcon-port 25581 --rcon-pass "$(cat data/local-server/rcon.pass)"
```

The bot prints `RESULT {:ok true ...}` and exits 0 when it holds a log. `.github/workflows/wood.yml` does the same on a GitHub runner with an independent RCON inventory check.
