# Crafting as data: planks, sticks and a crafting table from the 2x2 grid, then the recipe graph

## Summary

The bot can hold a log and nothing else. The next three goals (planks, sticks, a crafting-table item) all run through the player's own 2x2 grid in window 0, which means one new packet family (`container-click` with 26.x hashed slots, state ids, `container-close`), one new intent (`:craft`), a generated `recipes.edn` with tags resolved, and a crafting window in `sim.clj` so every click sequence is tested with no server. Both sibling bots lost race-weeks to this exact surface; the design below turns each of their scars into a rule the reducer cannot break.

## Why now / what the siblings learned

steve and ruststeve both drive crafting as an async method on a mutable bot object, with a client-side prediction of the window that drifts from the server under load. Everything they fixed is a symptom of that one braid.

- **Results stranded in the grid, invisible to the inventory read.** steve `src/lib/steve/lib/bot-utils.ts:1961-1966`: "A crafted result left in the grid is INVISIBLE to windowItems() — which only reads the inventory section — so the bot 'loses' furnaces/tables it actually holds and hot-spins". The memory note `steve-wood-lock-diagnosis.md` traces the `Craft Planks ⇄ Gather Wood` oscillation to the same undercount: logs sitting in the grid make `gather_wood.isComplete` flip false and preempt the craft. clojurecraft today has the identical hole: `game/container->player-slot` (`src/clojurecraft/game.clj:92-99`) returns nil for window-0 slots 0-4, so a result or ingredient in the grid vanishes from `:player/inventory`.
- **Taking the result with a stray item in the grid mints junk.** typecraft `src/lib/typecraft/bot/crafting.ts:349-352`: "GRID FIRST, result slot LAST: taking slot 0 while a stray plank still sits in the grid *crafts* that plank into a button". ruststeve CHANGES.md:1372: "The 2×2 grid ended up with one plank, so the output was a button." Rule for us: never click slot 0 unless the server has told us slot 0 holds exactly the expected item.
- **Clicks landing in a window the server already closed.** ruststeve CHANGES.md:1361 ("Stale-window fix (3968bcf): a 2×2 craft clicked into `active_window()`, i.e. a table window the server had closed... so the clicks were ignored") measured 0/6 vs 6/6 on the reproducing slug; typecraft `crafting.ts:142-149` closes any open window before a 2x2 craft. Rule: a 2x2 craft starts by emitting `container-close` for any `:window/open` and resets it on `respawn`/`login`.
- **Prediction drift.** ruststeve `src/bot/inventory.rs:50-58` sends an empty `changedSlots` on every click "so the server ALWAYS sees a prediction mismatch and replies with a full, AUTHORITATIVE container_set_content", and `inventory.rs:76-80` found replies lag one click if you return on the first packet. typecraft `bot/inventory.ts:361-369` abuses a stale `stateId` to force a full resend. We have no prediction to drift: the world only changes on `container-set-slot`/`container-set-content`, and the executor gates each click on the state id advancing.
- **Lag, not logic.** steve `LOOP.md:170-181`: 4/4 crafts succeed on a quiet server; race failures are the 30 s `withTimeout` tripping on sequential round-trips. Our deadline is a value in the intent (`:intent/finish-at`), and a slow server just means more ticks, never a cancelled promise.
- **Closing with a loaded cursor drops it on the ground** (`crafting.ts:363-366`, 14 planks lost). Rule: the take is a shift-click (mode 1) so the cursor is never loaded; the sim asserts the cursor is empty whenever `container-close` is emitted.
- **Recipe choice.** ruststeve CHANGES.md:280: picking the first recipe for `stick` chose the bamboo variant and stalled three bots. `recipes.edn` carries resolved tag sets, and the planner picks the recipe whose `:recipe/needs` the inventory covers.
- **Mixed plank species.** `bot-utils.ts:2093` and `crafting.rs:16-19`: a table needs 4 of one tag, and a cell-by-cell "any plank" fill grabbed the plank just placed. Our `craft/clicks` is a pure function of the inventory value that chooses one source stack per ingredient key up front.

## Design

**Data first: `resources/clojurecraft/recipes.edn` and `tags.edn`.** The vanilla recipe and tag JSON are not in `data/reports/reports/` (the `--reports` run writes only `blocks.json`, `packets.json`, `registries.json`); they live inside the bundled jar at `META-INF/versions/26.1.2/server-26.1.2.jar` under `data/minecraft/recipe/*.json` (1515 files) and `data/minecraft/tags/item/*.json`. `dev/clojurecraft/datagen.clj` gains `recipes` and `item-tags` readers over `java.util.zip` (no server run), keeps only `minecraft:crafting_shaped` and `minecraft:crafting_shapeless`, and resolves tags recursively (`#minecraft:logs` → `#minecraft:logs_that_burn` → `#minecraft:oak_logs` → items). The vanilla shape to mirror: shaped has `key` (char → item or `#tag` or a list) and `pattern` (rows); shapeless has `ingredients`; `result` has `id` and optional `count` (default 1). One row per recipe:

```clojure
{:recipe/id :stick
 :recipe/kind :shaped                       ; or :shapeless
 :recipe/pattern ["#" "#"]                  ; vanilla rows, verbatim; absent for shapeless
 :recipe/key {"#" #{:oak_planks :spruce_planks ...}}   ; tags resolved to item-name sets
 :recipe/ingredients [#{:oak_log :oak_wood :stripped_oak_log :stripped_oak_wood}]  ; shapeless only
 :recipe/needs {#{:oak_planks ...} 2}       ; ingredient set → count, derived
 :recipe/provides {:stick 4}
 :recipe/table? false}                      ; derived: pattern taller/wider than 2
```

Item names stay keywords (ids come from `blocks/items` at the edge), so the table is readable and the same row works for the planner's `needs → provides` graph and for the executor. First consumers: `oak_planks` (shapeless, 1 of `#oak_logs` → 4), `stick` (`["#" "#"]` → 4), `crafting_table` (`["##" "##"]` → 1). The recipe graph is just the table: a goal's unmet need is looked up by `provides`, which is how `:wooden-pickaxe` later expands to planks + sticks + a placed table without new code.

**`recipe.clj` (pure).** `by-result`, `fits-2x2?`, `affordable` (world recipe → the chosen source slot per key, or nil), `match` (grid value → result item, the vanilla rule: shaped patterns match at any offset, shapeless matches any arrangement), and `clicks` (world recipe → the click vector). Clicks are maps `{:click/slot 36 :click/button 0 :click/mode 0}`. The sequence for `stick` with 8 planks in hotbar slot 36: pick up the stack (36,0,0), right-click one into grid 1 (1,1,0) and grid 3 (3,1,0), put the remainder back (36,0,0), then, only after verification, shift-click the result (0,0,1). Exactly one item per cell so a shift-click yields exactly one craft (the 6-buttons incident in `bot-utils.ts:1990-1996` came from a drifted grid, not from the click).

**Packet specs** (added to `packet/specs`, ids already in `packets.edn`: `:container-click 18`, `:container-close 19`, `:place-recipe 39`, s2c `:open-screen 59`, `:container-close 17`, `:set-cursor-item 96`, `:set-held-slot 105`, `:recipe-book-add 74`). Field lists from typecraft `protocol/packet-defs.ts:2326-2351` and ruststeve `protocol-schema.json` (`packet_container_click`, `HashedSlot`), `ContainerID` is a varint in 775:

```clojure
[:play :c2s :container-click] [[:window-id :varint] [:state-id :varint] [:slot :i16] [:button :i8] [:mode :varint]
                               [:changed [:vec [[:slot :i16] [:item :hashed-slot]]]] [:cursor :hashed-slot]]
[:play :c2s :container-close]  [[:window-id :varint]]
[:play :c2s :place-recipe]     [[:window-id :varint] [:recipe-id :varint] [:make-all :bool]]
[:play :s2c :open-screen]      [[:window-id :varint] [:menu-type :varint] [:title :rest]]   ; title is NBT; last field
[:play :s2c :container-close]  [[:window-id :varint]]
[:play :s2c :set-cursor-item]  [[:item :slot]]
[:play :s2c :set-held-slot]    [[:slot :varint]]
[:play :s2c :recipe-book-add]  [[:data :rest]]                                             ; opaque, counted
```

`:hashed-slot` is a new writer in `packet.clj`: a bool prefix (the `option`), then `item :varint`, `count :varint`, `added [:vec [[:type :varint] [:hash :i32]]]`, `removed [:vec :varint]`. We always send `:changed []` and `:cursor nil` (the ruststeve rule): a mismatch makes the server send corrections, it never kicks. `container-set-content`/`container-set-slot` already decode `:state-id`; `game.clj` just drops it today.

**World attributes (accreted, nothing renamed).** `:window/state-id` (window 0's last state id), `:window/grid` ({0..4 item} — window-0 slots 0-4 kept verbatim, the slots `container->player-slot` discards), `:window/cursor`, `:window/open` ({:window/id :window/menu-type} from `open-screen`, dissoc'd on `container-close` in either direction and on `respawn`/`login`). `game/count-item` (world item-kw → n) reads `:player/inventory` only; `game/grid-item` reads the grid, so goals can see "a table is in the grid, reclaim it" instead of "no table".

**The `:craft` intent** lives in a new `craft.clj` (`defmethod intent/run :craft`): `{:intent/kind :craft :intent/recipe :stick :intent/stage :settle ...}`. Stages: `:settle` (emit `container-close` if `:window/open`, wait until `:window/state-id` is known and the grid is empty, else emit reclaim clicks for grid slots 4→1); `:click` (the precomputed vector, one click per tick, each only after `:window/state-id` passed the id the previous click carried, `:intent/finish-at` = now + 10 s); `:verify` (wait for `:window/grid` slot 0 = `:recipe/provides`; a different item → fail `:wrong-result`, with reclaim clicks first); `:take` (shift-click slot 0); done when `count-item` rose by the provided count. Fail reasons are data: `:no-ingredient`, `:grid-dirty`, `:wrong-result`, `:stale-window`, `:timeout`. The planner's blacklist key for crafts is the recipe id, not a position.

**Goals** in `plan/goals`, with two new keys the planner reads generically: `{:goal/id :planks :goal/priority 2 :goal/needs {:logs 1} :goal/provides {:planks 4}}`, `{:goal/id :sticks :goal/priority 3 :goal/needs {:planks 2} :goal/provides {:stick 4}}`, `{:goal/id :crafting-table :goal/priority 4 :goal/needs {:planks 4} :goal/provides {:crafting_table 1}}`. A default `goal-done?` for rows with `:goal/provides` is `count-item >= n`; `next-intent` for them is `{:intent/kind :craft :intent/recipe r}` when a recipe in `by-result` is affordable, `{:plan/wait :needs}` otherwise, which lets the existing `:wood` goal (priority 1, now parametrised to 2 logs via a new row `:wood-2`, the old `:wood` kept) satisfy the need. `:planks`/`:stick` as category keys map to tag sets in `recipe.clj` so birch counts.

**Sim.** `sim.clj` gains a window-0 model: `:sim/grid`, `:sim/cursor`, `:sim/state-id`, `:sim/open-window`, `:sim/inventory` (slot → item). `on-packet [:play :container-click]` applies vanilla rules as data: ignore a click whose `window-id` is not the open menu (no reply, as in typecraft's scripted server `bot/craft-window.test.ts:1-10`); apply mode 0 button 0/1 and mode 1; recompute slot 0 with `recipe/match`; bump the state id; reply with `container-set-slot` per changed slot, or a full `container-set-content` when the click carried a stale id. Fault knobs as input, not hidden state: `:sim/lag-ticks n` (replies delayed), `:sim/drop-clicks #{k}` (the k-th click is lost). `sim/run` already feeds `:go`; the end-to-end test gives one chunk with a 2-log trunk and stops at `plan/done?` for `:crafting-table`.

**Specs** (`spec.clj`): `:window/state-id int?`, `:window/grid (s/map-of #{0 1 2 3 4} ::item)`, `:intent/recipe keyword?`, `:intent/stage`, `::click`, `::recipe` (`:req [:recipe/id :recipe/kind :recipe/needs :recipe/provides :recipe/table?]`), `recipes-valid?` like `goals-valid?`, `:goal/needs`/`:goal/provides` as `(s/map-of keyword? pos-int?)`, and `:effect/packet` generators extended so `props_test` covers `container-click` encoding.

## Steps

1. **Datagen**: add `recipes`/`item-tags` to `dev/clojurecraft/datagen.clj` reading the inner jar; write `recipes.edn`, `tags.edn`. Check: `clojure -M:datagen` regenerates, the `stick` row equals the shape above, `(count recipes)` is the number of `crafting_shaped` + `crafting_shapeless` files, `#minecraft:logs` resolves to 36+ item names.
2. **`recipe.clj`** with unit tests: `match` on the three grids, offset invariance for `stick` (cells 1+3 and 2+4), `clicks` for mixed species picks one stack. Property: for every 2x2-able recipe, `(match (grid-after (clicks world r)))` = its result.
3. **Packet specs + `:hashed-slot`**: round-trip tests in `packet_test.clj`; `props_test` encode-never-throws for generated `container-click`.
4. **`game.clj` window attributes**: `inventory-one-key-space` test extended so slots 0-4 land in `:window/grid` and `:state-id` is kept; `open-screen`/`container-close` set and clear `:window/open`.
5. **`craft.clj` executor** with `plan_test`-style timelines: stick craft click order and state-id gating; a wrong slot-0 item → reclaim then `:wrong-result`; a lost click → retried after the ack deadline, never a blind take.
6. **Sim crafting window** + `sim_test`: log → planks → sticks → table item with `plan/done?`; property over `:sim/lag-ticks` 0..10 and any one dropped click: the bot never emits a slot-0 take unless the sim's slot 0 holds the expected item, and the final world has the table or an empty grid.
7. **Goals + `--until table`** in `main.clj` (`RESULT` gains `:planks :sticks :tables`); record a local run `data/runs/table.edn`, replay it to the same RESULT.
8. **CI**: `.github/workflows/wood.yml` gains a `table` job whose judge greps `data get entity Clj_table Inventory` for `minecraft:crafting_table` and `minecraft:stick`.

## Acceptance criteria

- `clojure -M:test` green with the new unit tests, the three properties (match/clicks round-trip, encode totality, no blind take under lag/drops) and `sim_test` end-to-end; no test opens a socket.
- `clojure -M:run --port 25571 --until table` prints `RESULT {:ok true ...}` on the local 26.1.2 server in under 120 s from `:go`, and `clojure -M:replay data/runs/table.edn` prints the same `:ok`.
- The CI gym job passes: RESULT `:ok true` and the independent RCON read shows `minecraft:crafting_table` and `minecraft:stick`.
- No `container-click` is ever sent while `:window/open` is set, and no `container-close` while `:window/cursor` is non-nil (asserted in the sim run).
- `resources/clojurecraft/*.edn` remain generated; no hand edits.

## References

- clojurecraft: `CLAUDE.md`, `docs/hickey.md`, `src/clojurecraft/{game,plan,intent,wood,packet,sim,memory,spec,main,blocks,record,harness}.clj`, `dev/clojurecraft/datagen.clj`, `scripts/datagen.sh`, `resources/clojurecraft/{packets,items}.edn`, `test/clojurecraft/{game_test,plan_test,sim_test,props_test,fixtures,world}.clj`, `.github/workflows/wood.yml`, `data/local-server/server-26.1.2.jar` (inner `META-INF/versions/26.1.2/server-26.1.2.jar`), `data/reports/reports/registries.json`.
- steve: `src/lib/steve/lib/bot-utils.ts` (1955-2200), `src/lib/typecraft/bot/crafting.ts`, `src/lib/typecraft/bot/inventory.ts` (40-130, 250-400), `src/lib/typecraft/bot/craft-window.test.ts`, `src/lib/typecraft/protocol/packet-defs.ts` (395-420, 998-1030, 1266-1290, 2326-2360, 2530-2540), `src/lib/typecraft/protocol/shared-types.ts` (1131-1215), `src/lib/typecraft/recipe/recipe.ts`, `src/lib/typecraft/data/recipes-raw/`, `LOOP.md` (160-228); memory `steve-wood-lock-diagnosis.md`, `race-to-nether-walls.md`.
- ruststeve: `src/tasks/craft.rs`, `src/bot/crafting.rs`, `src/bot/inventory.rs` (1-140), `src/bot/mod.rs` (1055-1110), `src/bot_utils.rs` (693-900), `src/recipe.rs`, `src/window.rs`, `src/protocol/data/protocol-schema.json`, `datagen/work/output/data/minecraft/{recipe,tags/item}/`, `CHANGES.md` (233, 280, 511-536, 1358-1372), `LOOP.md` (36, 188).

## Open questions

- **`place-recipe` instead of clicks?** The server would fill the grid from the inventory and we would take with one click. But `recipe-book-add` (the only source of the per-session recipe display ids `place-recipe` needs) is read as an opaque `restBuffer` by both siblings, and its `RecipeDisplay`/`SlotDisplay` type system is a real decoder; vanilla also places a ghost recipe when ingredients are missing. Proposed: ship manual clicks now, keep `:recipe/id` stable, and accrete `:intent/via :recipe-book` once `recipe-book-add` is decoded, with the sim modelling both.
- **State-id semantics.** My reading of vanilla since 1.17 is that a stale `state-id` does not reject the click; it only triggers a full resend (typecraft's comment at `inventory.ts:366` says "ignore"). The sim encodes "apply + full resend"; the first live recording decides which.
- **Slot components.** `packet/read-slot` marks any slot with a component patch as truncating, so a `container-set-content` whose items include a damaged tool loses every later slot. Not a blocker for planks/sticks/table (fresh items have no patch), but the wooden pickaxe goal needs real `Slot` component decoding; separate issue.
- **Shared matcher in the sim.** Using `recipe/match` on both sides makes the sim agree with the bot by construction; the live run and CI judge cover that gap, but a second independent matcher for the three bootstrap recipes in the sim is cheap insurance.
- **Grid reclaim after death.** `keep_inventory` returns the grid to the inventory on death in vanilla; the sim should drop the grid and reset `:window/open` on `respawn` once that packet is modelled.
