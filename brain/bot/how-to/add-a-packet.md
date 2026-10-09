---
title: Add a packet
description: Add a packet spec and a handler as data plus one defmethod, without touching the loop or the socket.
type: how-to
tags: [bot, how-to, protocol]
aliases: [new packet, handle a packet, packet spec]
status: verified
lastUpdated: 2026-10-09
verifiedAgainst: 60b624e
sourceRefs:
  - src/clojurecraft/packet.clj#def specs
  - src/clojurecraft/game.clj#defmulti on-packet
  - test/clojurecraft/packet_test.clj#every-c2s-spec-roundtrips
related:
  - "[[bot/how-to/_moc|How-to]]"
  - "[[packet-specs]]"
---

# Add a packet

## Steps
1. Find the name and id in `resources/clojurecraft/packets.edn` (kebab-case of the vanilla name). If the id is missing, regenerate with `scripts/datagen.sh`.
2. Add a row to `packet/specs`: `[state dir name] [[field type] ...]`, field names from the wire, never `:name`.
3. For a server packet, add `(defmethod game/on-packet [:play :the-name] [world pkt] ...)` in the namespace that owns the concern; use `game/emit` to reply.
4. For a client packet, `game/emit` a map with `:packet/name` and the fields; the roundtrip property test covers every c2s spec automatically.
5. Teach `sim.clj` the packet if a test needs the server to react.
6. Update [[packet-specs]] or the area note if the mechanism changed; run `clojure -M:test` and `clojure -M:brain`.

## See also
- [[packet-specs]]
