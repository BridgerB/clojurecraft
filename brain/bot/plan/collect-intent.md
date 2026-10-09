---
title: Collect intent
description: How :collect walks onto the drop until any log is held, how it detects a stall against leaves and ends naming the blocker (:intent/blocked-by) so the gather chain can dig it, and its 10 s timeout.
type: reference
tags: [bot, plan, intents, pickup]
aliases: [:collect, pickup intent, not-picked-up, nearest-item, blocked-by, blocker]
status: verified
lastUpdated: 2026-10-09
verifiedAgainst: 5c7d6c1
sourceRefs:
  - src/clojurecraft/intent.clj#defmethod run :collect
  - src/clojurecraft/intent.clj#defn blocker
  - src/clojurecraft/intent.clj#def collect-stall-ticks 20
  - src/clojurecraft/intent.clj#defn- nearest-item
  - src/clojurecraft/game.clj#defmethod on-packet [:play :add-entity]
related:
  - "[[bot/plan/_moc|Plan]]"
  - "[[mc-dig-and-pickup]]"
  - "[[leaf-canopy-over-the-drop]]"
  - "[[gather-chain]]"
---

# Collect intent

`{:intent/kind :collect :intent/target [x y z] :intent/for :log}` follows a dig. It needs no packet of its own: vanilla picks an item up when the player's box reaches it ([[mc-dig-and-pickup]]), so the intent walks onto the drop.

## Key files
- `intent.clj`, `run :collect` - the logic below.
- `intent.clj`, `nearest-item` - the tracked entity nearest the target block's centre, within 6 blocks; with none, the goal point is the block centre.
- `intent.clj`, `blocker` - the leaf block in the way from the player toward the goal: it probes the cells 0.8 blocks ahead in x, in z and diagonally, at head then feet height, and returns the first that `blocks/leaves?` accepts. Only leaves; anything else is left to the pathfinder (issue #4).
- `game.clj`, `on-packet [:play :add-entity]` - only item entities (type 71) are tracked in `:world/entities`.

## How it works
1. Progress is horizontal distance to the goal beating the best so far by 0.1; its tick is `:intent/best-tick`.
2. Done when `game/logs-held` is positive.
3. Stalled = horizontal collision and no progress for more than `collect-stall-ticks` (20, one second). If stalled and `blocker` finds a leaf, the intent ends **done** with `:intent/blocked-by [x y z]`; the gather chain then digs that leaf and resumes the same collect ([[gather-chain]]).
4. Failure `:not-picked-up` after `collect-timeout` (10 s) since the intent began.
5. Otherwise walk toward the goal until within 0.4 horizontally, then stand.

## Gotchas
- "Done" means **any** log held, not "one more log than before". It works for `:wood` (zero to one) and for `:kit`, because the recipe graph crafts planks as soon as a log is held, so every gather starts from zero logs ([[make-goals]]).
- A blocked collect is reported as done, not failed, so it never counts an attempt or blacklists the trunk.
- The 10 s timeout counts from the first collect; a resumed collect is a new intent with a fresh clock.

## See also
- [[walk-intent]] - the same steering, with detours.
- [[leaf-canopy-over-the-drop]] - the failure this logic answers.
