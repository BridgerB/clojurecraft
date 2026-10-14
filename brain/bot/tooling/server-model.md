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
  - "src/clojurecraft/sim.clj#Returns [world sim] (run+ for the moment before the stop too)"
  - src/clojurecraft/sim.clj#defn connect!
  - src/clojurecraft/sim.clj#defn loot
  - src/clojurecraft/sim.clj#defn poured
  - src/clojurecraft/sim.clj#defn quench
  - src/clojurecraft/sim.clj#defn light
  - src/clojurecraft/sim.clj#defn through-portal
  - test/clojurecraft/sim_test.clj#standing-in-the-portal-four-seconds-goes-to-the-nether
  - test/clojurecraft/sim_test.clj#water-poured-into-the-bowl-turns-the-lava-under-it-to-obsidian
  - src/clojurecraft/sim.clj#defn roll
  - src/clojurecraft/sim.clj#defn tick-falls
  - src/clojurecraft/sim.clj#defn fall-tick
  - test/clojurecraft/sim_test.clj#gravel-over-a-broken-block-falls-into-it
  - "test/clojurecraft/sim_test.clj#main's-own-loop-against-the-model-through-channels"
  - src/clojurecraft/sim.clj#def dig-ms
  - src/clojurecraft/sim.clj#observed live on 26.1.2: the breaker gets the block update, then the ack
  - test/clojurecraft/sim_test.clj#collects-one-log-against-the-model
  - test/clojurecraft/sim_test.clj#the-wood-goal-holds-in-generated-forests
  - test/clojurecraft/sim_test.clj#the-iron-kit-from-a-stone-pickaxe-and-its-recording-replays
related:
  - "[[bot/tooling/_moc|Tooling]]"
  - "[[early-finish-aborts]]"
  - "[[reducer-and-effects]]"
---

# Server model

`sim.clj` is a reducer too: `(sim/step sim event)` where the event is a client packet or a tick, with packets for the client accumulating in `:sim/out`. It models the login and configuration handshake, one teleport to spawn, one chunk column, keep-alives, block breaking with the vanilla timing per block (logs 3000 ms, leaves 300 ms; an early FINISH is ignored) answered with `block-update` then `block-changed-ack` as observed live, an item drop by the block's loot table (`loot`: stone by hand and leaves drop nothing, gravel gives flint when its roll comes under 0.1; the rolls come from `:sim/rng`, a pure generator seeded by the `:seed` input, so a run's luck replays), the server's own block view (`sim/block-at`: placed blocks, then the fixture column minus broken positions; a placed block that is broken is gone), pickup after a 500 ms delay when the player is within the inflated pickup box, window 0, an opened crafting table and an opened furnace with vanilla click rules, state ids and the prediction-based answer ([[sim-window-clicks]], [[sim-window-sync]]), furnaces that cook by the game's tables every tick ([[sim-furnace]]), `use-item-on` placing a held block or opening a container ([[sim-placement]]), and `use-item` with a bucket ([[scoop-intent]], #8): an empty one fills at the first water or lava source along its look (`scooped`), a filled one puts its fluid in the cell before the first solid block, through any fluid (`poured`), and water meeting lava above or beside it turns a lava source to obsidian and flowing lava to cobblestone (`quench`); fluids do not flow; flint and steel on a block makes fire in the cell its face looks into, or, inside a complete frame (ten obsidian round a two-by-three interior along x or z, `frame-around`), six `nether_portal` (`light`); feet in a portal `portal-ms` (4 s, 80 ticks) on end change the dimension: the Nether's respawn, a teleport where the player stands and the one column again (`through-portal`), gravel and sand falling (`tick-falls`, below), breath and drowning from the reported position with a respawn on `client-command` ([[death-and-respawn]]). Faults are inputs ([[sim-faults]]).

## Two ways to drive it
- `sim/run` folds the bot's reducer and the model together on one thread, 50 ms per tick of model time: deterministic and fast, so the generated-worlds property can run thousands of worlds.
- `sim/connect!` puts the model on a thread of its own behind a bounded channel pair shaped like `conn/open!`'s (`:in`, `:out`, `:close!`), ticking every 50 ms of wall time. `main`'s own loop (`run-loop!`, `apply-event!`, `go-when-loaded!`) then runs against it unchanged: the essay's "bot and server model composed through a channel pair", a complete run of the real process with no Java server. `main's-own-loop-against-the-model-through-channels` gets a log that way in about 6 s, with the model's keep-alives answered; a model that never forwards its packets fails it.

## Falling blocks
A block of one of `blocks/falling-types` looks under itself `fall-delay-ms` (two game ticks) after the block under it changed (`disturb`, called by a break, a placement, a scoop and a fall), as the game's `FallingBlock` schedules its tick on the neighbour update. If the cell under it is then free (air, a liquid, anything passable: `FallingBlock.isFree`), its cell becomes air for the client and it is in the air in `:sim/falling`. Each game tick `fall-tick` moves it as `FallingBlockEntity.tick` does (it gains 0.04 blocks a tick of speed, moves, keeps 98% of it) and lands it on whatever is under it at that moment, so a second gravel lands on the first rather than in the cell the first was still falling into: one block down takes seven ticks. Read in the 26.1.2 jar with `javap`. Not modelled: a block landing in a cell no longer free (the game drops it as an item), and harm to a player under it.

## Key files
- `sim.clj`, `init` - takes `{:column bytes :spawn [x y z] :keep-alive-every ms :drop-clicks #{n} :inventory {slot item} :respawn [x y z]}`; an `:inventory` slot is a named place (`[:hotbar 0]`, `[:main 3]`, read by `inventory/slot`) or a window-0 slot number, so a test says where an item is rather than which wire slot that is.
- `sim.clj`, `run` - drives a bot reducer against the model 50 ms per tick, sends `:go` once loaded, stops on a predicate.
- `sim_test.clj`, `the-wood-goal-holds-in-generated-forests` - test.check builds 2,000 worlds (`docs/hickey.md`: "thousands of generated worlds in the time one real run takes"): 1-3 trees of oak, spruce or birch, 3-6 logs high, anywhere in the column; each maybe on a one-block stone mound and maybe under low leaves over every cell within two steps; the spawn varies. The whole bot runs against the instrumented model in each and must end holding a log with no violation. About 16 ms a world; `FOREST_TRIALS=n` overrides the count and `FOREST_SEED=s` the seed, which is how `test.yml` runs it as eight shards of 250 ([[test-fixtures]]). Treating only oak as a log fails it (shrunk to one spruce), and so does removing the blocker's headroom check over a step: the mounds under canopies reproduce the ledge a live run failed on.
- `sim_test.clj` - end-to-end tests: one log held (`:wood`), a low canopy cleared, a table and four sticks (`:kit`), a placed table and a wooden pickaxe (`:pickaxe`), recovery from a lost click, and the iron kit (`the-iron-kit-from-a-stone-pickaxe-and-its-recording-replays`): from a stone pickaxe, a table, eight cobblestone and four logs on one column with coal, iron, gravel and a pond, to flint and steel and a filled water bucket in 92 simulated seconds, in the kit's order (an ore and a coal, the furnace, one ingot, gravel dug again four times to flint with seed 4, the flint and steel, the pickaxe put back in hand, three more ores, three ingots, the bucket, filled from the bank). The run is recorded through `record/tap!` (each event, then only the effects it added: `sim/run` clears effects once a tick) and `record/verify` replays it with every sent packet the same.

## Gotchas
- The model knows window 0, the crafting table and the furnace, fills an empty bucket with water, and drops gravel and sand: no other containers, no pouring, no lava, no dimensions yet; every roadmap issue extends it first.
- The sim uses `recipe/match` for slot 0, so bot and model agree on recipes by construction; only a live run checks the matcher against vanilla.
- A pickup gives the item the broken block's loot table dropped, which the model stores with the item entity.

## See also
- [[early-finish-aborts]]
