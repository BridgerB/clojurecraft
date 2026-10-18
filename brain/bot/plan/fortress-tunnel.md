---
title: Fortress tunnel
description: How the bot reaches a nether fortress (issue #11) - bricks as passive sightings in the Nether, a tunnel one step at a time where each step is a small plan of ops, and the rule that no step opens a cell beside lava.
type: reference
tags: [bot, plan, nether, fortress]
aliases: [fortress.clj, :tunnel-to, :fortress, step-plan, safe?, at-fortress?]
status: verified
lastUpdated: 2026-10-18
verifiedAgainst: HEAD
sourceRefs:
  - src/clojurecraft/fortress.clj#defn step-plan
  - src/clojurecraft/fortress.clj#defmethod build/holds? :landed-centred
  - test/clojurecraft/fortress_test.clj#a-tunnel-step-lands-in-the-middle-before-counting-it
  - test/clojurecraft/fortress_test.clj#a-fortress-tunnel-makes-a-pickaxe-from-held-supplies
  - test/clojurecraft/fortress_test.clj#a-tunnel-replans-when-its-pickaxe-breaks
  - test/clojurecraft/fortress_test.clj#a-tunnel-with-no-replacement-clears-slow-rock-by-hand
  - src/clojurecraft/fortress.clj#defn opens
  - src/clojurecraft/fortress.clj#defn safe?
  - src/clojurecraft/fortress.clj#defn dry?
  - src/clojurecraft/fortress.clj#defn moves
  - src/clojurecraft/fortress.clj#defn next-move
  - test/clojurecraft/fortress_test.clj#a-tunnel-turns-onto-ground-instead-of-pacing-in-the-portal
  - src/clojurecraft/fortress.clj#def brick-states
  - src/clojurecraft/fortress.clj#defn bricks
  - test/clojurecraft/fortress_test.clj#fortress-bricks-use-the-indexed-current-sightings
  - src/clojurecraft/fortress.clj#defmethod intent/run :tunnel-to
  - src/clojurecraft/fortress.clj#defmethod plan/next-intent :fortress
  - src/clojurecraft/fortress.clj#defn exploring
  - src/clojurecraft/fortress.clj#defn leg-over?
  - src/clojurecraft/fortress.clj#defn looked
  - src/clojurecraft/fortress.clj#defn leg-target
  - src/clojurecraft/fortress.clj#defn explore-y
  - src/clojurecraft/fortress.clj#defn shallow?
  - src/clojurecraft/fortress.clj#defn bridge-ok?
  - test/clojurecraft/fortress_test.clj#the-spiral-turns-right-and-its-legs-grow
  - test/clojurecraft/fortress_test.clj#a-leg-keeps-to-the-heights-over-the-lava-sea-and-under-the-roof
  - test/clojurecraft/fortress_test.clj#the-first-leg-goes-east-and-the-edge-of-the-world-turns-it
  - test/clojurecraft/fortress_test.clj#a-leg-goes-on-until-its-end-then-the-spiral-turns
  - test/clojurecraft/fortress_test.clj#a-leg-that-gains-ground-is-not-idle
  - test/clojurecraft/fortress_test.clj#an-exploring-tunnel-keeps-its-leg-for-the-planner
  - test/clojurecraft/fortress_test.clj#a-cavern-floor-is-bridged-only-over-ground-a-fall-survives
  - src/clojurecraft/fortress.clj#defmethod plan/done-by :at-fortress?
  - src/clojurecraft/fortress.clj#defmethod plan/done-by :nether-access?
  - src/clojurecraft/fortress.clj#defmethod plan/next-intent :return-nether
  - test/clojurecraft/fortress_test.clj#a-reached-nether-entry-does-not-strand-an-unfinished-fortress
  - src/clojurecraft/build.clj#defn work-op
  - src/clojurecraft/plan.clj#defn elsewhere?
  - test/clojurecraft/fortress_test.clj#no-step-opens-a-cell-beside-lava
  - test/clojurecraft/fortress_test.clj#no-tunnel-step-drops-a-fluid-bearing-bed
  - test/clojurecraft/fortress_test.clj#a-tunnel-drops-an-unsafe-in-flight-step
  - test/clojurecraft/fortress_test.clj#a-tunnel-replaces-a-solid-floor-the-route-cannot-walk
  - test/clojurecraft/fortress_test.clj#a-tunnel-floors-with-carried-stone-after-cobblestone-is-spent
  - test/clojurecraft/fortress_test.clj#a-tunnel-resumes-a-reachable-entry-after-a-no-path
  - src/clojurecraft/stairs.clj#defn filler
  - test/clojurecraft/fortress_test.clj#no-step-opens-a-spawner
  - src/clojurecraft/fortress.clj#defn arrived?
  - src/clojurecraft/fortress.clj#def aside
  - src/clojurecraft/fortress.clj#defn off-step?
  - test/clojurecraft/fortress_test.clj#a-step-the-feet-left-is-chosen-again
  - test/clojurecraft/sim_test.clj#the-fortress-from-a-nether-landing-and-its-recording-replays
related:
  - "[[bot/plan/_moc|Plan]]"
  - "[[build-plan]]"
  - "[[memory-sightings]]"
  - "[[mc-dimensions]]"
---

# Fortress tunnel

The `:fortress` target (`goals.edn`, `:goal/dimension "minecraft:the_nether"`) is done when a fortress block on record (`nether_bricks`, its fence or stairs, at y 32 or more, so never one under the lava sea) is within 6 blocks of the feet. Its planner (`plan/next-intent :fortress`) starts a `:tunnel-to` (not stone's `:tunnel`, which digs along a direction for ore) to the nearest one on record, or, with none, the next leg of a spiral search (below).

`bricks` asks `memory/positions-now` for `brick-states`, the indexed current sightings remembered by the facts' identity, before filtering the height; it never scans all sightings with `memory/latest`. Issue #328's snapshot, once streamed chunks restored its dug room ([[server-model]]), could tunnel but exhausted the judge's four-minute budget in that full scan on every tick. The indexed query preserves the dimension and recency rules without repeating the whole-memory query.

## A step is a plan of ops
`step-plan feet move` is a vector of [[build-plan]] ops, a pure function of where the step starts and its `[dx dy dz]`: place a floor under the cell stepped into (it may be lava: a placement replaces it), dig the cells the body passes through (`opens`: the two it ends in, plus the head's cell on a step down or the cell over the head on a step up), and land near the middle of the new cell (`[:landed-centred to]`, the plan's goal). `build/next-op` and `build/work-op` work it exactly as a `:build` works the portal, so the dig, place and walk children are the ones the portal uses. The `:tunnel-to` intent chooses a move when a step starts (`:intent/move`, `:intent/from`), works the step until the bot stands in it, stops its controls, counts it, and stops at the target or after 32 steps, so the planner can choose again from what the new chunks showed.

Issue #339's real snapshot (`real-7-s1-Clj_r10`) ran past a descending step's repaired floor and fell into lava with no footing. A feet-cell match alone ended steps before centring or landing, leaving forward controls pressed; the walk also kept accelerating over a descending waypoint while waiting to fall to its height. The [[walk-intent]] stops horizontal input over that waypoint, and the tunnel waits for both centring and ground contact. In-flight steps re-read the same goal, replacing the older `[:feet to]` op rather than preserving its premature completion.

Issue #296 exhausted the last pickaxe and retried the same tunnel until three `:no-pickaxe` failures ended the plan. The fortress planner now makes a replacement when `build/pickaxe-at-hand?` finds a non-fetching producer, before starting another tunnel. An in-flight tunnel that loses its tool ends without spending a failed attempt, so that supply planning can run again. With no replacement at hand, the shared op executor lets a tunnel dig even slow rock by hand: the Nether may have no wood for a replacement, and quartz ore must not turn that state into an immediate dead end. Portal builds retain their hand-dig time limit; tunnel lava and protected-block rules are unchanged.

Issue #410's R1/34 and R2/39 snapshots spent every cobblestone floor block while carrying other safe filler, then failed `:not-held` three times. Before working a floor placement, the tunnel selects `stairs/filler` from the current inventory: cobblestone first, then the carried stones and dirt, never falling gravel or sand. A changed material changes the op, so `build/work-op` drops even an already failed placement child and equips the available filler; portal build materials and tunnel safety rules stay unchanged.

## Exploring: a spiral, not a line
With no brick on record the tunnel used to aim 48 blocks east, then 48 more, for ever, and drifted down a block whenever the level step was refused, into the bedrock. Sim race 39095704051 runner 4 (issue #338, world R4/33) went 630 blocks east along y 2-8 with a fortress 150 blocks north of its line; the snapshots of R2/4 and R3/21 stood in pockets of the bedrock layer or at the east edge of the captured region (the model's limit: nothing is loaded past it). `exploring` now returns a search, `:fortress/search` = {`:search/from` `:search/heading` `:search/leg` `:search/tunnels` `:search/best` `:search/idle`}, and the planner's tunnel carries it (`:intent/search`; the `:tunnel-to` executor keeps it on the world, so an eat or a fight between two looks forgets nothing).

- **Legs of a square spiral.** The first leg goes east, each next one a quarter turn right (`turn-right`: east, south, west, north), `leg-length` blocks long: 160 for the first two (`explore-spacing`: a server shows 96 either side of the feet, `nether-view` 6 chunks), then each pair one spacing longer, so the strips swept lie a spacing apart and nothing is swept twice. The target is the leg's end (`leg-target`) at the height `explore-y` of where the leg began, the feet's own kept within y 52 to 90 (`explore-band`: over the lava sea at y 31 and its lakes, under the roof), so a tunnel in the bedrock climbs (a target more than 3 above sends the steps up first) and one that drifted into the roof comes down.
- **A leg is over** (`leg-over?`) when its end is within reach by the ground, when the world ends two blocks ahead (`open-ahead?`, `look-ahead`: a real server shows 96 blocks, so only the model's captured Nether ends in front of a tunnel, and a leg that cannot go the way it turns tries the next heading), when more tunnels were spent on it than its length asks, when the feet are farther from where it began than its length and 64 (another trip's), or when it is idle: at each look of the planner (`looked`) the feet must have gained `idle-gain` (4) blocks along the heading beyond the best so far, and `idle-looks` (3) looks without that end the leg. Sim race 39104666000 (R2/4) spent 45 simulated minutes at a time pacing a cavern before a leg of the old kind ended.
- **A search already running is kept** while its leg goes on; with none (the first look, a death, a failed tunnel that forgot it) the first leg starts east at the feet.

## Bridging a cavern
Never entering an air floor was the rule from the first live run (below), and in a Nether cavern it left every step ahead refused: sim race 39099976057 runner 2 (R2/4) paced a ledge for half an hour at a time. A step may now lay its floor in the air (`safe?` with `bridge?`, the placement the step plan always had: `build/place-op floor`, taken only once the block is confirmed) when the air hangs over solid ground within `bridge-fall` (2) blocks (`shallow?`: never lava or water, never the void or a cell not loaded, so a fall costs no health) and a solid neighbour holds the block (`build/foundation`). `bridge-ok?` limits it to a step toward the target (never sideways or back: the first version wandered out over the cavern and laid 500 blocks in 170 minutes) while more than `bridge-reserve` (24) filler is carried (`stairs/carried-filler`: the last floor of a plain step failed `:not-held` with the run's filler gone). The executor asks the same question each tick, so a bridging step is not dropped as unsafe once chosen.

## Never beside lava
`safe?` refuses a step when a cell it opens or its floor is not loaded, a cell it opens is lava or a solid a tunnel never opens (obsidian, bedrock, a spawner: the blaze rods come from it, issue #262), a cell it opens has lava beside it (other than the floor, which is filled first), or the floor is neither solid nor lava a placed block can fill. A passable portal cell needs no dig and may be walked through; its frame remains protected. An air floor is never safe to enter: live run f1-5 stood at a cave's edge, slid off while placing one and fell forty blocks into the lava sea. A cell behind a wall cannot be capped before the wall is dug, and Nether lava flows a block every half second, so instead of capping (ruststeve's `nether_tunnel_step`) the tunnel goes round: `moves` tries the axis with farther to go, up or down first when the target is more than 3 off, then level, then the other axis, sideways, and finally backward.

Solid does not mean walkable: issue #284's two real snapshots chose soul-sand floors, which the route classifies as awkward, and failed the entry walk `:no-path`. Each step first digs a floor unless `[:walk-floor floor]` (`path/floor?`) already holds, then places cobblestone before entering. The dig's guard also holds after placement, so the repair cannot undo itself. A repair is safe only with replacement support, no protected block, and no fluid exposed by the dig. An in-flight step re-reads these ops too: its failed entry walk is dropped when the floor repair becomes the next op; an unsafe repair drops the step instead, choosing again from the current world.

The later R1/6 reproduction of #284 stood on soul sand while its repaired destination was already walkable. The [[pathfinder]] snapped the departure to an isolated gravel neighbour and returned no route. It now searches from the actual supported, clear feet cell without allowing awkward floors as waypoints. A tunnel with an already failed, empty-route `:no-path` entry drops that child only when a route now exists, retaining the step and stopping controls before retrying; an entry still unreachable, or a found route that could not be followed, remains a failure.

Issue #339's R1/5 and R2/23 snapshots opened gravel beds below lava, then repeatedly cleared the gravel falling into the headroom until lava reached the bot and no escape footing remained. `safe?` also checks `intent/fluid-over-falling?` and `intent/bed-over-fluid?` for every opened cell: a fluid-bearing roof or neighbouring bed must stay sealed. Before each body-cell dig and entry walk, as before a floor repair, the tunnel rechecks safety and drops an unsafe step and its child with controls stopped, so an in-flight step replans instead of finishing a stale dig.

Issue #286 exposed two false dead ends: R3/10 had a safe retreat behind the feet that `moves` omitted, and the real portal at [256 97 464] was passable but rejected. Merely adding those moves paced between the portal's two cells. `next-move` prefers cells not yet left by this tunnel (`:intent/tunnel-visited`). Before retreating it can floor a level turn onto adjoining ground: the air floor must have immediate placement support and another solid horizontal neighbour besides the floor under the feet, and `stairs/over-fluid?` must find no fluid below it. The step must satisfy the same lava and optional water rules with that floor filled. Controls stop when the step is chosen, and its ordinary floor op must be confirmed before the walk begins; this is a short corner onto ground, not permission to enter an air floor or extend a causeway over lava.

The later R4/12 and R4/4 reproductions of #286 were a different dead end: the [[server-model]] placed the arrival on a single gravel block suspended over lava, where no portal frame could fit. The model now requires the frame's footprint before choosing a natural arrival. The tunnel still refuses those unsupported steps; its lava rules are not relaxed to escape an impossible arrival.

## The planner across dimensions
A row with `:goal/dimension` is chosen only there (`plan/here?`). When no row can be chosen here but one is undone elsewhere (`elsewhere?`), the plan waits `:other-dimension` instead of ending, so `--until fortress` in the overworld does not report done, and a run toward `[:nether :fortress]` works the portal, then the fortress.

After a chained run has reached `:nether`, a death can respawn it in the overworld while Nether targets remain undone (issue #287). The always row `:return-nether` works the existing `:nether` build and its supply planner there. Its `:nether-access?` predicate checks the unfinished Nether targets in play, not merely the historical entry milestone: it does nothing before the first entry, in another dimension, or after those targets are reached. Milestones remain historical; a standalone fortress trial does not acquire a portal-building prerequisite.

## Reach and climb
A `:tunnel-to` is done at its target from `reach` 6 (`arrived?`, from the feet cell's centre), or from `:intent/reach` when the intent gives one; its steps go up or down first only past `climb-past` 3 blocks off, or past `:intent/climb` when given. The blaze rods' tunnel to a spawner goes into the open cell beside it on its floor (reach 0.5, climb 0), or to within 2 of it when none is known ([[blaze-rods]]): from 6 off, gym blaze-fortress-b stood in the wall four over the spawner, and with climb 3 blaze-fortress-c run 3 came up three under its floor, out of reach of every blaze and rod. A target straight over or under the feet used to leave no move at all; `moves` then tries every direction (`aside`).

## A step the feet left
A step's move and the cell it started from are kept on the intent until the step is stood in. When the feet are in none of its cells (`off-step?`: knocked back by a fireball, or moved by a fight or a cover walk between its ticks), or its walk back to the cell it started from fails `:no-path` (`lost-way?`), the step is dropped and chosen again from where the feet are, never failed. Gym blaze-fortress-h run 1 failed its tunnel into the spawner's room `:no-path` twice, and the plan with it (issue #262).

## Gotchas
- The sightings are passive: a brick is remembered when its chunk loads (`memory/landmark-kinds`), in the dimension it was seen in, so no scan and no line of sight.
- The gym row (`fortress`, `:gym/land :nether-fortress`) carves a pocket of netherrack 48 blocks west of `locate structure minecraft:fortress` at y 72 and judges with `execute as {name} at @s if dimension minecraft:the_nether` and a filtered clone that counts bricks within 6.

## In the overworld
The stronghold's portal room (issue #265, [[stronghold]]) is reached by the same tunnel with `:intent/dry? true`: `choose-move` then also wants `dry?`, no opened cell water or beside water, since the way down runs through aquifers; the Nether's steps never ask.
