---
title: Gather chain
description: How any goal gets a log - walk, dig, collect tagged :intent/for :log, continued from :plan/last by wood/continue-gather, with up to six leaf digs to reach a drop - and why the tag exists.
type: reference
tags: [bot, plan, wood, intents]
aliases: [gather-next, continue-gather, intent/for :log, max-clears, log chain, wood chain]
status: verified
lastUpdated: 2026-10-14
verifiedAgainst: 5be14e7
sourceRefs:
  - src/clojurecraft/wood.clj#defn continue-gather
  - src/clojurecraft/wood.clj#defn gather-next
  - src/clojurecraft/wood.clj#defn near-walk
  - test/clojurecraft/plan_test.clj#a-near-trunk-with-no-route-is-climbed-for-too
  - src/clojurecraft/wood.clj#defn climb-out
  - test/clojurecraft/plan_test.clj#a-pit-under-the-sky-is-climbed-out-of
  - src/clojurecraft/stairs.clj#defn out?
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
- `wood.clj`, `gather-next` - continue the chain if one is in flight, else start toward the nearest remembered trunk bottom a route reaches (`near-walk`: `memory/nearest-log`, radius 48, no more than 12 above or below the eye, of the three nearest the first `intent/routable?` accepts) at the log nearest feet height (`trunk-target`). A trunk no route reaches is passed over, never walked at: sim race 38395033917 (world R3/24, 108 runs, issue #75) stood at the bottom of two staircases that met, the second having dug out the first's riser, with no way up; each walk to a trunk in the band failed `:no-path` the tick it started and blacklisted one trunk, twelve a second, until the plan failed. With none in that band a route reaches, and a trunk remembered further off, `climb-out`: walk to the nearest within 256 that `intent/routable?` accepts, under the open sky or not. Not out of the ground (`stairs/out?`: the open sky over the feet and a level step onto open ground beside them; a pit under the sky is not out), with none of those a route reaches, walk up the air already dug (`intent/opening`), and cut stairs only when that mouth cannot be walked to, `stairs/climb-to` a length at a time ([[stairs-down]]). Race 38477665442 (issues #56, #58, #54): the stone mine dug the bot two deep beside a cliff, onto a ledge of its quarry, onto a lakeshore under a cliff; every trunk was up a wall no jump climbs, twelve walks failed `:no-path`, and the sky over the feet said there was nothing to cut (`a-pit-under-the-sky-is-climbed-out-of`). With no trunk remembered, `{:plan/wait :no-log}`.
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
