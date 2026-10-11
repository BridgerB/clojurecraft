---
title: Collect intent
description: How :collect walks onto the drop until the bot holds one more of what it is for (:intent/drops: any log, or cobblestone) than when it began, how it detects a stall against leaves and ends naming the blocker (:intent/blocked-by) so the gather chain can dig it, and its 10 s timeout.
type: reference
tags: [bot, plan, intents, pickup]
aliases: [:collect, pickup intent, not-picked-up, nearest-item, blocked-by, blocker]
status: verified
lastUpdated: 2026-10-11
verifiedAgainst: HEAD
sourceRefs:
  - src/clojurecraft/intent.clj#defmethod run :collect
  - src/clojurecraft/intent.clj#defn blocker
  - src/clojurecraft/intent.clj#def collect-stall-ticks 20
  - src/clojurecraft/intent.clj#defn nearest-item
  - src/clojurecraft/game.clj#defmethod on-packet [:play :add-entity]
related:
  - "[[bot/plan/_moc|Plan]]"
  - "[[mc-dig-and-pickup]]"
  - "[[leaf-canopy-over-the-drop]]"
  - "[[gather-chain]]"
---

# Collect intent

`{:intent/kind :collect :intent/target [x y z] :intent/for :log :intent/drops #{log ids}}` follows a dig. It needs no packet of its own: vanilla picks an item up when the player's box reaches it ([[mc-dig-and-pickup]]), so the intent walks onto the drop.

## Key files
- `intent.clj`, `run :collect` - the logic below.
- `intent.clj`, `nearest-item` - the tracked entity nearest the target block's centre, within 6 blocks; with none, the goal point is the block centre.
- `intent.clj`, `blocker` - the leaf block in the way from the player toward the goal: it probes the cells 0.8 blocks ahead in x, in z and diagonally, at head then feet height, and returns the first that `blocks/leaves?` accepts. Only leaves; anything else is left to the pathfinder (issue #4).
- `game.clj`, `on-packet [:play :add-entity]` - only item entities (type 71) are tracked in `:world/entities`.

## How it works
1. Progress is horizontal distance to the goal beating the best so far by 0.1; its tick is `:intent/best-tick`.
2. Done when `inventory/held` of its `:intent/drops` is greater than `:intent/drops-before`, the count taken on the collect's first tick: one more of its drop than when it began. The wood chain's drops are every log item, the stone chain's cobblestone; a collect with no drops counts logs, as every collect did before stone (one that counted only logs never finished on cobblestone).
3. Stalled = horizontal collision and no progress for more than `collect-stall-ticks` (20, one second). If stalled and `blocker` finds a leaf, the intent ends **done** with `:intent/blocked-by [x y z]`; the gather chain then digs that leaf and resumes the same collect ([[gather-chain]]).
4. Failure `:not-picked-up` after `collect-timeout` (10 s) since the intent began.
5. Otherwise walk toward the goal until within 0.4 horizontally, then stand.

## Gotchas
- A blocked collect is reported as done, not failed, so it never counts an attempt or blacklists the trunk.
- The 10 s timeout counts from the first collect; a resumed collect is a new intent with a fresh clock.

## See also
- [[walk-intent]] - the same steering, with detours.
- [[leaf-canopy-over-the-drop]] - the failure this logic answers.
