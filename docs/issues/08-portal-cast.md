# Portal cast: obsidian from lava and water, the frame as a build plan in data, light it, step in

## Summary

Add a `build` namespace whose only job is to interpret a *build plan*: a vector of placement, dig, pour, scoop, use-on and verify ops, each carrying its own done-predicate as data. A pure `portal-plan` generates the proven template mold for a frame origin and facing; a generic `:build` intent walks the plan one op per tick, re-deriving every op's completion from `game/block-at` and sightings, so the cast resumes idempotently after a death, a chunk reload or a relaunch. `:light-portal` and `:enter-portal` are two more small intents, `:respawn`, `:use-item` and `:use-item-on` join `packet/specs`, and `sim` learns the three rules it needs (water on a lava source makes obsidian, fire in a complete frame makes `nether_portal`, 80 ticks in a portal changes dimension). Placing blocks, buckets (fill/refill) and the pathfinder are separate issues; this one consumes them.

Paths below: clojurecraft = this repo, steve = `/Users/bridger/Developer/mc/upstream/steve`, ruststeve = `/Users/bridger/Developer/mc/upstream/ruststeve`, memory = `/Users/bridger/.claude-personal/projects/-Users-bridger-Developer-mc/memory`.

## Why now / what the siblings learned

Both siblings spent weeks here and only ruststeve got through naturally. The whole record fits in a few facts; the point of this issue is to turn them into data and properties rather than scars in a 2,687-line task file.

**Cast, never mine.** steve `cast.ts:3-7`: "a fully-enclosed 1-block cup holds a lava source, then water poured into the block directly above flows down and turns it to obsidian ... The portal frame is cast bottom-up so each block sits on the (already solid) one below."

**The mold that works** is ruststeve `src/tasks/portal_mold.rs`. Header `:3-19`: frame cells `(bx+dx, by+dy, bz)` on the X/Y plane, bot works from +Z; `LAYERS` (`:34`) `[(1,0),(2,0)] [(0,1),(3,1)] [(0,2),(3,2)] [(0,3),(3,3)] [(1,4),(2,4)]`; per cell: floor `(x,Y-1,bz)`, cup walls N `(x,Y,bz-1)`, E/W `(x±1,Y,bz)`, S = the platform block `(x,Y,bz+1)`; bowl walls N/E/W at Y+1, bowl S = the bot's body. Stance: "every cell is poured from feet = cell.y+1, standing on the cell's own +Z cup wall ... the platform RISES one block per layer and nothing is ever placed at cell.y+2 above a cell that still needs pouring" (`:6-12`). Pours (`:384-395`, `:471-478`): lava aimed at the cup's NORTH WALL face `(x+0.5, y+0.3, bz-0.02)` because "the floor aim ... dropped lava into the bot's own feet" from the sneaking eye height 1.27; water aimed at the bowl's north wall `(x+0.5, y+1.5, bz-0.02)` because "a filled bucket's raycast ignores fluids, so a floor aim went straight through the lava source and REPLACED it with water". Then poll per tick and scoop the bowl "THE MOMENT the cup reads obsidian: water spreads one block per 5 ticks" (`:480-505`). Around it: a pad floor `fill_pad` (`:621`), a rim ring at pad level so spilled water never reaches the pool (`:115-135`), an east stair (`:718`), a 2-wide platform per layer placed in two passes "never placing into the bot's own cell" (`:670-713`), origin shifted up to 6 blocks until `footprint_lava == 0` because "a block placed into a lava source DESTROYS it" (`:77-105`), and `open_front` after 10/10 so the lighter can stand at `(x,by,bz+2)` (`:222-275`). Results: pool gym 7/8 PASS, first natural PASS 1374 s (memory `ruststeve-mold-cast-2026-09-28.md:11`); the lesson in one line: "Terraform to a template, then run a fixed script" (`:19`).

**Failure modes, each one a property we can state:**
- *Lava sea.* `ruststeve-portal-cast-diagnosis.md:24`: "The ONE remaining blocker — cast over a lava SEA ... the cast MECHANIC works" (proved by `cast-one.sh`), but over a rim-less sea no stance exists; the fix was siting the frame on dry ground and capping/sealing the scoop station (`portal_mold.rs:839-865`). Capping had its own bug: "CAP CAPPED THE SCOOP SOURCE" (`diagnosis:11`).
- *Lids.* `ruststeve-nether-loop-2026-09.md:49`: "REAL root cause of the feet-lava pours: LIDDED CUPS" (a solid block above the cup bends the ray onto the bot's feet); `:45` the water lid twin: "every pour must clear its own ray path right before firing".
- *Side-cast / open-cup.* `:47`: a lava source left in a cup whose +Z stand cell is open flows into the stand and kills the next approach; side-cast was the lidded-cell escape hatch. The mold made both obsolete.
- *Never y-sort.* steve `cast.ts:2510-2521`: "A y-sort interleaves the columns, so the bot leaps across the whole frame (off≈3) on every block and stalls"; the order is part of the design, not derived from positions.
- *Ghost blocks.* `portal_mold.rs:670-673` and steve `cast.ts:118-131`: a placement the server rejects stays solid in the client ("a locally-predicted GHOST block the bot then collided with"); the mirror image is a dig the server never completed (`:419-423`).
- *Stuck use.* steve `cast.ts:9-10`: "bot.activateItem() leaves bot.usingHeldItem stuck true, which silently blocks every subsequent bucket use" — `reliableUse` (`:112-154`) clears it.
- *Obsidian accidentally dug.* `nether-loop:47`: "top-row obsidian VANISHED → the pathfinder dug it" → `blocks_never_break`; ruststeve `dig_at` (`portal.rs:198-213`) refuses obsidian outright.
- *Frame origin drift.* `steve-cycle4-end-2026-10-04.md`: "anchor cell is the frame corner, unreachable once the left column is cast → new frames"; ruststeve persists the anchor to a file (`portal.rs:1401-1440`) so a partial frame is resumed, not orphaned.
- *Lighting and entry.* Fire goes on the top face of a bottom frame cell; stand with the hitbox clear of `z=bz+1` or burn (steve `cast.ts:2621-2628`); poll *any* interior cell for up to 60 ticks (`portal.rs:2799-2808`); the interior 2×3 and the approach must be pure air (`:2740-2745`); the bot's own portal needs no line of sight (`:2822-2830`); a nether portal teleports only after ~4 s standing inside (steve `enter.ts:44-62`).
- *Protocol facts on 775* (`nether-loop:49`): `use_item` carries yaw/pitch in the packet, so an aimed pour is authoritative; `use_item_on` with a bucket is a server no-op. Buckets pour with `use-item`; flint and steel lights with `use-item-on`.

Vanilla: frame 4×5, 10 obsidian without corners, interior 2×3; obsidian forms where water (source or flowing) touches a *lava source* (flowing lava gives cobblestone); a lava source state is the block's `level=0` state, which is `min-state` in `blocks/table` since `level` is lava's only property; the dimension change arrives as `:respawn` (`packets.edn` s2c 82).

## Design

**`build.clj`: a plan is data.** Ops are flat maps; every op carries `:build/done`, a tiny predicate language interpreted by one `satisfied?` over the world (`[:solid pos]`, `[:air pos]`, `[:block pos :obsidian]`, `[:fluid pos :lava-source]`, `[:no-fluid pos :water]`, `[:dimension "minecraft:the_nether"]`):

```clojure
{:build/op :place  :build/item :cobblestone :build/pos [x y z] :build/stand [sx sy sz] :build/done [:solid [x y z]]}
{:build/op :dig    :build/pos [x y z] :build/done [:air [x y z]]}
{:build/op :pour   :build/item :lava-bucket  :build/stand [x (inc y) (inc bz)] :build/sneak? true
                   :build/aim [(+ x 0.5) (+ y 0.3) (- bz 0.02)] :build/into [x y bz] :build/done [:fluid [x y bz] :lava-source]}
{:build/op :pour   :build/item :water-bucket :build/stand [x (inc y) (inc bz)] :build/sneak? true
                   :build/aim [(+ x 0.5) (+ y 1.5) (- bz 0.02)] :build/into [x (inc y) bz] :build/done [:block [x y bz] :obsidian]}
{:build/op :scoop  :build/item :bucket :build/at [x (inc y) bz] :build/done [:no-fluid [x (inc y) bz] :water]}
{:build/op :verify :build/pos [x y z] :build/block :obsidian}
{:build/op :use-on :build/item :flint-and-steel :build/pos [(+ bx 1) by bz] :build/face 1 :build/stand [(+ bx 1) by (+ bz 2)]
                   :build/done [:block [(+ bx 1) (+ by 1) bz] :nether-portal]}
{:build/op :stand-in :build/pos [(+ bx 1) (+ by 1) bz] :build/ticks 80 :build/done [:dimension "minecraft:the_nether"]}
```

Pours use `:build/aim`, not a face: the server raycasts from the eye along the yaw/pitch in `use-item`. `:build/face` belongs to `:use-on` only.

**`portal-plan`** `(portal-plan origin facing) → [op ...]` is pure and emits, in this fixed order: pad floor and rim; then per layer of `LAYERS`: stair step, front platform row, step-up, back platform row, and per cell: the five or seven wall placements (bowl E/W open toward the same-layer sibling, `portal_mold.rs:316-326`), a dig of any non-obsidian solid in the cup and bowl (never obsidian: the generator cannot emit one and the spec forbids it), lava pour, water pour, scoop, verify; after the top layer the `open_front` digs; then `:use-on` and `:stand-in`. Facing rotates the template; the origin is the frame's own `(bx,by,bz)`, stored under `:build/origin` so a resume re-derives the identical plan.

**The `:build` intent** (`defmethod intent/run :build`) holds `{:intent/plan plan :intent/at i}` and each tick finds the first op whose `:build/done` is not `satisfied?`, skipping everything already true: that is the whole resume story (a cast that succeeded is a `:verify` that is already true). An op runs as a tiny state machine in `:intent/stage` (`:approach` via the pathfinder issue's `:walk` sub-intent to `:build/stand`, `:settle` using the existing `still?` gate from `intent.clj:89`, `:act` emitting `set-carried-item` + `use-item`/`use-item-on`/`player-action` with the next `:bot/sequence`, `:await` with an absolute deadline). Nothing is predicted into `:world/blocks` for places or pours: the only truth is the server's `block-update`, which eliminates both ghost classes by construction; a dig keeps the existing `game/set-block` overlay. Lid invariant: before a pour the op's ray cells (`(x, y+1..y+2, bz..bz+1)`) are re-read and any non-obsidian solid becomes an inline `:dig`. Failure reasons are data: `[:need :lava-bucket]` lets the goal dispatch the bucket issue's refill intent and come back; `[:aim-miss into actual]` after three stances fails the intent so the planner re-chooses.

**Site selection** is a query, not a scan: `(site world)` ranks candidate origins near remembered lava-source sightings (`memory/watched?` gains `blocks/lava?`, `blocks/water?`) by: `footprint-lava = 0` over dx −2..6, dy −1..1, dz −2..3 (the capping lesson, `portal_mold.rs:53-67`); nearest source within 12 horizontal and ±3 vertical of `by` (steve's `anchor_max_d`/`anchor_dy_max`, `cast.ts:2225-2226`); at least one station cell with solid non-lava floor, air body, an open side onto a source and no lava ring (`station_ok`, `:766-783`); then fewest placements needed. Lava and water are in `blocks/passable-types` (`blocks.clj:17`), so physics walks into them today: `:lava?` must also become a hazard the walk intent refuses.

**Intents and packets.** `:light-portal` and `:enter-portal` are the last two ops of the same plan, but also stand-alone intents for the gym rows. `packet/specs` gains `[:play :c2s :use-item] [[:hand :varint] [:sequence :varint] [:yaw :f32] [:pitch :f32]]`, `[:play :c2s :use-item-on] [[:hand :varint] [:pos :position] [:face :varint] [:cursor-x :f32] [:cursor-y :f32] [:cursor-z :f32] [:inside-block :bool] [:world-border-hit :bool] [:sequence :varint]]`, and `[:play :s2c :respawn] [[:dimension-type :varint] [:dimension :string]]` (the decoder ignores the tail, `packet.clj:7-9`). `game/on-packet [:play :respawn]` assocs `:player/dimension`, drops `:world/chunks`, `:world/blocks`, `:world/entities`, and parks the old sightings under a new `:memory/dimensions {dim sightings}` so `:world/sightings` keeps its meaning.

**Sim.** `sim.clj` gains: `use-item` with a bucket raycasts from the (sneaking or not) eye to the first solid and places the fluid in the cell before it; a water cell next to a lava-source cell converts the lava to obsidian next tick; `use-item-on` face 1 of a bottom frame cell with flint and steel checks the 10-cell frame and 6 air interior cells and sets them to `nether_portal`; a player whose feet cell is `nether_portal` for 80 consecutive ticks gets `:respawn` with `"minecraft:the_nether"`, a teleport and a chunk. `spec.clj` gains `:build/op`, `::op`, `::plan`, `:build/done`, `:player/dimension`, and fdefs for `portal-plan`, `satisfied?` and `site`.

## Steps

1. Specs + packets: `use-item`, `use-item-on`, `respawn`; `:player/dimension`; sneaking eye height 1.27 and `:control/sneak?` in physics (depends on the placing issue). Verify: `packet_test` round-trips the three, `props_test` phase property still holds.
2. `blocks/lava?`, `lava-source?`, `water?`, `obsidian?`, `nether-portal?` from the table; sightings watch lava and water. Verify: unit tests on the generated ids.
3. `build.clj`: op specs, `satisfied?`, `portal-plan`, `site`. Verify: the geometry tests below pass with no server.
4. `:build` intent + `:light-portal` + `:enter-portal`. Verify: sim e2e below.
5. Sim rules (fluid raycast, obsidian, lighting, dimension). Verify: `sim_test` scenarios for a miss (lava in the stand cell) and a lid.
6. Harness: `--until cast-one|portal|nether`; the fixture builds the arena and gives the kit over RCON exactly as `ruststeve/cast-one.sh` and `portal-test.sh` do (flat stone floor, `doFireTick false`, lava pit set into the floor, `give` lava_bucket/water_bucket/bucket/flint_and_steel/cobblestone 64). Every run `--record`ed.
7. CI rows in `.github/workflows`: `cast-one`, `build-nether-portal`, `enter-nether`.

## Acceptance criteria

- Unit: `portal-plan` for origin `[0 0 0]` facing +Z yields exactly the ten frame cells of `LAYERS` in that order, two pours per cell, every pour's `:build/stand` at `cell.y+1` on `(x, bz+1)`, and no `:dig` op whose target can be obsidian; rotating the facing is a bijection on positions.
- Property (test.check): for every origin and facing, no `:place` targets any op's own `:build/stand` cell or the cell above it; no op places a block at `cell.y+2` above a cell whose pours come later in the plan (no lids); cap/seal placements never target a station's scoop cells; the plan is a pure function (same input, identical vector).
- Sim e2e: from a kit of 2 lava buckets, 1 water bucket, 1 empty bucket, 64 cobblestone and flint and steel on the model, the `:build` intent ends with ten `:verify` ops true, six `nether_portal` cells, and `:player/dimension "minecraft:the_nether"`; a second run from the same recording with the sim pre-seeded with 6/10 obsidian touches only the remaining four cells; killing the bot mid-cast and respawning resumes at the first unsatisfied op.
- Live: `cast-one` on the local 25571 server with RCON-given buckets casts one obsidian within 90 s, judged by `execute if block X Y Z minecraft:obsidian` over `clojure -M:rcon`; `build-nether-portal` reaches 10/10 and `nether_portal`; `enter-nether` is judged by `data get entity <name> Dimension`.
- CI: three gym rows, each a replayable `.edn`.

## References

- clojurecraft: `src/clojurecraft/intent.clj:49-139` (dig emit pattern, `still?`), `game.clj:71-74, 148-151, 171-175` (block-at, set-block, on-packet), `plan.clj:12-27`, `memory.clj:13-29`, `sim.clj:55-72, 111-128`, `spec.clj:56-71`, `packet.clj:24-91`, `harness.clj:24-42`, `physics.clj:11-12`, `resources/clojurecraft/packets.edn` (`:use-item 67`, `:use-item-on 66`, `:respawn 82`).
- ruststeve: `src/tasks/portal_mold.rs` (whole file), `src/tasks/portal.rs:186-213, 1401-1440, 2740-2862`, `src/tasks/lava_move.rs:20-60`, `cast-one.sh`, `portal-test.sh`.
- steve: `src/lib/steve/tasks/portal/cast.ts:1-11, 88-154, 2501-2521, 2608-2665`, `enter.ts:14-83`, `LOOP.md:153-162`, `gym/registry.ts:350, 526`.
- memory: `ruststeve-portal-cast-diagnosis.md:11, 15, 24`, `ruststeve-mold-cast-2026-09-28.md:11-19`, `ruststeve-nether-loop-2026-09.md:17-49`, `steve-cycle4-end-2026-10-04.md`, `steve-cycle5-end-2026-10-09.md` (natural 0/63, arena 66/72, wall = refill reachability).

## Open questions

- Does 26.1.2 echo `block-update` to the actor for placements and `use-item` pours the way it does not for digs (`intent.clj:133`)? If not, `:await` needs a bounded re-read and the ghost classes return in a milder form.
- Field order of `use-item-on` (`world-border-hit` position) and `respawn` on 775 must be checked against the vanilla report before trusting the specs; `login` could also yield the dimension but its layout is longer.
- Sneaking: ruststeve's edge-guard bug (`mold-cast:13` item 1) means sneak physics needs its own test before the stance relies on it.
- Lava refill between layers is the bucket issue's job; what does the `:build` intent do when `[:need :lava-bucket]` recurs more than N times at one cell: fail the site or wait?
- Should `:memory/dimensions` be the first use of the Datalog store (`hickey.md` status), since "sightings as of the overworld" is exactly an as-of query?
