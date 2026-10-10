---
title: Connection framing and compression
description: How conn.clj frames, compresses and decompresses packets, when the threshold switches on, and how its two threads and bounded channels decide the decode state.
type: reference
tags: [bot, protocol, conn, compression]
aliases: [read-frame, write-frame, zlib, compression threshold, socket threads, inbox outbox]
status: verified
lastUpdated: 2026-10-09
verifiedAgainst: 5c7d6c1
sourceRefs:
  - src/clojurecraft/conn.clj#defn read-frame
  - src/clojurecraft/conn.clj#defn write-frame
  - src/clojurecraft/conn.clj#defn open
  - src/clojurecraft/conn.clj#defn inflate
related:
  - "[[bot/protocol/_moc|Protocol]]"
  - "[[connection-phases]]"
  - "[[mc-compression-threshold]]"
---

# Connection framing and compression

`conn/open` connects a socket and returns `{:in chan :out chan :close! fn}`. Decoded packet maps arrive on `:in` (buffer 1024); packet maps put on `:out` (buffer 256) are encoded and written. The protocol state and compression threshold live in an atom private to `conn`, `{:state :handshake :threshold -1}`; the game never sees the threshold.

## Key files
- `conn.clj`, `read-frame` - `[varint length][data]`. With a threshold in force, data is `[varint uncompressed-size][payload]`; size 0 means the payload is raw, otherwise it is inflated to exactly that size.
- `conn.clj`, `write-frame` - threshold negative: raw. Payload shorter than the threshold: `varint 0` then raw. Otherwise `varint size` then the deflated bytes. Then the outer length; flush every frame.
- `conn.clj`, `open` - the reader and writer threads.
- `conn.clj`, `inflate`, `deflate` - JDK `java.util.zip`, one Inflater/Deflater per frame.

## How it works
1. Reader thread: `read-frame` with the current threshold, decode with the current state (`packet/decode`, a decode exception becomes `{:packet/name :decode-error ...}`), and if the packet is `login-compression` in the login state, set the threshold before the next frame is read. Then put the packet on `:in`.
2. Writer thread: take a packet, encode with the current state, flip the state through `packet/next-state`, write the frame. Flipping after encode and before the write means the server's reply is decoded in the new state.
3. Any exception in either thread puts `{:packet/name :closed :packet/reason s}` on `:in` and closes it; `run-loop` turns a nil from `:in` into a `:closed` event.
4. Socket: 10 s connect timeout, TCP_NODELAY, 64 KB buffered streams.

## Gotchas
- `login-compression` is consumed in `conn` and still forwarded to the reducer, which ignores it (no `on-packet` method); the game never needs the threshold.
- Bounded channels mean a slow reducer back-pressures the reader thread and therefore TCP; nothing is dropped.
- Encryption is not implemented (offline servers only); see [[connection-phases]].

## See also
- [[bytes-primitives]] - the varints and buffers the frames are made of.
- [[mc-compression-threshold]] - the server side.
