---
title: Compression threshold
description: The 26.1.2 default network compression threshold (256), when the server switches compression on during login, and what a client must do with it.
type: reference
tags: [game, server, protocol, compression]
aliases: [network-compression-threshold, login-compression, set compression, zlib threshold]
status: verified
lastUpdated: 2026-10-09
verifiedAgainst: 26.1.2
sourceRefs:
  - "CLAUDE.md#Vanilla 26.1.2 = protocol 775, offline mode, Java 25, compression threshold 256, seed typecraft."
  - src/clojurecraft/conn.clj#(swap! proto assoc :threshold (:threshold pkt))
  - src/clojurecraft/packet.clj#[:login :s2c :login-compression]
related:
  - "[[game/server/_moc|Server]]"
  - "[[conn-framing]]"
---

# Compression threshold

## Facts
- Default `network-compression-threshold` is 256; `scripts/server.sh` does not set it, and the server-written `data/local-world/server.properties` shows `network-compression-threshold=256` (read 2026-10-09).
- The server sends `login-compression {threshold}` during login, before `login-finished`. Every frame after that one, in both directions, uses the compressed format: `[length][uncompressed-size][payload]`, where size 0 means "not compressed" and is used for payloads shorter than the threshold.
- A client must therefore implement both framings; with the default threshold, small packets (keep-alives, movement) stay raw and chunk packets are zlib-deflated.
- A negative threshold disables compression (the server sends no `login-compression`).

## Gotchas
- The switch applies starting with the next frame read, so the reader must change framing between frames, which is why `conn`'s reader thread does it before reading on.

## See also
- [[conn-framing]] - our implementation.
