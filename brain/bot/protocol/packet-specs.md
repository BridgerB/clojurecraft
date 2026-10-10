---
title: Packet specs
description: Packets as maps with :packet/name, specs as a data table, ids generated from the vanilla reports, and what happens to a packet we do not model.
type: reference
tags: [bot, protocol, packets]
aliases: [packet table, packets.edn, decode encode, unknown packet]
status: verified
lastUpdated: 2026-10-09
verifiedAgainst: 60b624e
sourceRefs:
  - src/clojurecraft/packet.clj#def specs
  - src/clojurecraft/packet.clj#defn decode
  - src/clojurecraft/packet.clj#defn encode
  - src/clojurecraft/packet.clj#def ids
  - dev/clojurecraft/datagen.clj#defn packets
related:
  - "[[bot/protocol/_moc|Protocol]]"
  - "[[connection-phases]]"
  - "[[add-a-packet]]"
---

# Packet specs

A packet is a flat map with `:packet/name`; its other keys are the wire's own field names, unqualified, because the packet scopes them. `specs` maps `[state dir name]` to a vector of `[key type]` pairs; one interpreter reads and writes every packet from that table.

## Key files
- `packet.clj`, `specs` - the table (about fifty packets).
- `packet.clj`, `decode` - reads only the listed fields and ignores the rest of the frame; that is how chunk packets skip block entities and light.
- `packet.clj`, `encode` - client-to-server only; throws if the spec is missing.
- `packet.clj`, `ids` - loaded from `resources/clojurecraft/packets.edn`.
- `datagen.clj`, `packets` - turns the vanilla `reports/packets.json` into that EDN (kebab-case names, `:s2c`/`:c2s`).

## How it works
1. Types are keywords for primitives (`:varint :f64 :string :uuid :position :bytes :rest :slot` ...), `[:vec T]` for a varint-counted sequence, or a vector of pairs for a struct.
2. A `:slot` with components stops the decode of that packet and marks it `:truncated`; item id and count are still read.
3. An id with no spec decodes to `{:packet/name :unknown :packet/id n :packet/known name-if-any}`; the reducer counts it under `:stats/unknown` and never throws.

## Gotchas
- Field keys must not be `:name`; see [[packet-name-collision]].
- `packets.edn` is generated; regenerate with `scripts/datagen.sh`, never hand-edit.

## Limits
Compression and framing are in `conn.clj`, not here; see [[connection-phases]].

## See also
- [[add-a-packet]] - the recipe.
