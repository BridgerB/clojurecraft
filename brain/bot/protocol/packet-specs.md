---
title: Packet specs
description: Packets as maps with :packet/name, specs as a data table, ids generated from the vanilla reports, and what happens to a packet we do not model.
type: reference
tags: [bot, protocol, packets]
aliases: [packet table, packets.edn, decode encode, unknown packet]
status: verified
lastUpdated: 2026-10-11
verifiedAgainst: HEAD
sourceRefs:
  - test/clojurecraft/packet_test.clj#packet-specs-only-grow
  - src/clojurecraft/packet.clj#def specs
  - src/clojurecraft/packet.clj#def values
  - src/clojurecraft/packet.clj#defn decode
  - src/clojurecraft/packet.clj#defn encode
  - src/clojurecraft/packet.clj#def ids
  - dev/clojurecraft/datagen.clj#defn packets
  - src/clojurecraft/packet.clj#def component-codecs
  - src/clojurecraft/packet.clj#defn read-slot
  - test/clojurecraft/packet_test.clj#a-worn-tool-does-not-hide-the-slots-after-it
related:
  - "[[bot/protocol/_moc|Protocol]]"
  - "[[connection-phases]]"
  - "[[add-a-packet]]"
---

# Packet specs

A packet is a flat map with `:packet/name`; its other keys are the wire's own field names, unqualified, because the packet scopes them. `specs` maps `[state dir name]` to a vector of `[key type]` pairs; one interpreter reads and writes every packet from that table.

## Key files
- `packet.clj`, `specs` - the table (about fifty packets).
- `packet.clj`, `values` - the numbers some fields carry, by name (`value :hand :main-hand` is 0; `:dig`, `:face`, `:next-state`, `:client-command`, `:chat-mode`, `:main-hand`, `:particle-status`, `:game-type`), as the game's enums number them: code says what a field means and the packet still carries the number. A name the enum lacks throws.
- `packet.clj`, `decode` - reads only the listed fields and ignores the rest of the frame; that is how chunk packets skip block entities and light.
- `packet.clj`, `encode` - client-to-server only; throws if the spec is missing.
- `packet.clj`, `ids` - loaded from `resources/clojurecraft/packets.edn`.
- `datagen.clj`, `packets` - turns the vanilla `reports/packets.json` into that EDN (kebab-case names, `:s2c`/`:c2s`).

## How it works
1. Types are keywords for primitives (`:varint :f64 :string :uuid :position :bytes :rest :slot` ...), `[:vec T]` for a varint-counted sequence, or a vector of pairs for a struct.
2. A `:slot`'s components are read when their wire codec is known (`component-codecs`: damage, max damage, max stack size and repair cost are a varint, unbreakable is nothing, as the 26.1.2 jar's `DataComponents` registers them; type ids from `components.edn`, the `minecraft:data_component_type` registry) and kept as `:components {type value}`. A component of any other type cannot be stepped over: the slot is marked `:components?`, the decode of that packet stops and marks it `:truncated`; item id and count are still read. Before the codecs, a worn stone pickaxe ended every `container-set-content` it was in, and the game answers each `use-item` with the whole inventory: gym batch iron-a2 run 3 filled its bucket three times, the server took the water each time, and the bot never saw the water bucket behind the pickaxe and failed `:no-scoop`.
3. An id with no spec decodes to `{:packet/name :unknown :packet/id n :packet/known name-if-any}`; the reducer counts it under `:stats/unknown` and never throws.

## Specs only grow
`docs/hickey.md` (Accrete): "A packet spec only ever gains fields." `test/clojurecraft/packet_specs_ever.edn` holds every spec as it has ever been committed, and `packet-specs-only-grow` checks that each is still there with its recorded fields an unchanged prefix of today's (the decoder reads fields in wire order, so growing means appending), and that every spec today is in the history. Removing a field or changing its type fails it; appending one passes. To add a field, append it to the spec and to the history; to add a packet, add it to both.

## Gotchas
- Field keys must not be `:name`; see [[packet-name-collision]].
- `packets.edn` is generated; regenerate with `bb datagen`, never hand-edit.

## Limits
Compression and framing are in `conn.clj`, not here; see [[connection-phases]].

## See also
- [[add-a-packet]] - the recipe.
