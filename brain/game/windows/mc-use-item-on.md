---
title: use-item-on
description: The 775 use-item-on packet (right-click a block face) - its fields, the sequence it shares with digging, and what the server answers for a placement, a rejection, and a container.
type: reference
tags: [game, windows, packets, placement]
aliases: [use_item_on, right-click block, place block packet, block placement packet, use-item-on 66]
status: verified
lastUpdated: 2026-10-09
verifiedAgainst: 26.1.2
sourceRefs:
  - src/clojurecraft/packet.clj#[:play :c2s :use-item-on]
  - resources/clojurecraft/packets.edn#:use-item-on 66
  - src/clojurecraft/place.clj#a rejected placement comes back as a block update with the old state
related:
  - "[[game/windows/_moc|Windows]]"
  - "[[place-intent]]"
  - "[[mc-bucket-use-item]]"
---

# use-item-on

## Facts
- Play c2s id 66 in 775. Fields: `hand varint, pos position, face varint, cursor-x f32, cursor-y f32, cursor-z f32, inside-block bool, world-border-hit bool, sequence varint`.
- `pos` is the block clicked and `face` the face clicked (0 down, 1 up, 2 north, 3 south, 4 west, 5 east); a placed block goes into `pos + face`. The cursor is the hit point inside that block, 0..1 per axis.
- `sequence` is the same per-player counter `player-action` uses; the server answers with `block-changed-ack` for it.
- Placement accepted: `block-update` for the new block, a slot update for the consumed item, the ack. Rejected: `block-update` restating the destination's current state, then the ack.
- On a container block (a crafting table) the same packet opens its screen: `open-screen` then `container-set-content` ([[mc-crafting-table-window]]).
- Buckets do **not** go through this packet in 775 ([[mc-bucket-use-item]]).

## Limits
The accept/reject answers are as the code and sim encode them and as observed in the live pickaxe run of 2026-10-09; other refusal reasons (entities in the cell, adventure mode) were not exercised.

## See also
- [[place-intent]] - our sender.
