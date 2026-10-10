# Place the crafting table, open it, craft a wooden pickaxe; then recipe-driven goals

## Summary

Add the second and third verbs the bot needs after `:wood`: putting a block into the world (`use-item-on`, C→S id 66) and crafting in a 3×3 window (`open-screen` → `container-set-content` → `container-click` → `container-close`). The target is one new goal, `:wooden-pickaxe`, reached entirely against `sim.clj` first and a vanilla 26.1.2 server second. On the way the goal table grows a declarative shape: a goal states what it `:goal/wants` and the planner derives what it needs from the recipe graph, so the next twenty tool goals are rows, not code. This issue assumes the 2×2 craft in window 0 (planks, sticks, the table item) from issue 01; its sim test starts from that end state and also chains onto it.

## Why now / what the siblings learned

Both siblings spent most of their race time inside placement and crafting bugs, and every fix was a timer, a retry or a flag on a mutable object. The failure modes are well recorded; we design them out rather than around.

**Placement is judged by the server, never predicted.** ruststeve's `place_block` (`src/bot/mod.rs:1853-1905`) writes the block into its local world the moment it sends `use_item_on`; the consequences fill `CHANGES.md`: "the predicted GHOST block blocked the bot's own physics on the platform row" (`CHANGES.md:124`), "`place_cobble` re-read the cell 3 ticks after `place_block`, but `place_block` predicts the block client-side at once, so a placement the server rejected or reverted counted as placed. Every cap, shaft seal and station seal could be a ghost" (`CHANGES.md:1092`), and the sniffed ground truth that a rejected placement comes back as `block_update (4,67,0) -> air` (`CHANGES.md:964`). It then learned the inventory side of the same lesson: "The SDK never decrements the held stack when it places a block" (`CHANGES.md:1119`), so counting spent blocks misread real placements. steve's `placeStationBlock` opens with "placeBlock is server-rejected ~15-30% of the time, consuming the item client-side without placing anything" (`steve/src/lib/steve/lib/bot-utils.ts:1564-1572`). typecraft's `placeBlockWithOptions` waits up to 5 s for a `blockUpdate` at the destination and only counts `oldStateId !== newStateId` (`typecraft/bot/placing.ts:145-180`), which is right in spirit but is a callback on a timer. Our rule: `set-block` happens only from `block-update`; a `:place` intent is done when the world value shows the placed state at the destination and `:stats/last-ack` has passed its sequence, and failed when the ack has arrived and the cell has not changed. No local prediction, no held-stack decrement, no timer other than an absolute deadline.

**Never place into your own body, and choose the reference block by type.** ruststeve: "Never predict a block INTO our own body: the server rejects that placement" (`mod.rs:1889-1891`); steve's `clipsBot` does the same AABB test (`bot-utils.ts:1577-1588`). steve also found that right-clicking a utility block interacts instead of placing: "Standing on a crafting table / furnace: a plain right-click OPENS it, so the pillar block never lands" (`bot-utils.ts:980-984`), fixed by sneaking. ruststeve's `table_cell_empty` had to accept `cave_air` after 1,944 loops on `state == 0` (`bot_utils.rs:915-920`). We compute the candidate list as a pure function over the world (`place/site`): a solid, non-utility, non-fluid support under an `air`-typed cell (`blocks/type-of` = `:air`, which covers all three air states) whose unit box does not intersect `physics/aabb` of `:player/pos`, within `intent/reach`. Sneaking is never needed because the reference block is never a utility block.

**The window is server-authoritative; the state id is load-bearing.** ruststeve sends `container_click` with empty `changedSlots` "so the server ALWAYS sees a prediction mismatch and replies ... authoritative" (`src/bot/inventory.rs:50-57`); typecraft's `craft` forgets the cached result slot before placing (`crafting.ts:194-199`), verifies the result is the recipe's item before taking it after minting 25 buttons from a stray plank (`crafting.ts:300-332`), and sweeps "GRID FIRST, result slot LAST" (`crafting.ts:349-357`). ruststeve restricts ingredient search to the inventory section after picking a plank back out of the grid (`src/bot/crafting.rs:17-20`). typecraft's scripted craft server states the vanilla rules we must model: "a click is applied only when its windowId is the container the server has open (otherwise it is ignored with no reply)", "after a click the server sends container_set_slot for every slot where its own state differs from what the client claimed", "a login or respawn gives the player a fresh menu" (`typecraft/bot/craft-window.test.ts:1-10`). Our bot claims nothing (`:changed-slots []`, `:cursor-item nil`), echoes the last `:window/state-id`, and advances the click plan only when the server's slot update arrives; a stale state id makes vanilla drop the click and resend everything, which to a reducer is just another packet. steve's "wood-lock" (`run-loop.ts:365-372`: moving a log into the grid flips `gather_wood.isComplete()` and the planner preempts the craft) cannot happen when the grid is part of the world value and `logs-held` counts `:window/slots` too.

**`activateItem` is not needed here.** steve's `reliableUse` exists because `bot.activateItem()` leaves `usingHeldItem` stuck (`tasks/portal/cast.ts:88-90, 143-151`); that is `use-item` (67, no block hit). Opening a table and placing a block are both `use-item-on`; buckets are a later issue.

**Goals.** steve's `craft_planks` → `craft_crafting_table` → `craft_sticks` → `craft_wooden_pickaxe` encode prerequisites as hand-written `canExecute`/`isComplete` lambdas (`steps.ts:138-170`) and `completedFrom` re-derives the set every tick (`run-loop.ts:143-144`). The re-derivation is right; the lambdas are the recipe table transcribed by hand, with the comments of two years of deadlocks. We derive needs from the recipe graph.

## Design

**Data from the jar.** `scripts/datagen.sh` also unzips `data/minecraft/recipe/*.json` and `data/minecraft/tags/item/*.json` from the inner jar (`META-INF/versions/26.1.2/server-26.1.2.jar`) and `datagen.clj` writes `recipes.edn` (crafting_shaped/shapeless only) and `item-tags.edn`. Rows:

```clojure
{:recipe/id :wooden_pickaxe :recipe/type :shaped
 :recipe/pattern ["XXX" " # " " # "]
 :recipe/key {\X #{:tag/wooden_tool_materials} \# #{:item/stick}}
 :recipe/result {:item/wooden_pickaxe 1}}
```

`wooden_tool_materials` is `["#minecraft:planks"]`, so tags resolve recursively; `crafting_table` is `["##" "##"]` of `#planks`, `stick` is `["#" "#"]` → 4, `oak_planks` is shapeless `#oak_logs` → 4.

**`recipe.clj`, pure.** `(resolve-tag tags k) → #{item-ids}`; `(needs recipes id) → {:item/stick 2 :tag/planks 3}` plus `:block/crafting_table :near` when the pattern exceeds 2×2; `(grid-plan recipe inventory size) → [{:click/slot 10 :click/button 0 :click/mode 0} ...]` choosing, per key, the held item variant with the largest stack (steve's `resolveId`, `bot-utils.ts:2050-2068`); `(match grid size) → result-or-nil`, the same function the sim uses to fill slot 0. Layout of menu type 12 (`minecraft:crafting`, registry id 12; typecraft `windows.ts:60`): 0 result, 1–9 grid (`1 + x + 3y`), 10–36 main, 37–45 hotbar.

**Packet specs to add** (`packet/specs`, ids already in `packets.edn`):

```clojure
[:play :c2s :use-item-on] [[:hand :varint] [:pos :position] [:face :varint] [:cursor-x :f32] [:cursor-y :f32] [:cursor-z :f32] [:inside-block :bool] [:world-border-hit :bool] [:sequence :varint]]
[:play :c2s :container-click] [[:window-id :varint] [:state-id :varint] [:slot :i16] [:button :i8] [:mode :varint] [:changed-slots [:vec [[:slot :i16] [:item [:opt :hashed-slot]]]]] [:cursor-item [:opt :hashed-slot]]]
[:play :c2s :container-close] [[:window-id :varint]]
[:play :s2c :open-screen] [[:window-id :varint] [:type :varint] [:title :rest]]
[:play :s2c :container-close] [[:window-id :varint]]
[:play :s2c :set-cursor-item] [[:item :slot]]
```

`use-item-on` is checked against typecraft `packet-defs.ts:2772-2784` and ruststeve `protocol-schema.json` (same nine fields, face is a varint here, an `i8` in `player-action`). `[:opt T]` is a new compound type (nil → one `false` byte); `:hashed-slot` writes item, count, two empty component vectors. `open-screen`'s title NBT is kept as `:rest`.

**World attributes.** `:window/id`, `:window/type`, `:window/state-id`, `:window/slots {i item}`, `:window/cursor`, `:window/opened-at`; all dissoc'd on `container-close` or respawn. `game/container->player-slot` gains a sibling `window->player-slot [type s]` so a table window's 10–45 also update `:player/inventory` (today `game.clj:213-217` drops every non-zero window). `game/items-held` counts `:player/inventory`, `:window/slots` 1–9 and `:window/cursor`, so nothing in a grid is invisible.

**Intents.**

```clojure
{:intent/kind :place :intent/item 333 :intent/target [x y z] :intent/against [x (dec y) z] :intent/face 1
 :intent/stage :equip|:settle|:sent :intent/sequence n :intent/deadline ms}
{:intent/kind :open-container :intent/target [x y z] :intent/window-type 12 :intent/sequence n :intent/deadline ms}
{:intent/kind :craft :intent/recipe :wooden_pickaxe :intent/plan [...] :intent/step i :intent/sent-state-id s :intent/deadline ms}
```

`:place` equips via `set-carried-item` (or a mode-2 hotbar swap in window 0 when the item is in 9–35), settles three still ticks like `:dig` (`intent.clj:105-120`), emits `use-item-on` with cursor `[0.5 1.0 0.5]` for face 1 and `:bot/sequence`, then waits: done when `(game/block-at world target)` is a `crafting_table` state and `:stats/last-ack ≥ sequence`; failed `:rejected` when the ack has passed and the cell is unchanged. `:open-container` is done when `:window/type` is 12 and `:window/slots` has arrived. `:craft` sends one click per server reply, takes slot 0 only when `match` of `:window/slots` equals the recipe result, returns the grid (1–9) before the result, and ends with `container-close`; any mismatch fails `:grid-desync` and the grid is swept, never the result slot.

**Memory.** No new attribute: `memory/watched?` (`memory.clj:13`) grows to `crafting_table` states, so a placed or seen table is a sighting that survives chunk unloads; `nearest-log` generalises to `(nearest world eye radius blacklist pred)`.

**Goals.** Rows gain `:goal/wants`; `goal-done?` defaults to holding the wants. Dispatch uses a hierarchy: `(derive :wooden-pickaxe :goal/recipe)` and one `defmethod next-intent :goal/recipe` in `craft.clj` that, from `(recipe/needs ...)` against the world, returns the first unmet need as an intent: no table in reach → `:place` if the item is held, else the table recipe; a table in reach → `:open-container`, then `:craft`. `:crafting-table` is its own row (`:goal/wants {:block/crafting_table :near}`) so `plan/choose` stays a sort. `spec.clj` adds `:window/*`, `:intent/item`, `:intent/against`, `:intent/face`, `:intent/plan`, `:goal/wants`, `::recipe`, and an fdef on `recipe/grid-plan`.

**Sim.** `sim/init` gains `:sim/inventory`, `:sim/held`, `:sim/window`, `:sim/next-window 1`. `use-item-on`: a reject (destination not air, support not solid, or overlapping the player's AABB) sends `block-update` for the destination at its current state plus `block-changed-ack`; a success sets the block, decrements the held stack with `set-player-inventory`, sends both `block-update`s and the ack; a click on a table sends `open-screen` + `container-set-content`. `container-click` is dropped silently for a window id or state id the sim does not have open; otherwise it applies modes 0 and 1, recomputes slot 0 with `recipe/match`, and sends `container-set-slot` for every changed slot and `set-cursor-item`. `container-close` returns the grid to the inventory via `set-player-inventory`.

## Steps

1. Datagen: `recipes.edn`, `item-tags.edn`; test that the four recipes above load with the expected shapes.
2. `recipe.clj` with `needs`, `grid-plan`, `match`; unit tests on the four recipes, property: for every shaped recipe, `(match (apply-plan empty (grid-plan r inv 3)) 3)` equals its result.
3. `packet.clj`: the six specs and `[:opt T]`; byte-exact encode tests for `use-item-on` and `container-click`; `props_test` generators cover the new s2c specs automatically.
4. `game.clj`: `:window/*` reducers, `window->player-slot`, `items-held`; tests with canned `open-screen`/`set-content`/`set-slot`/`close`.
5. `intent.clj` (or `place.clj`, `craft.clj`): the three intents; timeline tests via `fx/fold` including a rejected placement and a dropped click.
6. `sim.clj` extension; `sim_test`: start in play holding 4 planks, 2 sticks and a table item, end with `:item/wooden_pickaxe` in `:player/inventory` and the table at a sighting; a second test chains after issue 01's log-to-table run.
7. `plan.clj` hierarchy + `craft.clj` goals; property: `plan/choose` never returns a goal whose `needs` are unmet when a lower-priority goal satisfying them exists.
8. `main` `--until pickaxe`; record a live run with `--record` and replay it.
9. `.github/workflows/pickaxe.yml`.

## Acceptance criteria

- Unit: every step above green under `clojure -M:test` with reducers instrumented; no namespace outside `conn`/`rcon`/`harness`/`main` touches I/O.
- Property: `step` stays total over generated `open-screen`/`set-content`/`set-cursor-item` packets; the click plan for any shaped recipe reproduces its result under `match`; a `:place` intent never emits `use-item-on` whose destination intersects `physics/aabb`.
- Sim end-to-end: from issue 01's end state to a held wooden pickaxe in under 60 simulated seconds, with the rejected-placement and stale-state-id paths each exercised in a dedicated test.
- Live: `clojure -M:run --until pickaxe` against the local 26.1.2 server prints `RESULT {:ok true ...}` including `:table/pos`; the recording replays to the same result with no server.
- CI gym: `pickaxe.yml` judges by the RESULT line, `data get entity Clj_pickaxe Inventory` containing `wooden_pickaxe`, and `execute if block <table/pos> minecraft:crafting_table` over RCON.

## References

- `upstream/clojurecraft/src/clojurecraft/{game,plan,intent,memory,packet,sim,spec}.clj`; `docs/hickey.md` ("Goals are data; planning is a function; executing is open")
- `steve/src/lib/typecraft/protocol/packet-defs.ts:2326-2362, 2772-2784, 998-1003`; `typecraft/bot/placing.ts`; `typecraft/bot/crafting.ts`; `typecraft/bot/craft-window.test.ts`; `typecraft/window/windows.ts:60`
- `steve/src/lib/steve/steps.ts:138-170`; `lib/run-loop.ts:143-144, 365-372`; `lib/bot-utils.ts:1564-1680, 1780-1950, 2006-2220`; `tasks/portal/cast.ts:88-151`
- `ruststeve/src/bot/mod.rs:1853-1905`; `src/bot/inventory.rs:40-70`; `src/bot/crafting.rs`; `src/bot_utils.rs:824-935`; `CHANGES.md:124, 964, 1092, 1119-1121, 1213`
- Server jar `data/minecraft/recipe/{wooden_pickaxe,crafting_table,stick,oak_planks}.json`, `data/minecraft/tags/item/{planks,wooden_tool_materials,oak_logs}.json`; `reports/registries.json` `minecraft:menu` (`crafting` = 12)

## Open questions

- Does 26.1.2 send both `block-update`s (reference and destination) to the placer on success as well as rejection? ruststeve's `mod.rs:1876-1878` says no update comes back; `CHANGES.md:964` shows one on rejection. The design relies on the destination update; the step-8 recording settles it, and the fallback is a `:settle`-style poll of `block-at` after the ack.
- `place-recipe` (39) would replace the click plan with one packet but needs the server's recipe ids from `update-recipes`/`recipe-book-add`; stay with clicks until a recipe fails under load.
- Should placed utility blocks be tagged in the sighting (`:block/placed-at`) so a far table is preferred over a stranger's? Not needed for one bot; revisit for the race.
- Cursor values: vanilla validates them in `[0,1]` relative to the block; confirm the server accepts `[0.5 1.0 0.5]` on face 1 rather than `0.999`.
