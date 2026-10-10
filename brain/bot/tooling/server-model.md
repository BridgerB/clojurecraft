---
title: Server model
description: The pure vanilla-server model in sim.clj that lets the whole bot run handshake to held log in a test with no Java process.
type: reference
tags: [bot, tooling, sim, tests]
aliases: [sim.clj, fake server, socket-free test, sim/run]
status: verified
lastUpdated: 2026-10-10
verifiedAgainst: cc7365f
sourceRefs:
  - src/clojurecraft/sim.clj#defn init
  - src/clojurecraft/sim.clj#defn run
  - src/clojurecraft/sim.clj#def dig-ms
  - src/clojurecraft/sim.clj#observed live on 26.1.2: the breaker gets the block update, then the ack
  - test/clojurecraft/sim_test.clj#collects-one-log-against-the-model
  - test/clojurecraft/sim_test.clj#the-wood-goal-holds-in-generated-forests
related:
  - "[[bot/tooling/_moc|Tooling]]"
  - "[[early-finish-aborts]]"
  - "[[reducer-and-effects]]"
---

# Server model

`sim.clj` is a reducer too: `(sim/step sim event)` where the event is a client packet or a tick, with packets for the client accumulating in `:sim/out`. It models the login and configuration handshake, one teleport to spawn, one chunk column, keep-alives, block breaking with the vanilla timing per block (logs 3000 ms, leaves 300 ms; an early FINISH is ignored) answered with `block-update` then `block-changed-ack` as observed live, an item drop at a broken log (leaves drop nothing), the server's own block view (`sim/block-at`: the fixture column minus broken positions), pickup after a 500 ms delay when the player is within the inflated pickup box, window 0 and an opened crafting table with vanilla click rules, state ids and the prediction-based answer ([[sim-window-clicks]], [[sim-window-sync]]), and `use-item-on` placing a table or opening one ([[sim-placement]]). Faults are inputs ([[sim-faults]]).

## Key files
- `sim.clj`, `init` - takes `{:column bytes :spawn [x y z] :keep-alive-every ms :drop-clicks #{n} :inventory {slot item}}`.
- `sim.clj`, `run` - drives a bot reducer against the model 50 ms per tick, sends `:go` once loaded, stops on a predicate.
- `sim_test.clj`, `the-wood-goal-holds-in-generated-forests` - test.check builds 2,000 worlds (`docs/hickey.md`: "thousands of generated worlds in the time one real run takes"): 1-3 trees of oak, spruce or birch, 3-6 logs high, anywhere in the column; each maybe on a one-block stone mound and maybe under low leaves over every cell within two steps; the spawn varies. The whole bot runs against the instrumented model in each and must end holding a log with no violation. About 16 ms a world, about 40 s for the test; `FOREST_TRIALS=n` overrides the count. Treating only oak as a log fails it (shrunk to one spruce), and so does removing the blocker's headroom check over a step: the mounds under canopies reproduce the ledge a live run failed on.
- `sim_test.clj` - end-to-end tests: one log held (`:wood`), a low canopy cleared, a table and four sticks (`:kit`), a placed table and a wooden pickaxe (`:pickaxe`), and recovery from a lost click.

## Gotchas
- The model knows window 0 and the crafting table only: no furnaces, no placement of other blocks, no dimensions yet; every roadmap issue extends it first.
- The sim uses `recipe/match` for slot 0, so bot and model agree on recipes by construction; only a live run checks the matcher against vanilla.
- Pickup always gives an `oak_log`, whatever block was broken.

## See also
- [[early-finish-aborts]]
