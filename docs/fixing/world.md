# Reference: world, protocol, game, physics, windows, mechanics, the server model, gotchas (section 20)

Part of the bot-failure manual; [AGENTS.md](../../AGENTS.md) is its entry and index. Read only the section a question needs.

## 20. World, protocol, game

Every attribute, its meaning and lifetime is in `src/clojurecraft/model.clj` (`model/attributes`) and its spec in `spec.clj`; read those, not a copy. One line per namespace is in CLAUDE.md's Layout.

### 20.1 Design decisions a fix must not break

- One world value, written only by `main/apply-event!`; unknown = absent, never nil. No clock or `rand` in a reducer (`:event/now`, `:event/rand`); new nondeterminism must be an event field (an early `rand-nth` broke replay). Phase changes only in `game/emit` via `packet/transitions`.
- Goals in play arrive on `:go` (`:go/goals`, `:go/chain?`), never via a CLI flag or dynamic var. Chained runs use milestones (`:plan/reached`); without them a run recrafted sticks forever. `:portal-kit` counts the filled water bucket beside the empty one (#49).
- Memory is append-only DataScript facts. Protocol from scratch: never add a Java client library.
- Predict nothing: every `container-click` is sent with `:changed [] :cursor nil`; windows change only on `container-set-slot`, `container-set-content`, `set-cursor-item`. Manual clicks, never `place-recipe` (no spec; undecodable display ids; vanilla ghost-places).
- `recipe/sources` takes each cell group from one stack that covers it, else several, lowest first (#86), never reading the grid. One `:craft` intent = one craft; slot 0 shift-clicked only when exactly `{:item result :count makes}`. Window-0 slots 0-4 live in `:window/grid`, never `:player/inventory`; counts read the inventory only.
- The server judges placement: done only on its `block-update`. One planner (`make/next-intent :needs`): never add a second answer to the same question. `blocks/log-items` matches only names ending `_log` (no `*_wood`, no `crimson_stem`).
- `memo/by` keys by `identical?` (`memory/positions-in`, `stone/exposed-cells`, `path/plan-on`): keep the clock out of memo keys and the overlay identical when nothing changed (`terrain/superseded`), or plan-tick cost explodes.
- New attribute → row in `model/attributes` + spec in the same change. Meanings never change; packet specs only grow (`packet_specs_ever.edn`); goal rows are superseded, never edited.

### 20.2 Terrain (`terrain.clj`, `chunk.clj`, `blocks.clj`)

- `:world/chunks` `{[cx cz] {:sections [...] :chunk/min-y y}}`; overworld 24 sections from -64, Nether/End 16 from 0 (decode takes the count from the column).
- Section `{:single id}` or `{:bits n :palette [...] :longs long[]}`; palette nil above 8 bits; index `(y<<8)|(z<<4)|x`, entries never straddle longs, no long-count prefix in 26.x.
- `terrain/block-at`: overlay `:world/blocks` first, then `chunk/block-at`. **nil = not loaded, never air.**
- Overlay writers: `set-block` (server's word; confirms an unconfirmed cell; `memory/observe`), `predict` (dig-silence guess into `:world/unconfirmed`), `forget-stale` (drops guesses > 3000 ms every tick). `confirmed?` = not a guess. `put-column` drops overlay and guesses inside that chunk.
- `solid-fn` treats unloaded as solid; `blocks/solid?` of an unknown id is false (two different questions). `fluid-at` → `{:fluid/kind :fluid/level}` or nil (unloaded = no fluid; kelp, seagrass, bubble columns count as water source). `under-sky?`: only air, plants or leaves above up to y 319; unloaded is not under sky.
- `blocks/passable-types`: `grass` (the type of `grass_block`) deliberately absent (copying ruststeve's list made the ground passable); `tall_grass`, `snow_layer`, `nether_portal` passable. Leaves solid; slabs, stairs, fences, doors are full cubes.
- `blocks/replaceable?` (bot's spot rule): air, grasses, ferns, dead bush, seagrass, vines, roots, lichen; never flowers, flower beds, bushes, saplings. `blocks/placeable-into?` (server's): those plus any fluid (#44).
- State ids (26.1.2): air 0, cave_air 15293, stone 1, grass_block 8-9, dirt 10, water 86-101, lava 102-117, oak_log 136-138; item entity type 71. Regenerate with `bb datagen`.

### 20.3 Memory (`memory.clj`)

- Sightings `{:sight/pos :sight/state :sight/at :sight/dimension}` appended only on change. `watched?`: logs, `placed-kinds` `#{:crafting_table :furnace}`, `landmark-kinds` (fortress bricks, fences, stairs, lit portal). Never stone (it froze every chunk load).
- Pools: one fact per chunk and kind (highest surface source nearest the centre). Build sites: `remember-site`/`site`.
- Queries (in the bot's dimension): `remembered`, `history`, `latest`, `positions-now` (memoised `positions-in`), `nearest`, `nearest-log` (radius 48, ±12 of the eye; blacklist removed before `trunk-bottom?`), `pools`, `nearest-surface-water` (≤ 5 under the eye; deeper is the aquifer trap), `answers`, `intentions`, `intention-as-of`.
- Edges: air is sticky; a chunk load records logs but not air (a log broken while unloaded stays a log); a chunk load clears the overlay, not memory; first facts carry time 0; no eviction. Terrain answers "is water beside me now", memory "where can I walk for water".

### 20.4 Physics (`physics.clj`)

- `(physics/step solid? fluid-at world controls)`, run by `game/on-tick` only when `game/physics-ready?` and not dead. Controls `:control/forward? :jump? :sneak? :yaw :look` persist in `:player/controls`.
- Constants: tick 50 ms, gravity 0.08, drag 0.98, half-width 0.3, height 1.8, eye 1.62, jump 0.42, jump cooldown 10 ticks. Walk 0.216 blocks/tick; jump peak 1.2-1.3. **Resting vy is -0.0784, never 0**: stillness checks are horizontal only. No step-up, sprint or strafe; non-full cubes are full cubes. Yaw: south 0, east -90; looking down is positive pitch.
- Fluids: `eye-in-water?` tests y+1.62. **The only lift out of water** is `out-of-liquid-impulse` 0.3 on horizontal collision when the box moved up 0.6 would be free; swim-up never clears a one-block bank. Floats with feet 1.03 under the surface, 0.1 blocks/tick. Flow currents are **not modelled**.
- Breath `:player/air` is an estimate (own metadata not decoded): -1/tick eye under, +4 otherwise, 0-300.
- `move-player-pos-rot` on change, status-only every 20 ticks; on-ground flag `0x01` (typecraft's `0x80` is wrong).

### 20.5 Protocol

- A packet is a flat map with `:packet/name` and unqualified wire fields; never a `:name` field (collision; hello uses `:username`).
- `packet/specs` maps `[state dir name]` → `[[key type] ...]` (66 rows). `packet/values` names enums; a missing name throws. `decode` reads listed fields only; `encode` throws on a missing spec. Ids in generated `packets.edn`.
- Synthetic packets: no spec → `{:packet/name :unknown}` counted in `:stats/unknown`; decode exception → `:decode-error`; socket failure → `:closed` (sets `:bot/closed`).
- `:slot` components decode through `component-codecs` (damage, max damage, max stack size, repair cost, unbreakable); any other truncates the rest (`:truncated true`). Before this, a worn pickaxe hid the water bucket behind it (`:no-scoop`).
- Phases `:handshake → :login → :configuration → :play`, back on `start-configuration`. `hello` carries the MD5 v3 UUID of `OfflinePlayer:<name>`; changing `--name` changes player data.
- The first teleport also emits `player-loaded`; without it nothing (pickup included) ticks for the player; resent after a dimension change.
- `set-time` has a fieldless spec; `game/clocked` estimates `:server/tick-ms` from its spacing (one every 20 server ticks). Not modelled: `attack` (c2s 1), `place-recipe` (39), encryption. Entity metadata is skimmed (`packet/read-metadata`: simple serializers, stopping at the first other); every entity is tracked with velocity, a living one's health and flags, deaths (`entity.clj`, brain `world/entities`, #258).
- `:bot/sequence` increments for each `player-action`, `use-item-on`, `use-item`; answered when `:stats/last-ack` ≥ ours. `block-changed-ack` appends an `:ack` answer.
- **After a break the server sends `block-update` then the ack. An ack alone means not broken** (dropped early FINISH, or refused). Under delayed destroy a loaded server breaks seconds after the ack (#64).
- Never predicted: placed blocks, window slots and cursor, an acked-but-undone break, pickups (`take-item-entity` with our id appends `:pickup`), health. The only guess is the dig-silence overlay.
- Death: `set-health ≤ 0` → `game/die` (dead, controls cleared, `:death` answer, `:plan/intent`/`:plan/last` dropped, `client-command 0`). `change-dimension` drops columns and overlay and resets `:player/loaded?`. A hand-built `{:packet/name :respawn}` fails the spec; use `fixtures/respawn`.

### 20.6 Loop, recording, replay

- `main/step` = `(game/compose game/step plan/step)`; ticks on a fixed 50 ms grid. `apply-event!` swaps, reads effects, clears them, performs them: a reducer may assume `:bot/effects` empty at step start.
- `-main` defaults: port 25571, name `Clj_wood`, `--until wood`, timeout 120 s. `--events stdin` drops bad EDN lines (`external-event`).
- RESULT: one stdout line `RESULT {...}`; `:reason` is `:goal`, `:disconnected`, `:closed`, a `:plan/reason`, or `:timeout`; `:held`, `:plan`, `:stats`. Exit 0 iff `:ok true`.
- Recording: optional `{:record/commit sha}`, one EDN line per event (`record/wire` drops derived `:chunk/column`), `#:record{:effects ...}` after any event with effects (grep that form), `{:record/result ...}` last. Tap runs before the swap; `tap-buffer` 4096 blocks rather than drop. `.gz` reads directly.
- Replay: `record/verify` compares `:send` effects (logs ignored): `:identical` or the first `:record/mismatch`. **Fold a recording at its own commit** (`bb replay`; `--here` for the working tree): newer code makes a whole file look truncated (#111).
- `dev/clojurecraft/probe.clj`: `fold`, `at-ms`, `story` (every intent's start, end, reason), `around` (slice round the feet: `#` solid `.` clear `W` water `L` lava `~` awkward `g` falling).
- Real snapshots `real-<runner>-<s>-<bot>.edn.gz` (`real.clj`): the same value built from a race's recording at its commit (`real/fold-step`, `convert`, `sim/joined`), its columns inside it (`:snapshot/columns`, `simrace/own-world`), `:snapshot/source :real`; `clojure -M:simrace --from-recording f` makes one and keeps it only when it recurs.
- Sim snapshots `<set>-<n>-<seed>.edn.gz`: a moment kept from before the choice that failed (`simrace/decision`, `chosen-moment`; runs keep a moment every 30 s, the last 12, `sim/play-from`), with the next tick's inputs and `:snapshot/moment` (why, when, window). `simrace/judged` plays it over its window, never from the landing. `bb issue-check fetch <n>` → `test/issues/<n>/`; `clojure -M:simrace --judge f` judges one.
- Telemetry (`watch.clj`) is a view: offered to a 1024 channel, drops rather than slow the loop, never recorded.

### 20.7 Windows, crafting, smelting

| Window | Own slots | Player slots | Hotbar starts | Menu type |
|---|---|---|---|---|
| Window 0 | 0 result, 1-4 grid, 5-8 armour | main 9-35, hotbar 36-44, offhand 45 | 36 | none |
| Crafting table | 0 result, 1-9 grid | main 10-36, hotbar 37-45 | 37 | 12 |
| Furnace | input 0, fuel 1, output 2 | from 3 | 30 | 14 |

- Name slots with `inventory/slot-in` (`[:hotbar n] [:main n] [:result] [:grid n] [:input] [:fuel] [:output]` ...).
- A current-state-id click is answered per changed slot; a stale one by `container-set-content` + real cursor. **A click that changes nothing gets no packet.** A click for a closed window is silently ignored.
- `window/click` emits, drops `:window/cursor`, records `:intent/awaiting`; intent-only. `window/waiting` → nil, `:waiting`, or `:overdue` after `answer-timeout` 3000. On overdue `window/lost` resyncs (`resync-click`, state id -1, cursor put in a free slot, or with none a `junk` stack thrown); the `max-lost` 3rd loss fails `:stale-window` (#131, #132: loaded servers answer late, not never).
- **A full inventory:** a result or output moved into 36 full slots stays put and gets no answer. The craft's and smelt's `:settle` throw a `window/junk` stack first when no slot is free (`window/tossable`, `toss`: look up to `toss-pitch`, then mode 4; cobblestone only above `cobblestone-keep`; #172, #192).
- **Phantom cursor:** the server adopts the click's claimed cursor, so `window/click` forgets the cursor as it sends; never claim a non-empty cursor.
- **A furnace moves the state id by itself:** judge a click by the slot clicked.
- `recipe/recipes` 1030 recipes, tags resolved; `datagen/shrink` trims padding. `recipe/clicks` lays one craft; nil when not coverable.
- `:craft`: `:settle` (`:unknown-recipe`; close any container for `:inventory`; 5 s for a state id `:no-window`; cursor down `:inventory-full`; shift-click grid empty; plan `:no-ingredient`) → `:click` (one per round trip) → `:verify` (slot 0 exactly expected else `:wrong-result`; 5 s `:no-result`) → `:take` (5 s `:not-taken`; table craft then closes). Junk crafts (two planks → pressure plate, one → button) are why the grid is emptied and slot 0 verified.
- Table: `make/craft-row` adds `{:block/crafting_table :near}` to recipes over 2×2; `make/at-container` uses an open table, opens one in reach (4.5), or walks; menu type must be 12 else `:no-table-window`.
- Smelting: `make/smelt-row` needs input, `{:fuel/ticks t}`, `{:block/furnace :near}`. `smelt/burn-order` coal/charcoal then planks; never logs, never sticks (#36); charcoal never made. `fuel-clicks` loads `ceil(ticks/burn)` from one stack. `:smelt`: `:settle` → `:load` (fuel then input) → `:cook` (`loaded?` 5 s `:not-loaded`; `cook-by` = now + n × cook ticks × 50 + 5 s; next fuel stack or `:out-of-fuel`; past it `:not-cooked` once the furnace stops cooking, `cooking?`) → `:take` (5 s) → `:close`. 200 ticks/item; plank burns 300. An abandoned mid-cook smelt leaves input in the furnace, invisible to the planner.

### 20.8 Vanilla 26.1.2 mechanics

- Dig: `damage/tick = speed / hardness / (30 if harvest else 100)`, `ticks = ceil(1/damage)`. Log by hand 3000 ms, leaves 300. Tier ranks: wood/gold 0, stone/copper 1, iron 2, diamond/netherite 3; stone needs any pickaxe, iron ore stone. Gravel's flint is a 0.1 roll.
- Server takes FINISH once 70% of its break time passed since START; earlier aborts silently.
- Reach: dig 4.5 eye to centre, place 4.0, open 4.5, sim bucket ray 4.5.
- `use-item-on` (c2s 66): bot clicks the support's top face (face 1), cursor `[0.5 1.0 0.5]`. Server places only into replaceable cells (+ fluids), **never into any player's box** (#106); right-clicking a table or furnace opens it. Rejected = `block-update` restating the cell, then the ack. Judge furnaces by block name (facing varies).
- Buckets use `use-item` (c2s 67, carries yaw/pitch), not `use-item-on`; flint and steel uses `use-item-on`. Empty bucket ray-casts source-only; full one pours before the first solid.
- Fluids (`fluid.clj`): water drop 1, slope 4, every 250 ms, makes sources; lava drop 2, slope 2, every 1500 ms (Nether: drop 1, 500 ms). Lava touched by water **above or beside (never below)** → obsidian from a source, cobblestone from a flow. Not modelled: waterlogged flows, fire.
- Falling blocks fall 2 ticks after the cell below changes; gravel refills a niche dug under it.
- Pickup: box inflated (1, 0.5, 1), 10-tick delay, 0.5-1 s after FINISH live.
- 20 tps; one server stops near 25 bots. Drowning: 2 damage every 20 ticks at zero air.
- Server settings: any new `server.properties` needs `pause-when-empty-seconds=0` and `spawn-protection=0` (else a non-op's breaks revert after the ack). `race.clj` sends `gamerule keep_inventory true`; camelCase gamerules return an error string, not an exception.

### 20.9 The server model (`sim.clj`) and its limits

- `(sim/step sim event)`; driven by `sim/play-from` (from a moment: world, model, pending packets, tick seed; keeps moments when asked), `sim/run`/`run+` (from the handshake, 50 ms ticks, moments kept), `sim/play-on` (from a world and model), `sim/connect!` (behind channels like `conn/open!`).
- Faithful: handshake, chunk streaming, breaks at 70% with update before ack, delayed destroy, seeded loot, tool wear (tools stack to 1, #36), pickup after 500 ms, windows with vanilla click rules, furnaces cooking every tick, placement under `placeable-into?`, buckets, fluid flow (`tick-fluids`), falling blocks, portal lighting (dimension changes after 4000 ms), drowning, respawn, hunger and eating at a given `:difficulty`, mobs (night spawns, melee, arrows, creeper blasts, the `attack` with its hurt window, loot; fixtures by `:mobs`), eyes of ender toward `:stronghold`, end portal frames and the portal, the End (`:end`: platform, perched dragon, crystals) (#260).
- Inputs: `:columns`, `:nether` (the Nether a portal leads to: a captured set's region, the player where the server would link the portal, `sim/arrival`, #353), faults `:drop-clicks :drop-breaks :lag-ticks :slow-breaks :kill-at`, `:inventory`, `:seed`, `:tick-seed`. `:sim/violations` (`:click-on-empty-result`, `:close-with-cursor`, `:click-inventory-while-open`, `:place-into-player`) must be `[]` in end-to-end tests.
- Not modelled: real server lag (no reorder, duplicate, lost cursor, set-time, so `:server/tick-ms` never stretches in the sim); the captured world's edge (radius 12 columns; past it unloaded = solid/`:unknown`) and the captured Nether's (32 chunks past the landings' Nether points); the linked portal's frame round a player arriving in the Nether; water push and rubber-banding (model trusts reported position); `use-item-on` reach; block facing; break penalties; stacking of buckets (64); hoppers, lava-bucket fuel; a never-opened furnace does not tick.
- A sim-only failure touching those may be the model's limit: if a real race on the same signature does not fail, fix `sim.clj` first against the jar (#44: model refused placing into water; #36: tools stacked). Real-only failures under load need a fault input before a sim test can hold the fix.

### 20.10 Gotchas already paid for

Most are stated where they apply above (grass type, resting vy, `:name` collision, early FINISH, phantom cursor, junk crafts, sticky memory, guesses, ashore, block-edge starts, furnace state ids, own-commit replay). The rest:
- Digging the bottom log from above dropped it into a hole: `wood/trunk-target` digs the log nearest feet height. Leaves at head height over a drop: collect stall → `intent/blocker` → dig the leaf (≤ 6); `step?` also checks over the step and the player.
- Never place into a guess, a flower, onto a table or into a body: each cost three `:rejected` in a race. Gravel refilled a niche dug under it seven times.
- Nether columns read at overworld height put brick 64 off: read height from the column. `set-held-slot` outside 0-8 is ignored (found by the totality property).

---
