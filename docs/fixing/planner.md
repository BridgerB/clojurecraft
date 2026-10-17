# Reference: the planner and every intent (section 19)

Part of the bot-failure manual; [AGENTS.md](../../AGENTS.md) is its entry and index. Read only the section a question needs.

## 19. Planner and intents

### 19.1 The planner tick (`plan/step`)

`plan/step` = `plan/step-over` on the shipped table (`resources/clojurecraft/goals.edn`), composed after `game/step`. `:go` calls `plan/begin`; `:tick` calls `plan/plan-tick` while `:plan/status :active`, `plan/landing-tick` while `:landing`.

**The table.** Rows `:goal/id :goal/priority :goal/needs :goal/provides :goal/done? :goal/act`, optional `:goal/plan :goal/holds :goal/spares :goal/blocks :goal/depth :goal/dimension :goal/always? :goal/until :goal/superseded-by`. Need keys `:item/<name> n`, `:tag/<item-tag> n`, `:block/<name> :near`, `:fuel/ticks n`. Targets have `:goal/target? true`; producers are every other row with an act, plus one generated row per recipe (`make/craft-row`, priority 10) and furnace recipe (`make/smelt-row`). Superseded rows are never chosen; `plan/current-id` maps a `:go` naming one to its replacement. Registries: `plan/done-by` (`:goal/done?`), `plan/act` (`:goal/act`, default waits `[:no-act <act>]`), `plan/next-intent` (`:goal/plan`, default `:needs`).

**One tick (`plan/plan-tick`), in order.**
1. `trap/trapped-tick` re-derives `:plan/trap` (only with `:player/pos`); `plan/reach` adds milestones on a chained run.
2. `plan/choose`: highest-priority target in play (`:plan/goals` or every target, plus `:goal/always?` rows), not in `:plan/reached`, `done-by` false, worked in this dimension (`here?`).
3. Running intent whose cursor holds an item, or with a click unanswered (`can-abandon?` false): run it anyway (it puts the item down, the answer comes).
4. No goal here but one undone elsewhere (`elsewhere?`): abandon `:goal-met`, wait `:other-dimension`.
5. No goal: abandon `:goal-met`, `:plan/status :done`.
6. Running intent for another goal than the chosen one: abandon `:preempted`, plan again this tick.
7. Running intent: `run-intent`; when it ends, `plan-tick` runs again this tick.
8. No intent: `next-intent`. nil waits `:nothing-to-do`; `{:plan/wait r}` waits `r`; else `start-intent` with `:intent/goal`.

**Needs netting (`make/decide`, `make/next-row`).**
- `decide` order: `wood/continue-gather`, a table pick-up in flight (`chain-next` for `:take-table`), `next-row`; `:stuck` becomes `{:plan/wait :stuck}`. Act waits: `:craft` `:no-table`; `:smelt` `:no-furnace`, `:no-fuel`.
- All needs resolve against one working copy of inventory counts (`recipe/consume`). `resolve-row`: `:goal/holds` first (resolved once, handed back unconsumed), then `:goal/spares`, then `:goal/needs` × runs.
- `resolve-item-need`: producers in `producer-order` (needs covered, no table, larger yield); `:stuck` past `make/max-depth` 12, on a cycle, or none doable. `craft-now`: a craft wanted n > 1 that can run once now runs first; smelts batch on purpose.
- `resolve-fuel-need`: held fuel in smelt order, then coal, then planks (planks first when `wood-covers?`, logs less `log-reserve` 1). `resolve-block-need`: `make/near` within `table-search` 24, not blacklisted.
- Spares: worn tools don't count (`without-worn`: < `spare-uses` 40 digs left). `spare-worth?` refuses `:stuck`, any `:mine` act, and a `:gather` that would only wait `:no-log` or walk up an `:opening`.
- `take-table`: a row that fetches (`#{:gather :mine :scoop}` or `:stuck`) with no table held and the placed one within reach digs it to carry it (#39, #67).

**Waits (`plan/wait`).** Counted in planner ticks, not wall ms: `plan/wait-ticks` 400 (20 s at 20 tps) in `:plan/waiting-ticks`; then the plan fails with the wait's reason. A starved loop (9 ticks in 21 s on a loaded runner, #57) ran out a wall-clock wait before tick-counted recovery could act. Only `begin` and `start-intent` clear the wait clock, so consecutive waits with different reasons share it.

**Failure (`plan/fail-intent`).**
- Every failure: fact recorded, controls cleared, `:plan/last` dropped. Target into `:plan/blacklist`, except `plan/placing-kinds` `#{:place :redig}` (#43). A log intent's `:intent/trunk` counts in `:plan/trunk-failures`; `wood/max-trunk-failures` 2 gives the trunk up.
- `plan/boxing-kinds` `#{:stairs-down :tunnel}` failing `:boxed` add the feet cell to `:plan/boxed-at`; a `:stairs-up` does not (only `stairs/box`, `stairs/duck` record a climb's cell).
- `max-attempts` 3 consecutive failures fail the plan; a `:walk` `:no-path` counts in `:plan/unwalkable` instead (`max-unwalkable` 12); a success resets both. `:plan/reason` = last `:intent/reason` or the wait reason.

**Landing.** A `:go` with `:go/at` starts in `:landing`; `landed?` wants feet within `landing-reach` 2.0 on the plane, chunk loaded; `landing-timeout` 30000 ms fails `:no-landing`.

**Chained runs.** `--until X` plays `plan/through X`: every live target with a `:goal/until` (no escapes, no `:wade`) at or below X's priority, so `--until nether` starts at wood. With `:plan/chain? true`, `plan/reach` puts every holding target into `:plan/reached` for the run (`:plan/reached-after` ms). A reached step is never asked again; a broken tool is remade by the needs of the step that uses it. A gym stage (one target) keeps re-deriving.

**Preemption.** `:escape-water`/`:escape-lava`: priority 0, `:goal/always? true`, done by `:not-trapped-in`, planned by `:escape` only while `:plan/trap` is their kind. `abandon-intent`: fact `:abandoned`, controls cleared, `:plan/last` dropped, open container closed.

**Composition.** `intent/run-child` runs `:intent/child` as `:plan/intent` for one tick, then restores. `intent/chain-next last for drops extra`: after a `:walk` for `for`, a `:dig` of its target; after that dig, a `:collect` with drops; only links with the same `:intent/for` continue.

### 19.2 Walk (`intent/run :walk`)

To within `intent/reach` 4.0 (eye to block centre) of `:intent/target`; route planned on first tick; time in ticks. Each tick: arrived → look, done; past `walk-timeout-ticks` 1200 (60 s) → `:stuck`; no waypoints → `route!` (`:none` with none fails `:no-path`); `route-stale?` → replan, or `:no-path` after `max-replans` 6; route ended short → press straight at target; waypoint reached → advance `:intent/at`; else `follow`.
- `walk-goal`: `:near` range `goal-range` 3; `:pickup` (`pickup-range` 1.5, `pickup-rise` 2) when `:intent/pickup?`, plus `:goal/step-down?`; `:intent/for :scoop` → `:pickup` over the source, `scoop-range` 1, rise 1; `:build` → `:block`; `:opening` → `:sky`.
- `arrived?`: `:log` also wants feet ≤ `pickup-rise` below; `:scoop` feet level with or one under the cell over the source; `:build` `centred?` (within 0.25); `:opening` under open sky.
- `waypoint-reached?`: within 0.35 on the plane and 0.5 in height; one below counts once feet are down. `follow` jumps when the waypoint is above within 1.3, or when blocked. A walk ending in water is preempted by the escape.
- `route-stale?`: a waypoint ahead no longer standable; none reached for `stuck-ticks` 40; or `:partial`/`:none` with every waypoint behind. A new chunk is NOT a replan reason (it once burned six replans).
- `routable?`: acts must ask it before choosing a target. True for `:found`; `:partial`/`:none` only when heading toward a target in an unloaded chunk.

### 19.3 Dig (`intent/run :dig`, `intent/dig-stage`)

Stages `:settle` → `:digging` → `:confirm`, absolute times against `:time/now`. Run as a child by stairs, swim-out, build, redig, place niche.
- `:settle` refusals, in order: block `intent/open?` (air, water or lava only; an awkward block is dug, #91) → `:target-gone`; eye > 4.5 from centre → `:out-of-reach`; `choose-tool` `:needs-tool`; block in `intent/never-dug` `#{:obsidian}` or `exposes-fluid?` (fluid in any of six neighbours, or `fluid-over-falling?`: gravel/sand over it within 16 with fluid on top, or `bed-over-fluid?`: gravel/sand beside it resting on a fluid, #37) without `:intent/exposes-ok?` → `:unsafe`.
- Start after 3 `still?` ticks and `settle-ms` 150, or `settle-deadline` 2000. `still?` = horizontal speed < 0.05, on ground or afloat. Sends `set-carried-item`, `player-action :start-destroy` (`face-toward`), `swing`; `:intent/finish-at` = now + `finish-delay`.
- `:digging`: tool gone from slot → `:tool-broke`; swing every 350 ms; at `finish-at` send `:stop-destroy`, go `:confirm` with `confirm-ticks` 60.
- `:confirm`: block differs from START state → done. Ticks out and FINISH acked → `:rejected`. Ticks out with no ack at all → done, cell predicted air (`terrain/predict`, `:world/unconfirmed`, dropped after `terrain/unconfirmed-ms` 3000).
- `finish-delay` = `ms × 1.15 × slowdown + 100`; `confirm-ticks` also × `intent/slowdown` (`:server/tick-ms` / 50, ≥ 1, from `game/clocked` on set-time spacing; #64, #88, #144: a 130 ms/tick server left blocks standing). 3550 ms for a log by hand, 1442 for stone with a wooden pickaxe at 20 tps. FINISH is never early (property test).
- `dig-time`: `dig/ms`, 3000 for unknown blocks; penalties `:off-ground?`, `:eyes-in-water?` each divide speed by 5. `dig/best-tool`: fastest hotbar tool of the block's own kind that harvests it; stone by hand drops nothing (7500 ms).
- Only hotbar slots: callers equip a stowed tool first (`intent/stowed-tool` + `:equip`): stairs `dig-next`, mine act, place niche. The ack alone is never the break (#59, #64, #66).

### 19.4 Collect (`intent/run :collect`)

Walk onto the drop until one more of `:intent/drops` than `:intent/drops-before` is held (no drops → counts logs). No packet: vanilla picks up on contact.
- Goal point: `nearest-item` within 6 of the block centre, else the centre; progress beats best by 0.1. Past `collect-timeout` 10000 ms → `:not-picked-up` (clock restarts per resumed collect).
- Done, not failed (no attempt, no blacklist): stalled > `collect-stall-ticks` 20 with `blocker` naming a leaf (probes 0.8 ahead, head then feet) → `:intent/blocked-by`; `ledge-ahead?` (open cell 0.6 ahead, no landing within `path/max-fall`) → drop left (was `:ledge`, #93).

### 19.5 Gather chain (`wood/gather-next`)

Walk, dig, collect, each `:intent/for :log`, chained from `:plan/last`. `:wood` uses it directly, `:kit` via `:gather`.
1. `continue-gather` (only `:log`-tagged): walk → dig same target; dig → `:intent/resume` or collect; collect with `:intent/blocked-by` and < `max-clears` 6 → dig the leaf, `:intent/clears` + 1.
2. `near-walk`: nearest standing trunk within `search-radius` 48, ±12 of the eye (`memory/nearest-log`), skipping blacklisted logs and failed trunks; first routable of 3 nearest. Target = `trunk-target` (log nearest feet height, up to `max-trunk` 8 up).
3. `climb-out`: trunks within `far-radius` 256: walk to the first routable; else (not `stairs/out?`, feet not in `:plan/boxed-at`) `intent/opening`, else `:stairs-up` to top of world, `:intent/for :surface`.
4. `hunt` (under open sky): a walk `:intent/for :log-hunt` via `scoop/hunt-targets`/`first-walk`. Logs remembered within `far-radius`: toward the ground nearest the nearest log, and may walk back toward the landing. None remembered: sets out anyway in the water hunt's order (#57). nil underground or with no routable ground.
5. `crossing` (#87): under open sky, nothing else reaching a tree, the nearest trunk across open water → `:cross` (swum at the surface, jump held; done on dry land once wet and 8 nearer; stuck in the water, a child `:swim-out :retreat` to the nearest bank, #57).
6. `{:plan/wait :no-log}`.
- The `:log` tag exists so a walk to a table is never continued as a dig. A trunk no route reaches is passed over, never walked at (#75). Stairs only once a trunk is remembered, unreachable, and the shaft cannot be walked up. `stairs/out?` is false in a pit (`stairs/pit-rim`; #54, #56, #58, #63, #102-#104).

### 19.6 Stairs and tunnel (`stairs.clj`)

`:stairs-down` (`:intent/to-y`) cuts one stair at a time, never digs the block under the feet; `:stairs-up` the same upward; `:tunnel` level steps only, done after `:intent/length`. Stages are methods of `stairs-stage`.
1. `:plan`: `airborne?` (not on ground, not in liquid) waits (#65, #107). Heading `:intent/dir` (default +x). Cells `safe?` and not in `:intent/visited` → `:open`; else `lay` gives cells → `:lay`; else turn a quarter; at `max-turns` 4, `step-aside`.
2. `:lay`: child `:place` of a `filler` per cell; refused or no filler → `refuse`.
3. `:open`: child dig of the topmost solid cell (`dig-next` equips a stowed tool or fails via `tool-missing`). Child `:target-gone` counts done (`fell?`); child in `refusals` `#{:needs-tool :unsafe}` → turn; `:rejected` → dig again up to `max-rejections` 3, then `:rejected`; any other child reason fails the staircase.
4. `:step`: walk `onto` the new feet cell. `taken?`: half a block past start in the stair's direction, `centred?`, on ground going up. Stair cell solid again (gravel) → `:open`. `step-timeout-ticks` 200 (10 s) → `:stair-not-taken`.
5. `:level`: child digs of level cells, walk until feet in the cell; 200-tick clock restarts per dig. A child in `refusals` (gravel fallen in under water after the step was chosen, #171) is `level-refused`: a tunnel plans again, a staircase turns.
6. Done: down at/below `to-y`; up on ground at `to-y`, or `out?` unless `:intent/to-height?`.
- Cells: `stair-cells` head-up, head, new feet one down; `up-cells` over head, new head, new feet one up; `level-cells` head and feet one along.
- `clear-way?`: no opened cell water, lava or unloaded; no lava in any `exposed` cell; new feet not in `:plan/boxed-at`. `safe?` adds a solid floor. Water beside a stair is allowed, lava is not; a cell that itself borders water is refused by the dig (`:unsafe`) and the stair turns.
- `level-safe?`: stair rules, no opened cell bordering fluid, not `too-hard?`. `tunnel-plan`: heading when `level-safe?`, else one side kept in `:intent/side`, never back; `:boxed` when neither.
- `lay`/`floor-laying`: floor against `place/support`, else a holder first; never over a fluid (`over-fluid?`, `bridge-depth` 16; #62). `fillers`: cobblestone, self-dropping stones, dirt; never gravel or sand (#37).
- `onto` creeps under `creep-speed` 0.06 when nothing beyond stops the body; a stair must end centred or it becomes a shaft.
- `step-aside`: never straight back; keeps `:intent/level-dir`. Going up, ranked by `shore-distance` (dry top within 48; lake ice is not shore), `dry-head-distance` (`wet-head?` y+1..y+3, #61), toward `:plan/go-at`; not `under-falling?` first (#43); visited cells last. `:boxed` when none allowed or `max-level-steps` 32 spent (plus plane distance to landing for a climb).
- `duck` (climbs): no fresh level step → a stair down along the heading whose head dries soonest; feet into `:plan/boxed-at` (#37). `box`: `:stairs-up` `:intent/for :surface` ends done and records its feet instead of failing (#61).
- Stairs up: `up-safe?` refuses gravel/sand over the cell above the head; `seal` lays filler into water beside the cells unless the water is over or under (#54); `step-lay` lays the step ahead (#61); `climb-to` cuts `climb-stairs` 8 at a time (#39).
- `out?`: under open sky, an open level step under sky, no `pit-rim` (`rim-reach` 8). Tools: going down or tunnelling, pickaxe-only rock with no pickaxe → `:no-pickaxe` (never dig on by hand at 7500 ms a stone); a stair up is cut by hand (`by-hand?`, #40); snow is dug by hand (#42, #72).

### 19.7 Mine act (`stone/mine-next`) and redig

| Row | Blocks | Holds | Depth |
|---|---|---|---|
| `:mine-stone` | stone | wooden pickaxe (by item) | none |
| `:mine-coal` | coal ore, deepslate coal ore | `:tag/pickaxes` | 40 |
| `:mine-iron` | iron ore, deepslate iron ore | stone pickaxe | 16 |
| `:dig-gravel` | gravel | any pickaxe + spare (#67) | 40 |

Order: (1) `continue-mine` (`chain-next` tagged with the row id, drops `drops-of-row`); (2) `tool-missing` → wait `:no-pickaxe` (gravel never waits); (3) tool only in main inventory → `:equip`; (4) `exposed-of-states`: row blocks within 24 of the eye and `search-height` 12 from loaded terrain (never memory), not blacklisted, with a `beside` cell; first routable of 3 nearest; walk `:intent/pickup? true` (+ `:intent/step-down?` when the stand is a block up); (5) `stairs` `stair-depth` 6 down, never below `:goal/depth`; (6) `tunnel-heading` → `:tunnel` of 12 (ranked: dry top 12 ahead, then farthest from landing); (7) `deeper`: 6 more ignoring row depth, bottom more than `floor-margin` 8 away; (8) `retreat`: in water `:swim-out :retreat`, else walk to the end of a `:found :away` route of 12; (9) `climb`: `:stairs-up :intent/for :boxed` unless `out?`; (10) wait `:boxed`. Steps 5-7 return nil from a cell in `:plan/boxed-at` (#105).
- `beside`: horizontal neighbour at the block's level or one lower, the cell under it, or (step-down) a neighbour one up. Never straight above. Floor, head and ceiling blocks count.
- Iron peaks at y 16; no coal below y 0.
- Redig (`:flint-from-gravel`): `plan/act :redig` wraps in `place/ashore`; `stone/link` stages place (`:intent/passage-ok? true`) → dig → collect, one intent (as three, the planner fetched more gravel instead). Gravel back → another redig, no counter. Child place `:no-spot` while afloat (`afloat-no-spot?`) → `:swim-out :retreat`, place again (#95).

### 19.8 Scoop (`scoop/scoop-next`, `intent/run :scoop`)

`:fill-bucket` holds and spares `{:tag/pickaxes 1}`; `take-table` precedes. Surface water only, never cave water. Order: (1) `sources`: plain sources open above, within 24, `surface-water?` (≤ `memory/surface-water-below` 5 under the eye, or under open sky), not blacklisted; first in reach and `sees?` → `:scoop`; (2) `first-walk :scoop` to the bank of the first of 3 routable; (3) remembered pools within `pool-radius` 96; (4) `seen-source` when `deep?`, scooped with `:intent/aim`; (5) `from-below`: source over the feet column under ≤ `bed-depth` 2 of rock, dug with `:intent/exposes-ok?`; (6) `climb` (`:intent/for :surface`) when deeper than `underground-depth` 8 under `:plan/go-at` and no open sky; (7) `first-walk :water-hunt` over `hunt-targets`: 8 bearings, ground `hunt-distance` 40 out, within +16/-24 of the eye, none nearer the landing; (8) `pit-climb` with `:intent/to-height?` (#60); (9) `to-bank` stairs toward the seen water's bank height, then `:tunnel` 8 (#60); (10) `dig-out`; (11) `tunnel-out` 8 along `stone/tunnel-heading`; (12) wait `:no-water`. `climb` and `pit-climb` wait while `to-bank` has water to cut toward (#69: they undid its stairs for 90 minutes).
- Intent: `:equip` (`:not-held`); `:aim` at `aim-point` (0.85 up the source), no longer scoopable → `:target-gone`, 3 still ticks + 300 ms → `use-item`; `:sent`: one more filled bucket → done, else re-aim after 3000 ms, `max-tries` 3 → `:no-scoop` (source blacklisted).
- `scoopable?` wants air over the source; `sees?` wants every ray cell air or water: a plant is a wall (#83). `first-walk` drops already-arrived targets. `boxed-here?`: no climb/pit-climb/dig-out from a `:plan/boxed-at` cell (#61). Only the inventory says the bucket filled.

### 19.9 Place, equip, open-container (`place.clj`)

Success only when the server shows the block. Given `:intent/cell` (build, stairs) is used; else a spot is found.
1. `:equip`: held → on; hotbar → `set-carried-item`; main → window-0 swap click (an open container closed first: the server ignores window-0 clicks while one is open); none → `:not-held`.
2. `:spot`: given cell with a `support` neighbour, else `:no-support`. Else `spot`, else `niche` (dug first, stowed tool equipped), else with `:intent/passage-ok?` a passage spot, else once `fresh-rock` (walk `fresh-rock-distance` 12 away via an `:away` route, or with no route `fresh-rock-climb` 4 stairs up; never in water; `:intent/retreated?`; #94, #148), else `:no-spot`.
3. `:settle`: target no longer replaceable → back to `:spot`; 3 still ticks + 300 ms → `use-item-on`.
4. `:sent`: block name at target → done. Acked (500 ms passed) with cell still replaceable and < `max-refusals` 3 → `give-up` (another spot; #106). Acked otherwise → `:rejected`. 3000 ms no answer → `:no-answer`.
- `spot`: rings 1-3 round the feet (levels 0, -1, +1), target replaceable and `terrain/confirmed?`, support solid and not a placed container, not `passage?`, not inside the player, within `place-reach` 4.0. Flowers are not replaceable.
- `niche`: wall cell at feet or head level, solid above and below, nothing falling over, no fluid beside, droppable by held tools, not obsidian or placed; side nearest the landing, never ahead (#94). `dig-niche` waits for the server's confirm (or 3000 ms after FINISH) before placing.
- `place/ashore`: in water, `:swim-out :retreat` first (#95). `:open-container`: `:out-of-reach` beyond `open-reach` 4.5, `:no-window` after 3000 ms.

### 19.10 Build (`build.clj`)

A build is a vector of ops with done-predicates as data (`build/holds?`); `next-op` works the first unsatisfied one; no progress is stored, so it resumes from the world after death or relaunch.
- Ops `:build/op` ∈ `:place :dig :pour :scoop :use-on :stand-in :verify`, with `:build/pos :build/stand :build/item :build/aim :build/done`. Predicates `[:solid p] [:not-solid p] [:firm p] [:filled p] [:block p name] [:lava-source p] [:no-water p] [:dimension name] [:or ...] [:and ...]`.
- `portal-plan origin facing h` (#124, the rock is the mould): `room-ops` (landing, room two rows six high, five-step stair, access steps to `h`, floors firm); `cell-ops` per frame cell in `layers` order (bottom up, NEVER sorted): walls firm, hole and cell over it dug, lava poured low, water poured into the cell directly over the lava, scooped back; then `interior-ops`, `light-op`, `enter-op`. `cast-one-plan`: room, first cell, `:verify`.
- `:build` intent per tick: `plan-for` (memoized), `next-op`, `work-op`. `op-child`: lava pour with no lava bucket → `refill`; water pour with other lava touching → `:stray-lava`; `:place` into `foundation` (up to depth 4) or `:no-support`; `:dig` with `:intent/exposes-ok?`; `:pour`/`:use-on` equip, aim, use, wait `:intent/expect`; `:stand-in` → `:await`; `:verify` → `:unverified`.
- A dig child failing `:target-gone` counts done (`found-open?`, #91). A place child `:rejected` with its cell inside the player is re-approached (`refused-for-the-body?`, #44).
- `approach-stand`: `reachable-stand` (refuses a stand whose feet or head is the worked cell, #44; also tries feet one under the cell), `room-stand`; with no route to any, cuts toward `nearest-stand-cell` (feet's height only, sorted by distance to the worked cell, line must have a diggable block; `stance-block` never takes frame rock; #74) via `toward-stand`.
- `work-op`: `:no-station` with a `spent?` pool ends the build done (new site chosen); a way child failing `:no-path`/`:stuck` beyond `site-reach` of the landing gives the site up and ends the build (`lost-way?`, `memory/give-up-site`; #222); any other keyword fails it.
- `choose-site`: beside the `max-sources` 3 nearest lava sources not within `give-up-radius` of a given-up site, any depth; `site-ok?` wants footprint loaded, no fluid in or touching it, pool not `spent?` (≥ as many reachable sources as cells left; #142); then fewest `blocks-needed` (≤ `site-blocks` 8), then nearest.
- `refill`: `station` (4 to `station-depth` 9 in front, within `station-span` 8 aside, sources in reach); scoop the nearest seen source, else dig `sight-block`; returns `:no-station`/`:no-bucket`.
- `plan/next-intent :build`: `held-first` (row's `:goal/holds` + cobblestone by `blocks-needed`; #70) via `make/decide`; then `:build` at the remembered site unless spent, else a chosen one; else `lava-hunt` (`lava-walk`, `uncap`, `toward-lava`; #41); else wait `:no-site`.
- Rules: never dig obsidian; water above the lava, never beside; landing reached at the lava's height (`:no-stand` otherwise); every worked cell in `footprint`; `kept` cells never dug for a way; a stand over water placed from shore (#45). `:no-effect` = pour/use-on with no effect in 3000 ms; `:not-yet` = `:await` past `await-ms` 10000.

### 19.11 Escape (`trap.clj`, `swim.clj`)

- Trap (`trap/trapped-tick`): lava on any contact (`:player/in-lava?`); water when `:plan/wet-ticks` (eye under) reaches `trap-after-ticks` 20. Wading never preempts. Clears after `dry-land?` (on ground, body dry, eye in air, solid non-lily-pad below) for `dry-ticks-to-clear` 6. On clearing, `{:trap/pos :trap/kind :trap/at}` joins `:world/traps`; `path/trap-factor` costs routes within 2 columns 10× for 600000 ms.
- `:swim-out`: `banks` within `bank-radius` 16, from 3 under to 1 over `float-top` (1.03 under an open surface), kept once picked. No bank: dig the cap (`obstacle`), else `notch` a wall within 4 (never under a roofed surface), else hold jump and look again every 20 ticks. A kept bank no longer standable (`flooded?`, water flowed in; #156, #157) is dropped and another picked. Stuck 40 ticks without 0.05 progress: dig `obstacle` (lily pad, head-height ahead, feet-height ahead when the bank is no higher than the feet, over the head; never feet-height ahead under a higher bank; #156); none → `:stuck`. Child digs via `dig-afloat` (jump held, #73) or `dig-or-sink` (sneak to the floor, `:intent/dig-when-down`: 5.75 s instead of 28 s). > `escape-timeout-ticks` 2400 → `:no-escape`. A `:retreat` swim-out ends itself on `dry-land?`.
- `:flee`: footing within 6, one up or down, no lava beside or under; none or > 200 ticks → `:no-footing`.
- `:wade` (gym `:shore`): entry over pools within 64 and 16 below; walk to bank, then head `wade-beyond` 4 ahead of the feet once wet. Fails `:no-water` (no entry, or 2400 ticks), `:shallow`.

### 19.12 Pathfinder (`path/plan`)

`(path/plan world from goal opts)` → `{:path/waypoints :path/cost :path/status :found|:partial|:none :path/from}`. Pure A*, budget in node expansions (`default-max-nodes` 6000) so replays match; `:partial` when spent, `:none` when the open set empties, both return the route to the nearest node seen.
- Classes (`classify-id`): `:water :lava :awkward` (shape taller than 0.125, or slime, ice, magma, cobweb) `:solid :clear :unknown`. One snow layer is `:clear` (#38: 39 bots froze). Powder snow is `:clear` and not solid: a player sinks through it (#87). `:unknown` is never floor nor clear: routes stop at the loaded edge.
- Moves: `:walk` 1, `:jump-up` 2, `:diagonal` √2 (both corners clear), `:drop` 1 + 0.5/block up to `max-fall` 3; `landing` refuses water, lava, awkward, unknown. `clear?` refuses lava in the four sides or below. Water is a wall, never a floor.
- `start-cell`: feet cell if standable, else nearest within one block (block-edge starts).
- Goals (`goal-done?`, `heuristic`): `:near` (3) `:block :xz :away :pickup` (`:goal/rise`, `:goal/step-down?`) `:sky` (`sky-weight` 4, not admissible).
- Never: parkour, digging, scaffolding.

### 19.13 Fortress tunnel (`fortress.clj`)

`:fortress` (Nether) done by `:at-fortress?`: a brick on record at y ≥ 32 within 6. `:tunnel-to` the nearest brick within 512, else explore 48 along the first safe axis; wait `:other-dimension` outside the Nether, `:no-way` nowhere safe. `step-plan`: floor under the new cell, `opens` dug, stand in it (worked by `build/next-op`). `safe?` refuses unloaded cells, opened lava/unbreakable cells, lava beside an opened cell, a floor neither solid nor lava. Vertical first when > 3 off. Done at target or after 32 steps. Failure `:blocked`.

### 19.14 Intentions as facts (`memory.clj`)

`plan/start-intent` numbers the intent (`:intent/id` from `:plan/intents`) and appends `:started`; then `:done`, `:failed` (with `:intention/reason`), or `:abandoned`. `memory/intentions` (oldest first); `memory/intention-as-of world t`: what it was doing at t (the tick before a death). Answers: `:ack` (`:answer/sequence`), `:pickup` (`:answer/entity :answer/count`), `:death`; read with `memory/answers`. Intents without `:intent/id` (hand-built in tests) are not recorded.

### 19.15 Survival and combat (`combat.clj`, `eat.clj`, `hunt.clj`, `armour.clj`, `sword.clj`, `entity.clj`; issues #258, #259)

- Entities (`entity.clj`): every `add-entity` is kept in `:world/entities` with `:entity/vel` (blocks/tick), `:entity/health` (metadata index 9), `:entity/flags` (index 0), `:entity/died-at`; death is `entity-event` status 3 or removal. `entity/hostiles` (monster category from the generated `mobs.edn`, minus enderman and zombified piglin), `entity/nearest`, `entity/gone?`. `:intent/target` may be an eid.
- `:fight` (intent): approach straight while it closes 0.1; after 20 ticks without progress a route to a cell within 2 (`combat/route-walk`, a child `:walk`); no route → `:unreachable`. Engage: look at the target (y+0.9 for a hoverer), `attack` then `swing` when `:time/now ≥ :intent/next-swing` (650 ms); below 10 health break off; `:ghost` after 25 swings in reach; 20 s with no attack → `:unreachable` (hold fights excepted); done when `entity/gone?`. Only targets in sight and within reach of the floor are fought (`combat/hittable?`).
- `:defend` (always row, priority 0 after the escapes): a hostile within 4 or a creeper within 6 preempts any plan; creepers are backed away from (`:back-off`), never fought.
- `:eat` (always row): below food 14, `use-item` held for the food's eating ticks (`food/eating-ticks`), done when food rises (`:not-eaten` otherwise). `:hunt` (always row): hungry with no food, kill the nearest animal a route reaches within 24 and pick up the meat.
- `:wear-armour` puts on anything wearable held (window-0 click into an armour slot); `:armour` (priority 13) makes an iron helmet and chestplate from ingots left after the portal kit; `:sword` (priority 13, chained runs only) makes the best sword without a trip.
- The planner never abandons an intent while one of its clicks is unanswered (`plan/can-abandon?`): a helmet left on the cursor once lost 8 ingots.

### 19.16 Past the Nether (`blaze.clj`, `pearls.clj`, `eyes.clj`, `stronghold.clj`, `drops.clj`; issues #262-#265)

- `:blaze-rods` (aside, 18): 7 rods; defend, fight the nearest blaze in sight, `:collect-drops` (done when one more is held or none is lying there), else `:camp` by or tunnel to the remembered spawner (spawners are watched landmarks, per dimension); hurt: eat, hide out of a blaze's sight (`path` `:unseen`), back off. Waits `:no-blaze`.
- `:ender-pearls` (aside, 19): 12 pearls by hunting endermen, never bartering (a kill drops 0-1: about 24 kills). `:lookout` stares at the nearest enderman in sight within 64; `:provoke` from beyond 4 blocks; `:burrow` (3 stairs down, a 2-high tunnel: an enderman stopped at the last stair cannot reach) then `:fight` holding. Waits `:no-enderman`, `:no-shelter`; fails `:unprovoked`. Open: reaching endermen on ledges 30-80 blocks off in a warped forest.
- `:eyes-of-ender` (aside, 20): 12 eyes through the 2x2 craft rows (blaze powder, then eyes); waits `:no-pearls` / `:no-rods`.
- Stronghold (#265, in progress): `stronghold/triangulate` (pure), `:throw-eye`, travel, the portal room.

---
