---
title: Server model
description: The pure vanilla-server model in sim.clj that lets the whole bot run handshake to held log in a test with no Java process.
type: reference
tags: [bot, tooling, sim, tests]
aliases: [sim.clj, fake server, socket-free test, sim/run]
status: verified
lastUpdated: 2026-10-11
verifiedAgainst: HEAD
sourceRefs:
  - src/clojurecraft/sim.clj#defn init
  - src/clojurecraft/sim.clj#defn run
  - src/clojurecraft/sim.clj#defn connect!
  - src/clojurecraft/sim.clj#defn loot
  - src/clojurecraft/sim.clj#defn roll
  - "test/clojurecraft/sim_test.clj#main's-own-loop-against-the-model-through-channels"
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

`sim.clj` is a reducer too: `(sim/step sim event)` where the event is a client packet or a tick, with packets for the client accumulating in `:sim/out`. It models the login and configuration handshake, one teleport to spawn, one chunk column, keep-alives, block breaking with the vanilla timing per block (logs 3000 ms, leaves 300 ms; an early FINISH is ignored) answered with `block-update` then `block-changed-ack` as observed live, an item drop by the block's loot table (`loot`: stone by hand and leaves drop nothing, gravel gives flint when its roll comes under 0.1; the rolls come from `:sim/rng`, a pure generator seeded by the `:seed` input, so a run's luck replays), the server's own block view (`sim/block-at`: placed blocks, then the fixture column minus broken positions; a placed block that is broken is gone), pickup after a 500 ms delay when the player is within the inflated pickup box, window 0, an opened crafting table and an opened furnace with vanilla click rules, state ids and the prediction-based answer ([[sim-window-clicks]], [[sim-window-sync]]), furnaces that cook by the game's tables every tick ([[sim-furnace]]), and `use-item-on` placing a held block or opening a container ([[sim-placement]]), breath and drowning from the reported position with a respawn on `client-command` ([[death-and-respawn]]). Faults are inputs ([[sim-faults]]).

## Two ways to drive it
- `sim/run` folds the bot's reducer and the model together on one thread, 50 ms per tick of model time: deterministic and fast, so the generated-worlds property can run thousands of worlds.
- `sim/connect!` puts the model on a thread of its own behind a bounded channel pair shaped like `conn/open!`'s (`:in`, `:out`, `:close!`), ticking every 50 ms of wall time. `main`'s own loop (`run-loop!`, `apply-event!`, `go-when-loaded!`) then runs against it unchanged: the essay's "bot and server model composed through a channel pair", a complete run of the real process with no Java server. `main's-own-loop-against-the-model-through-channels` gets a log that way in about 6 s, with the model's keep-alives answered; a model that never forwards its packets fails it.

## Key files
- `sim.clj`, `init` - takes `{:column bytes :spawn [x y z] :keep-alive-every ms :drop-clicks #{n} :inventory {slot item} :respawn [x y z]}`.
- `sim.clj`, `run` - drives a bot reducer against the model 50 ms per tick, sends `:go` once loaded, stops on a predicate.
- `sim_test.clj`, `the-wood-goal-holds-in-generated-forests` - test.check builds 2,000 worlds (`docs/hickey.md`: "thousands of generated worlds in the time one real run takes"): 1-3 trees of oak, spruce or birch, 3-6 logs high, anywhere in the column; each maybe on a one-block stone mound and maybe under low leaves over every cell within two steps; the spawn varies. The whole bot runs against the instrumented model in each and must end holding a log with no violation. About 16 ms a world; `FOREST_TRIALS=n` overrides the count and `FOREST_SEED=s` the seed, which is how `test.yml` runs it as eight shards of 250 ([[test-fixtures]]). Treating only oak as a log fails it (shrunk to one spruce), and so does removing the blocker's headroom check over a step: the mounds under canopies reproduce the ledge a live run failed on.
- `sim_test.clj` - end-to-end tests: one log held (`:wood`), a low canopy cleared, a table and four sticks (`:kit`), a placed table and a wooden pickaxe (`:pickaxe`), and recovery from a lost click.

## Gotchas
- The model knows window 0, the crafting table and the furnace: no other containers, no buckets, no dimensions yet; every roadmap issue extends it first.
- The sim uses `recipe/match` for slot 0, so bot and model agree on recipes by construction; only a live run checks the matcher against vanilla.
- A pickup gives the item the broken block's loot table dropped, which the model stores with the item entity.

## See also
- [[early-finish-aborts]]
