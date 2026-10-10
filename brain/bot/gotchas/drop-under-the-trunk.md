---
title: Drop under the trunk
description: Symptom: the log breaks, the item spawns, collect times out twice. Cause: digging the bottom log while standing above it drops the item into a hole under the remaining trunk.
type: reference
tags: [bot, gotcha, wood, collect]
aliases: [not-picked-up, item under tree, feet height target]
status: verified
lastUpdated: 2026-10-09
verifiedAgainst: 60b624e
sourceRefs:
  - src/clojurecraft/wood.clj#defn trunk-target
  - test/clojurecraft/plan_test.clj#digs-the-log-at-feet-height
related:
  - "[[bot/gotchas/_moc|Gotchas]]"
  - "[[memory-sightings]]"
  - "[[gym-on-runners]]"
---

# Drop under the trunk

Symptom (first CI run): `intent :collect failed: :not-picked-up (attempt 1)`, again for attempt 2, success on the third log.

## Root cause
The bot stood two blocks above the tree's base. It dug the bottom log; the drop landed at the base, under two remaining logs. A player is 1.8 tall, so walking into that one-block hole is impossible, and the pickup box never reached the item.

## Fix
`trunk-target` picks the remembered log in the column whose height is closest to the bot's feet (lower on ties). The drop then lands on top of the logs below it, at feet height, inside pickup reach.

## See also
- [[memory-sightings]]
