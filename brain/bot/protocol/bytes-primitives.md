---
title: Bytes primitives
description: The wire primitives in bytes.clj - varint/varlong sign handling, the packed block position bitfield, strings, UUIDs, offline UUID - and the tests that pin each encoding.
type: reference
tags: [bot, protocol, bytes]
aliases: [varint, varlong, position packing, pack-position, unpack-position, offline-uuid, wire types]
status: verified
lastUpdated: 2026-10-09
verifiedAgainst: 5c7d6c1
sourceRefs:
  - src/clojurecraft/bytes.clj#defn read-varint
  - src/clojurecraft/bytes.clj#defn write-varint
  - src/clojurecraft/bytes.clj#defn unpack-position
  - src/clojurecraft/bytes.clj#defn pack-position
  - src/clojurecraft/bytes.clj#defn offline-uuid
  - test/clojurecraft/bytes_test.clj#wiki example: x=18357644 y=831 z=-20882616 encodes as 0x4607632C15B4833F
related:
  - "[[bot/protocol/_moc|Protocol]]"
  - "[[packet-specs]]"
  - "[[mc-offline-uuid]]"
---

# Bytes primitives

Readers take a `java.nio.ByteBuffer` whose position is the cursor; writers take a `DataOutputStream`. Neither escapes the `decode`/`encode` call that owns it, so every public function is bytes in, value out. All multi-byte numbers are big-endian (`ByteBuffer` and `DataOutputStream` defaults).

## Key files
- `bytes.clj`, `read-varint` / `write-varint` - LEB128, 7 bits per byte, low first. Reads sign-extend to int32 (`unchecked-int`) and throw past 5 bytes; writes mask to 32 bits, so -1 is `ff ff ff ff 0f`.
- `bytes.clj`, `read-varlong` / `write-varlong` - the same over 64 bits, throwing past 10 bytes.
- `bytes.clj`, `pack-position` / `unpack-position` - block positions as one i64: `x` 26 bits at the top, `z` 26 bits in the middle, `y` 12 bits at the bottom, each signed (unpack sign-extends with arithmetic shifts).
- `bytes.clj`, `read-string` / `write-string` - varint byte length then UTF-8.
- `bytes.clj`, `read-uuid` - two i64s, most significant first.
- `bytes.clj`, `offline-uuid` - see [[mc-offline-uuid]].

## Ranges
- Position: x and z in -33554432..33554431, y in -2048..2047; the test round-trips the extremes `[33554431 2047 -33554432]` and the wiki's worked example.
- `read-u32` masks to unsigned; `read-u16`, `read-u8` likewise.

## Gotchas
- Position order on the wire is x, z, y (not x, y, z); the function argument and return order is `[x y z]`.
- `section-blocks-update` packs a section position differently (x 22, z 22, y 20 bits); that decode lives in `game.clj`'s `section-update`, not here.
- `with-out` is the only way bytes are produced: it runs a writer function against a fresh stream and returns the array.

## See also
- [[conn-framing]] - frames built from these.
- [[packet-table]] - which field types each packet uses.
