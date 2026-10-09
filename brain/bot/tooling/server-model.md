---
title: Server model
description: The pure vanilla-server model in sim.clj that lets the whole bot run handshake to held log in a test with no Java process.
type: reference
tags: [bot, tooling, sim, tests]
aliases: [sim.clj, fake server, socket-free test, sim/run]
status: verified
lastUpdated: 2026-10-09
verifiedAgainst: 60b624e
sourceRefs:
  - src/clojurecraft/sim.clj#defn init
  - src/clojurecraft/sim.clj#defn run
  - src/clojurecraft/sim.clj#def dig-ms
  - test/clojurecraft/sim_test.clj#collects-one-log-against-the-model
related:
  - "[[bot/tooling/_moc|Tooling]]"
  - "[[early-finish-aborts]]"
  - "[[reducer-and-effects]]"
---

# Server model

`sim.clj` is a reducer too: `(sim/step sim event)` where the event is a client packet or a tick, with packets for the client accumulating in `:sim/out`. It models the login and configuration handshake, one teleport to spawn, one chunk column, keep-alives, block breaking with the vanilla timing (an early FINISH is ignored), an item drop at the block, and pickup after a 500 ms delay when the player is within the inflated pickup box.

## Key files
- `sim.clj`, `init` - takes `{:column bytes :spawn [x y z] :keep-alive-every ms}`.
- `sim.clj`, `run` - drives a bot reducer against the model 50 ms per tick, sends `:go` once loaded, stops on a predicate.
- `sim_test.clj` - the end-to-end test: plan done, one log held, keep-alives answered, target block air.

## Gotchas
- The model knows nothing about crafting, furnaces or dimensions yet; every roadmap issue extends it first.

## See also
- [[early-finish-aborts]]
