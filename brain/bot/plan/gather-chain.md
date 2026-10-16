---
title: Gather chain
description: How any goal gets a log - walk, dig, collect tagged :intent/for :log, continued from :plan/last by wood/continue-gather, with up to six leaf digs to reach a drop - and why the tag exists.
type: reference
tags: [bot, plan, wood, intents]
aliases: [gather-next, continue-gather, intent/for :log, max-clears, log chain, wood chain]
status: verified
lastUpdated: 2026-10-15
verifiedAgainst: HEAD
sourceRefs:
  - src/clojurecraft/wood.clj#defn continue-gather
  - src/clojurecraft/wood.clj#defn gather-next
  - src/clojurecraft/wood.clj#defn way-out
  - src/clojurecraft/wood.clj#defn gather-or-out
  - test/clojurecraft/build_test.clj#a-pit-deep-under-the-landing-is-climbed-out-of-for-wood
  - src/clojurecraft/stairs.clj#defn pit-rim
  - test/clojurecraft/plan_test.clj#a-quarry-with-a-level-step-across-its-floor-is-climbed-out-of
  - test/clojurecraft/sim_test.clj#a-log-is-gathered-out-of-a-quarry
  - src/clojurecraft/wood.clj#defn near-walk
  - test/clojurecraft/plan_test.clj#a-near-trunk-with-no-route-is-climbed-for-too
  - src/clojurecraft/wood.clj#defn climb-out
  - test/clojurecraft/plan_test.clj#a-pit-under-the-sky-is-climbed-out-of
  - test/clojurecraft/plan_test.clj#a-pocket-under-a-canopy-is-climbed-out-of
  - test/clojurecraft/sim_test.clj#a-log-is-gathered-from-a-pocket-under-a-canopy
  - src/clojurecraft/stairs.clj#defn out?
  - src/clojurecraft/wood.clj#defn hunt
  - src/clojurecraft/wood.clj#defn into-water
  - test/clojurecraft/plan_test.clj#boxed-under-the-sea-the-bot-digs-up-into-it
  - test/clojurecraft/plan_test.clj#with-no-tree-known-the-wood-hunt-sets-out
  - src/clojurecraft/scoop.clj#defn hunt-targets
  - test/clojurecraft/plan_test.clj#a-hunt-that-knows-its-trees-walks-back-toward-the-landing
  - test/clojurecraft/plan_test.clj#with-no-route-to-any-trunk-the-bot-walks-toward-the-trees
  - src/clojurecraft/wood.clj#def max-clears 6
  - test/clojurecraft/sim_test.clj#a-low-canopy-is-cleared-to-reach-the-drop
  - test/clojurecraft/plan_test.clj#underground-with-no-log-the-bot-waits
  - test/clojurecraft/plan_test.clj#a-trunk-with-no-route-is-climbed-for
  - test/clojurecraft/plan_test.clj#a-trunk-above-the-band-is-walked-to
  - src/clojurecraft/intent.clj#defn opening
  - test/clojurecraft/plan_test.clj#a-shaft-to-the-sky-is-walked-not-cut
related:
  - "[[bot/plan/_moc|Plan]]"
  - "[[collect-intent]]"
  - "[[goals-and-intents]]"
  - "[[make-goals]]"
---

# Gather chain

Getting a log is a chain of three intents, each started by the planner after the previous one finished: walk to the trunk, dig the log, collect the drop. `wood.clj` owns it and any goal that needs logs reuses it (`:wood` directly, `:kit` through the recipe graph's `:gather`).

## Key files
- `wood.clj`, `gather-next` - continue the chain if one is in flight, else start toward the nearest remembered trunk bottom a route reaches (`near-walk`: `memory/nearest-log`, radius 48, no more than 12 above or below the eye, of the three nearest the first `intent/routable?` accepts) at the log nearest feet height (`trunk-target`). A trunk no route reaches is passed over, never walked at: sim race 38395033917 (world R3/24, 108 runs, issue #75) stood at the bottom of two staircases that met, the second having dug out the first's riser, with no way up; each walk to a trunk in the band failed `:no-path` the tick it started and blacklisted one trunk, twelve a second, until the plan failed. With none in that band a route reaches, and a trunk remembered further off, `climb-out`: walk to the nearest within 256 that `intent/routable?` accepts, under the open sky or not. Not out of the ground (`stairs/out?`: the open sky over the feet and a level step onto open ground beside them; a pit under the sky is not out), with none of those a route reaches, walk up the air already dug (`intent/opening`), and cut stairs only when that mouth cannot be walked to, `stairs/climb-to` a length at a time ([[stairs-down]]). Under the open sky with logs remembered and no route to any, `hunt`: a walk toward the trees the way a water hunt goes out (`scoop/hunt-targets`, the ground forty blocks out along eight bearings, never nearer the landing; [[scoop-intent]]), to the ground nearest the nearest remembered log that a route reaches, tagged `:intent/for :log-hunt`, so the chunks on the way are remembered and the next pass finds the way round. Race 38477665442 bots Clj_r14 and Clj_r20 landed on a lake's shore with the forest across the water, every trunk in the near band and no route to one, and waited `:no-log` until the plan failed (issue #57, `with-no-route-to-any-trunk-the-bot-walks-toward-the-trees`); bot Clj_r15 came up out of its mine for a spare pickaxe's wood with the trees a hundred blocks off, past the pathfinder's budget, and waited the same way (issue #87). With no log remembered at all within `far-radius` the hunt sets out all the same, its targets in the water hunt's order: race 38665691517 runner 1 (issue #57) landed slots Clj_r08, Clj_r14 and Clj_r15 where no standing trunk was in reach and routes led out on three bearings, and each waited `:no-log` at its landing round after round (`with-no-tree-known-the-wood-hunt-sets-out`). With logs remembered within `far-radius` the hunt may also walk a hop back toward the landing (`scoop/hunt-targets` with `homeward?`, ranked only by how near the trees it lands): race 38680745815 runner 5 (issue #57) bot Clj_r10 hunted a hop away from its landing before any trunk was remembered, and with the valley's trunks then known below it, every bearing back toward them was refused as nearer the landing, the one bearing left had no route, and the plan waited `:no-log` out its clock (`a-hunt-that-knows-its-trees-walks-back-toward-the-landing`). Race 38477665442 (issues #56, #58, #54): the stone mine dug the bot two deep beside a cliff, onto a ledge of its quarry, onto a lakeshore under a cliff; every trunk was up a wall no jump climbs, twelve walks failed `:no-path`, and the sky over the feet said there was nothing to cut (`a-pit-under-the-sky-is-climbed-out-of`). The same three rules cover a hole under an oak canopy, where every exit jump meets leaves (solid to a route) and the sky is still over the feet: race 38477665442 runner 10 Clj_r16 and the sim race in world R2/28 (issue #63, the sim bot below a snow-lined slope, #38's wall at the second log) failed twelve walks `:no-path` before them; `a-pocket-under-a-canopy-is-climbed-out-of` holds that stairs are cut, and sim test `a-log-is-gathered-from-a-pocket-under-a-canopy` that the bot gets out and takes the log. Nor is the floor of a quarry out, though a level step crosses it: race 38515209959 (issues #102 and #103, 30 real bots) and the sim race in worlds R3/36 and R3/32 stood at the bottom of the quarry the stone mine had dug round their feet, two or three deep and a few cells wide, the stairs they came down by eaten by the digs, with the trees seven blocks off and no route to any; `stairs/out?` said out for the sky and the step, the hunt had no route either, and each waited `:no-log` for the table's log. A pit is a floor with a wall every way (`stairs/pit-rim`: along each heading, within eight columns, one the feet cannot step into, and ground over the feet in at least one of them), and `out?` is never true in one (`a-quarry-with-a-level-step-across-its-floor-is-climbed-out-of`; sim test `a-log-is-gathered-out-of-a-quarry` cuts up out of it by hand and takes the log). Boxed under water (the feet in `:plan/boxed-at`, where a climb out was boxed) with water within `bed-reach` (4) over the head, `into-water`: the lowest solid cell over the head dug first, `:intent/exposes-ok?` (the dig lets the water in), never when a cell between would fall on the bot (gravel, sand), is lava, unloaded or never dug; then, nothing but air and water over the head, a `:swim-out` `:intent/for :rise` up to the water's top (`:intent/to-y`) and to the shore ([[water-escape]]), done on dry land in the open. Sim race 38806318231 runner 1 and 38811426215 runner 8 (issue #89, worlds R4/30, R4/21 and R1/32 at 76010d2, 146 runs) had bots mine under the sea floor until the last pickaxe broke; for a new one they needed a log, every climb out was `:boxed` under the water, no route led home, and each waited `:no-log` (`boxed-under-the-sea-the-bot-digs-up-into-it`). With no trunk remembered, `{:plan/wait :no-log}`. The `:gather` act (`make`'s `plan/act :gather`) is `gather-or-out`: when `gather-next` waits `:no-log`, the way out the water hunt takes first (`way-out`, never in water, which the escape owns: `scoop/pit-climb` out of a pit under the sky deep under the landing, or from a cell under rock where a climb out died `:boxed` a `stone/retreat` to new rock); `make/spare-worth?` still asks `gather-next` alone, so a spare never sends the bot out this way. Sim race 38843552659 (issue #186): world R1/18 stood on a ravine's floor at y 6, sixty under its landing, and world R4/39 under rock at y 47 where its climb out had died `:boxed`; both waited `:no-log` with trees remembered sixty blocks off (`a-pit-deep-under-the-landing-is-climbed-out-of-for-wood`).
- `wood.clj`, `continue-gather` - reads `:plan/last` (the intent that just finished); only intents tagged `:intent/for :log` continue. The plain links (walk → dig → collect) are `intent/chain-next`, the same chain a mining row's `continue-mine` follows ([[mine-act]]); wood adds the leaf clears and their resume.
- `wood.clj`, `max-clears` - at most 6 leaf digs per drop.

## How it works
| Last intent | Next |
|---|---|
| `:walk` (for :log) | `:dig` the same target |
| `:dig` (for :log) | its `:intent/resume` if it had one, else `:collect` the same target |
| `:collect` with `:intent/blocked-by` and fewer than 6 clears | `:dig` the blocking leaf, carrying `:intent/resume` = the same collect with `:intent/clears` + 1 |
| anything else | nil (start fresh) |

## Gotchas
- The tag exists so a walk for another purpose (to a crafting table) is never continued as a dig; before it, any finished walk or dig was assumed to be part of a log chain.
- The chain lives entirely in `:plan/last` and the intent maps; nothing else remembers it. A failed intent clears `:plan/last`, so a failure restarts the chain from a fresh search with the target blacklisted.
- The sim test drops a 2x2 oak canopy one block above the ground between bot and trunk; the bot breaks the leaf at `[3 65 0]` and finishes with no failed attempts.
- The near band is 12 above and below the eye, so from a mine every surface tree is past it. Stairs are cut only once a trunk is remembered, no route reaches it, and the shaft already dug cannot be walked up. Full run 29ef069 cut one at spawn before any tree was remembered, failed `:stair-not-taken`, and the plan then waited `:no-log`. Full run 4528031 remembered the trees, found no route through the mine, cut a new stair from y 17, and the only pickaxe broke at y 49 (`a-shaft-to-the-sky-is-walked-not-cut`). `:mine-iron` keeps a spare stone pickaxe and gathers the wood for it while one still digs.

## See also
- [[collect-intent]] - where a blocker is named.
- [[any-log-species]] - why the chain takes any log.
