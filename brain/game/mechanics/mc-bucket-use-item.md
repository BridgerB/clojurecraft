---
title: Buckets pour via use-item
description: In protocol 775 a bucket is used with the use-item packet (carrying yaw and pitch), and use-item-on with a bucket is a server no-op. Draft from sibling research.
type: reference
tags: [game, buckets, packets]
aliases: [use-item vs use-item-on, bucket pour, lava bucket packet]
status: draft
lastUpdated: 2026-10-09
verifiedAgainst: 26.1.2
sourceRefs:
  - resources/clojurecraft/packets.edn#:use-item 67
  - resources/clojurecraft/packets.edn#:use-item-on 66
related:
  - "[[game/mechanics/_moc|Mechanics]]"
  - "[[sib-portal-mold]]"
---

# Buckets pour via use-item

## Facts
- Research for issue #8 (portal cast) found that both siblings pour and scoop with `use-item` (id 67), which in 775 carries the player's yaw and pitch, aiming at a block face by look direction; `use-item-on` (66) with a bucket does nothing server-side. Flint and steel does use `use-item-on`.
- Consequence for our build plans: a pour op carries an aim, not a face.

## Limits
Draft until a live scoop or pour is observed in this repo. The `:use-item` spec exists (hand, sequence, y-rot, x-rot, field order read from `ServerboundUseItemPacket`), the `:scoop` intent sends it ([[scoop-intent]]), and the sim fills a bucket by ray along its look; neither is a run on the real server.

## See also
- [[sib-portal-mold]]
