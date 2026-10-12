---
title: Buckets pour via use-item
description: In protocol 775 a bucket is used with the use-item packet (carrying yaw and pitch), aimed by the look; a scoop that way is seen to work on 26.1.2, and the server answers it with the whole inventory. That use-item-on with a bucket does nothing is the siblings' finding.
type: reference
tags: [game, buckets, packets]
aliases: [use-item vs use-item-on, bucket pour, lava bucket packet]
status: verified
lastUpdated: 2026-10-11
verifiedAgainst: 26.1.2
sourceRefs:
  - resources/clojurecraft/packets.edn#:use-item 67
  - resources/clojurecraft/packets.edn#:use-item-on 66
  - src/clojurecraft/scoop.clj#defn use-item
  - test/clojurecraft/packet_test.clj#a-worn-tool-does-not-hide-the-slots-after-it
related:
  - "[[game/mechanics/_moc|Mechanics]]"
  - "[[sib-portal-mold]]"
---

# Buckets pour via use-item

## Read in the 26.1.2 jar (#8)
`BucketItem.use` ray-casts with `ClipContext.Fluid.SOURCE_ONLY` for an empty bucket (it stops at the first water or lava source) and `NONE` for a filled one (it passes through every fluid and stops at a solid block; the fluid goes in the cell the hit face looks into, `BlockPos.relative`). `LiquidBlock.shouldSpreadLiquid` turns lava to obsidian (a source) or cobblestone (flowing) when water is in the cell above it or at one of its four sides: `POSSIBLE_FLOW_DIRECTIONS` is down, south, north, east, west, and each one's opposite is looked at. So a water pour aimed at the floor under a lava cup goes through the lava and replaces it, and a pour into the cell over the cup turns it to obsidian.

## Facts
- Research for issue #8 (portal cast) found that both siblings pour and scoop with `use-item` (id 67), which in 775 carries the player's yaw and pitch, aiming at a block face by look direction; `use-item-on` (66) with a bucket does nothing server-side. Flint and steel does use `use-item-on`.
- Seen on 26.1.2 (gym batch iron-a3 run 3 and live run 13, 2026-10-11, recorded): `use-item` with an empty bucket in hand, its yaw and pitch aimed at the surface of a water source in reach and in sight, is answered with a `block-update` turning the source to air, the `block-changed-ack` for its sequence, and a whole `container-set-content` for window 0 holding the water bucket. The whole window comes because the game's `useItem` sends all the menu's data after a use; a worn tool in it once hid the bucket from the bot ([[packet-specs]]).
- Consequence for our build plans: a pour op carries an aim, not a face.

## Limits
A pour has not been seen yet. That `use-item-on` with a bucket does nothing is the siblings' research, not tried here.

## See also
- [[sib-portal-mold]]
