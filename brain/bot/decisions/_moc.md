---
title: Decisions
type: moc
tags: [bot, decisions]
status: verified
lastUpdated: 2026-10-10
verifiedAgainst: 9c46e4b
related:
  - "[[bot/_moc|Bot pillar]]"
---

# Decisions

- [[from-scratch-protocol]] - no MCProtocolLib; packets as data instead.
- [[natural-tree-not-fixture]] - CI chops a real tree in a normal world, not a setblock on a flat world.
- [[facts-in-datascript]] - memory is append-only observation facts in a DataScript value, queried with Datalog (replacing the plain map).
- [[randomness-on-the-tick]] - why `rand` is an event field and not a call.
- [[manual-clicks-not-place-recipe]] - clicks we compute, not the recipe book; needs a decoder we lack.
- [[predict-nothing]] - clicks claim no changes and an empty cursor; the server stays the only truth.
- [[one-stack-per-ingredient]] - each ingredient from one stack chosen up front, never cell-by-cell.
- [[one-item-per-cell]] - one craft per intent, taken by one verified shift-click.
- [[grid-in-its-own-attribute]] - the 2x2 grid is not inventory, and not dropped either.
- [[goals-in-play-from-go]] - which goals run arrives on the :go event, so replays match.
- [[placement-judged-by-server]] - a placed block exists only when the server says so.
- [[spot-two-blocks-away]] - where a table goes: rings 1-3 around the feet, ±1 in height, never inside the player (and why the first design failed in CI).
- [[public-by-default]] - defn everywhere; defn- only for one-line local aliases, so the REPL reaches everything.
- [[one-planner]] - the recipe-only walk was removed once the needs planner covered it.
- [[any-log-species]] - gather the nearest log of any kind; the next plan picks the planks recipe.

## See also
- [[bot/_moc|Bot pillar]]
