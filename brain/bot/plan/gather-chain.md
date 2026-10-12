---
title: Gather chain
description: How any goal gets a log - walk, dig, collect tagged :intent/for :log, continued from :plan/last by wood/continue-gather, with up to six leaf digs to reach a drop - and why the tag exists.
type: reference
tags: [bot, plan, wood, intents]
aliases: [gather-next, continue-gather, intent/for :log, max-clears, log chain, wood chain]
status: verified
lastUpdated: 2026-10-12
verifiedAgainst: 5be14e7
sourceRefs:
  - src/clojurecraft/wood.clj#defn continue-gather
  - src/clojurecraft/wood.clj#defn gather-next
  - src/clojurecraft/wood.clj#defn climb-out
  - src/clojurecraft/wood.clj#def max-clears 6
  - test/clojurecraft/sim_test.clj#a-low-canopy-is-cleared-to-reach-the-drop
  - test/clojurecraft/plan_test.clj#underground-with-no-log-the-bot-climbs-out
  - test/clojurecraft/plan_test.clj#a-trunk-above-the-band-is-walked-to
related:
  - "[[bot/plan/_moc|Plan]]"
  - "[[collect-intent]]"
  - "[[goals-and-intents]]"
  - "[[make-goals]]"
---

# Gather chain

Getting a log is a chain of three intents, each started by the planner after the previous one finished: walk to the trunk, dig the log, collect the drop. `wood.clj` owns it and any goal that needs logs reuses it (`:wood` directly, `:kit` through the recipe graph's `:gather`).

## Key files
- `wood.clj`, `gather-next` - continue the chain if one is in flight, else start toward the nearest remembered trunk bottom (`memory/nearest-log`, radius 48, no more than 12 above or below the eye) at the log nearest feet height (`trunk-target`). With none in that band and the sky not open over the feet, `climb-out`: walk to the nearest standing trunk within 256 that a route or its waypoints reach (`way-home?`), else `{:intent/kind :stairs-up :intent/to-y terrain/top-y :intent/for :surface}`. Under the open sky, `{:plan/wait :no-log}`.
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
- The near band is 12 above and below the eye, so from a mine every surface tree is past it. Full run 5be14e7 stood at y 16 with a wooden pickaxe and one plank after the stone pickaxe wore out, waited `:no-log`, and the plan failed. `way-home?` accepts a `:partial` route through loaded ground, which `routable?` refuses, because the way back up the tunnel can be longer than one search. A wooden pickaxe cannot cut that stone.

## See also
- [[collect-intent]] - where a blocker is named.
- [[any-log-species]] - why the chain takes any log.
