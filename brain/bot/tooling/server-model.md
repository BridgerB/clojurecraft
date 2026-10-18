---
title: Server model
description: The pure vanilla-server model in sim.clj that lets the whole bot run handshake to held log in a test with no Java process.
type: reference
tags: [bot, tooling, sim, tests]
aliases: [sim.clj, fake server, socket-free test, sim/run]
status: verified
lastUpdated: 2026-10-18
verifiedAgainst: HEAD
sourceRefs:
  - src/clojurecraft/sim.clj#defn init
  - src/clojurecraft/sim.clj#defn joined
  - "src/clojurecraft/sim.clj#Returns [world sim] (run+ for the moment before the stop too)"
  - src/clojurecraft/sim.clj#defn connect!
  - src/clojurecraft/sim.clj#defn loot
  - src/clojurecraft/sim.clj#defn poured
  - src/clojurecraft/sim.clj#defn quench
  - src/clojurecraft/sim.clj#defn wake-fluids
  - src/clojurecraft/sim.clj#defn tick-fluids
  - src/clojurecraft/sim.clj#defn fluid-tick
  - src/clojurecraft/fluid.clj#defn state-of
  - test/clojurecraft/fluid_test.clj#aquatic-sources-fill-an-opened-shaft-without-erasing-the-plant
  - test/clojurecraft/fluid_test.clj#saved-models-wake-previously-unrecognized-sources-once
  - test/clojurecraft/sim_test.clj#a-rise-enters-the-water-above-an-opened-seagrass-shaft
  - src/clojurecraft/fluid.clj#defn new-liquid
  - src/clojurecraft/fluid.clj#defn spread
  - src/clojurecraft/fluid.clj#defn mix
  - test/clojurecraft/fluid_test.clj#water-spreads-seven-blocks-on-a-floor
  - test/clojurecraft/fluid_test.clj#water-directly-above-a-lava-source-makes-obsidian
  - src/clojurecraft/sim.clj#defn light
  - src/clojurecraft/sim.clj#defn through-portal
  - src/clojurecraft/sim.clj#defn stream-from
  - src/clojurecraft/sim.clj#defn exit-point
  - src/clojurecraft/sim.clj#defn exit-spot
  - src/clojurecraft/sim.clj#defn portal-space?
  - test/clojurecraft/sim_test.clj#a-nether-arrival-needs-room-for-the-portal-not-an-isolated-foothold
  - src/clojurecraft/sim.clj#defn platform
  - "src/clojurecraft/sim.clj#With no :at, the player stands where"
  - test/clojurecraft/sim_test.clj#the-arrival-is-the-standing-cell-nearest-the-exit-point
  - test/clojurecraft/sim_test.clj#standing-in-the-portal-four-seconds-goes-to-the-nether
  - test/clojurecraft/sim_test.clj#streamed-chunks-include-the-blocks-the-server-now-holds
  - test/clojurecraft/sim_test.clj#a-portal-transition-does-not-put-the-client-back-in-undug-rock
  - test/clojurecraft/sim_test.clj#water-poured-into-the-bowl-turns-the-lava-under-it-to-obsidian
  - src/clojurecraft/sim.clj#defn roll
  - src/clojurecraft/sim.clj#defn tick-falls
  - src/clojurecraft/sim.clj#defn fall-tick
  - test/clojurecraft/sim_test.clj#gravel-over-a-broken-block-falls-into-it
  - src/clojurecraft/sim.clj#defn melts?
  - "test/clojurecraft/sim_test.clj#main's-own-loop-against-the-model-through-channels"
  - src/clojurecraft/sim.clj#def dig-ms
  - src/clojurecraft/sim.clj#defn finish-dig
  - test/clojurecraft/sim_test.clj#collects-one-log-against-the-model
  - test/clojurecraft/sim_test.clj#the-wood-goal-holds-in-generated-forests
  - test/clojurecraft/sim_test.clj#the-iron-kit-from-a-stone-pickaxe-and-its-recording-replays
related:
  - "[[bot/tooling/_moc|Tooling]]"
  - "[[early-finish-aborts]]"
  - "[[reducer-and-effects]]"
  - "[[sim-hunger]]"
  - "[[sim-mobs]]"
  - "[[sim-end]]"
  - "[[real-snapshots]]"
---

# Server model

`sim.clj` is a reducer too: `(sim/step sim event)` where the event is a client packet or a tick, with packets for the client accumulating in `:sim/out`. It models the login and configuration handshake, one teleport to spawn, one chunk column, keep-alives, block breaking with the vanilla timing per block (logs 3000 ms, leaves 300 ms; an early FINISH is ignored) answered with `block-update` then `block-changed-ack` as observed live, an item drop by the block's loot table (`loot`: stone by hand and leaves drop nothing, gravel gives flint when its roll comes under 0.1; the rolls come from `:sim/rng`, a pure generator seeded by the `:seed` input, so a run's luck replays), the server's own block view (`sim/block-at`: placed blocks, then the fixture column minus broken positions; a placed block that is broken is gone), pickup after a 500 ms delay when the player is within the inflated pickup box, window 0, an opened crafting table and an opened furnace with vanilla click rules, state ids and the prediction-based answer ([[sim-window-clicks]], [[sim-window-sync]]), furnaces that cook by the game's tables every tick ([[sim-furnace]]), `use-item-on` placing a held block or opening a container ([[sim-placement]]), and `use-item` with a bucket ([[scoop-intent]], #8): an empty one fills at the first water or lava source along its look (`scooped`), a filled one puts its fluid in the cell before the first solid block, through any fluid (`poured`), and water meeting lava above or beside it turns a lava source to obsidian and flowing lava to cobblestone (`quench`); fluids do not flow; flint and steel on a block makes fire in the cell its face looks into, or, inside a complete frame (ten obsidian round a two-by-three interior along x or z, `frame-around`), six `nether_portal` (`light`); feet in a portal `portal-ms` (4 s, 80 ticks) on end change the dimension: the Nether's respawn, a teleport where the player stands and the one column again (`through-portal`), or a whole Nether's columns with the player where the server would link the portal (below), gravel and sand falling (`tick-falls`, below), breath and drowning from the reported position with a respawn on `client-command` ([[death-and-respawn]]), and, given a `:difficulty`, hunger and eating ([[sim-hunger]]) and mobs that spawn, fight and die ([[sim-mobs]]); eyes of ender, the end portal and the End ([[sim-end]]). Faults are inputs ([[sim-faults]]).

## Two ways to drive it
- `sim/run` folds the bot's reducer and the model together on one thread, 50 ms per tick of model time: deterministic and fast, so the generated-worlds property can run thousands of worlds.
- `sim/connect!` puts the model on a thread of its own behind a bounded channel pair shaped like `conn/open!`'s (`:in`, `:out`, `:close!`), ticking every 50 ms of wall time. `main`'s own loop (`run-loop!`, `apply-event!`, `go-when-loaded!`) then runs against it unchanged: the essay's "bot and server model composed through a channel pair", a complete run of the real process with no Java server. `main's-own-loop-against-the-model-through-channels` gets a log that way in about 6 s, with the model's keep-alives answered; a model that never forwards its packets fails it.

## Arriving in the Nether
`arrive` makes a `:sim/nether` the world: one `:column` and its `:at` (the fixtures'), or `:columns` (a real race's recorded Nether, [[real-snapshots]], or a race set's captured one, [[sim-race]]), decoded on arrival unless they come `:decoded`, streamed round the player when they come with a `:view-distance`. With no `:at` the player arrives where the server would place a new linked portal (`arrival`, a pure function of the decoded columns and the portal's position, issue #353): `exit-point` scales the position as `NetherPortalBlock.getExitPortal` does (x and z over 8, floored, y kept), and `exit-spot` takes, of the cells within `exit-search` (16) across with `portal-space?` for a four-by-five frame in either horizontal axis, entirely under the logical height (128: the bedrock roof), the nearest to that point in three dimensions, ties by position, as `PortalForcer.createPortal` searches; with none, `platform` is its forced one, an obsidian floor two by three with three cells of air over it at the point's y held between 70 and 118. The natural site's four floor cells must be solid, with four air cells over each; no frame or portal is built round the player. A portal deep in the overworld comes out in a cave large enough for its frame (`the-arrival-is-the-standing-cell-nearest-the-exit-point`).

Issue #286's R4/12 and R4/4 snapshots entered the Nether on one gravel block at [352 33 226], suspended over lava. The old single-cell search chose it because it held the player, though it could not hold a portal. The fortress tunnel correctly refused every step from there. Checking the frame footprint before arrival selects actual ground instead; tunnel drop and lava rules remain unchanged.

## Streamed chunks
`stream-from` sends the captured columns first, then the model's changed blocks in those fresh columns, before the batch finishes. Broken cells are air unless a later placement occupies them (`block-at`); changes outside the fresh view are not sent. The column bytes and decoded values stay shared, never rewritten. Sim race 38999010135 (issue #328, R1/7 seed 2) crossed the portal after digging its room at y -25: the dimension change dropped the client's overlay, but the model resent only the original columns, putting its feet and head back in deepslate while the model still held the open room. The first fortress tunnel failed `:no-stand` twice in 300 ms. Resending the changed blocks keeps the two terrain views in agreement after a dimension change or an ordinary chunk reload.

## Falling blocks
A block of one of `blocks/falling-types` looks under itself `fall-delay-ms` (two game ticks) after the block under it changed (`disturb`, called by a break, a placement, a scoop and a fall), as the game's `FallingBlock` schedules its tick on the neighbour update. If the cell under it is then free (air, a liquid, anything passable: `FallingBlock.isFree`), its cell becomes air for the client and it is in the air in `:sim/falling`. Each game tick `fall-tick` moves it as `FallingBlockEntity.tick` does (it gains 0.04 blocks a tick of speed, moves, keeps 98% of it) and lands it on whatever is under it at that moment, so a second gravel lands on the first rather than in the cell the first was still falling into: one block down takes seven ticks. Read in the 26.1.2 jar with `javap`. Not modelled: a block landing in a cell no longer free (the game drops it as an item), and harm to a player under it.

## Water and lava
The model moves fluids as the server does (issue #125). The rules are `fluid.clj`, data and pure functions over a cell lookup, from vanilla's `FlowingFluid`, `WaterFluid`, `LavaFluid` and `LiquidBlock`: a fluid state is a map (`:fluid/kind :fluid/amount :fluid/source? :fluid/falling?`, the block's level is its legacy encoding, `state-of`, `block-state`); `kinds` holds each kind's drop-off, slope search, tick delay and whether two sources make a third, for the Overworld and the Nether. A cell's tick makes it what its neighbours feed it (`new-liquid`, `next-state`: a source stays, a flow is falling under its kind, else the most beside it less the drop-off, nothing at none, so a flow recedes block by block when its source is scooped) and then spreads (`spread`: down first into a cell holding no solid and no fluid; on the level only when it cannot go down, toward the open sides nearest a way down, `side-spread`, `slope-distance`; lava falling into water makes stone). Lava touched by water above or beside turns at once to obsidian when a source and cobblestone when a flow (`mix`). The model only schedules: every change goes through `set-cell`, whose `disturb` quenches at once and wakes every fluid at or round the cell to tick its kind's delay later (`wake-fluids`, `:sim/fluids` {cell due-ms}); `tick-fluids` ticks the due cells in position order, so a run replays. Ice or frosted ice broken over a solid or a fluid leaves a water source (`melts?`, the game's `IceBlock.playerDestroy` without silk touch), except in the Nether; it broke to air before, and a bot that dug the ice over its head from a frozen sea floated a block under the rim with no bank (issue #177). Not modelled: lava's random slow-down, fire.

`state-of` reads `blocks/fluid`, including aquatic and waterlogged sources. Their fluid ticks preserve the block when its fluid is unchanged, while spreading the water it holds. Sim race 39007350790 runner 2 (issue #344, R3/4 seed 1) dug the sea bed beneath tall seagrass: the model recognized no source there, left the shaft dry and the rise jumped until `[:cast-one :no-escape]` three times. New changes now schedule that source. Older saved models lack `:sim/fluid-sources-ready?`; their first `tick-fluids` wakes fluids beside broken and placed cells once, retaining earlier pending deadlines, then marks the scheduler ready. The healthy shaft regression rises to dry land without a death; the issue's one-health snapshot instead recovers through deaths and respawn, then resumes crafting and building within its unchanged window.

## Key files
- `sim.clj`, `init` - takes `{:column bytes :spawn [x y z] :keep-alive-every ms :drop-clicks #{n} :drop-breaks #{n} :inventory {slot item} :respawn [x y z]}` (`:drop-breaks`: the FINISHes, by ordinal, the model answers with the ack alone and the block left standing, as a lagging server does with an early FINISH queued behind another; `finish-dig`); an `:inventory` slot is a named place (`[:hotbar 0]`, `[:main 3]`, read by `inventory/slot`) or a window-0 slot number, so a test says where an item is rather than which wire slot that is.
- `sim.clj`, `joined` - the model already in play with a player it never saw join (issue #299, [[real-snapshots]]): `init`'s world, then the real server's state at a moment given as data (time, position, window 0, health, food, the blocks changed, the mobs and items under the client's ids, the columns the client holds); nothing is sent but `remove-entities` for what the client tracks and the model does not carry. `:sim/player-eid` is then the real server's id for the player (`player-eid-of`), which pickups and the eaten event name.
- `sim.clj`, `run` - drives a bot reducer against the model 50 ms per tick, sends `:go` once loaded, stops on a predicate.
- `sim_test.clj`, `the-wood-goal-holds-in-generated-forests` - test.check builds 2,000 worlds (`docs/RICH-HICKEY.md`: "thousands of generated worlds in the time one real run takes"): 1-3 trees of oak, spruce or birch, 3-6 logs high, anywhere in the column; each maybe on a one-block stone mound and maybe under low leaves over every cell within two steps; the spawn varies. The whole bot runs against the instrumented model in each and must end holding a log with no violation. About 16 ms a world; `FOREST_TRIALS=n` overrides the count and `FOREST_SEED=s` the seed, which is how `test.yml` runs it as eight shards of 250 ([[test-fixtures]]). Treating only oak as a log fails it (shrunk to one spruce), and so does removing the blocker's headroom check over a step: the mounds under canopies reproduce the ledge a live run failed on.
- `sim_test.clj` - end-to-end tests: one log held (`:wood`), a low canopy cleared, a table and four sticks (`:kit`), a placed table and a wooden pickaxe (`:pickaxe`), recovery from a lost click, and the iron kit (`the-iron-kit-from-a-stone-pickaxe-and-its-recording-replays`): from a stone pickaxe, a table, eight cobblestone and four logs on one column with coal, iron, gravel and a pond, to flint and steel and a filled water bucket in 92 simulated seconds, in the kit's order (an ore and a coal, the furnace, one ingot, gravel dug again four times to flint with seed 4, the flint and steel, the pickaxe put back in hand, three more ores, three ingots, the bucket, filled from the bank). The run is recorded through `record/tap!` (each event, then only the effects it added: `sim/run` clears effects once a tick) and `record/verify` replays it with every sent packet the same.

## Gotchas
- The model knows window 0, the crafting table and the furnace, fills and pours buckets of water and lava, moves them as the server does, and drops gravel and sand: no other containers; every roadmap issue extends it first.
- The sim uses `recipe/match` for slot 0, so bot and model agree on recipes by construction; only a live run checks the matcher against vanilla.
- A pickup gives the item the broken block's loot table dropped, which the model stores with the item entity.

## See also
- [[early-finish-aborts]]
