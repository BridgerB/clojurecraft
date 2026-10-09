---
title: Connection phases
description: The handshake-to-play sequence, which emitted packets flip the phase, and why both the socket and the reducer agree without sharing state.
type: reference
tags: [bot, protocol, handshake, phases]
aliases: [login sequence, protocol state, configuration phase, keep-alive]
status: verified
lastUpdated: 2026-10-09
verifiedAgainst: 60b624e
sourceRefs:
  - src/clojurecraft/packet.clj#def transitions
  - src/clojurecraft/conn.clj#defn open
  - src/clojurecraft/conn.clj#defn read-frame
  - src/clojurecraft/game.clj#defmulti on-packet
related:
  - "[[bot/protocol/_moc|Protocol]]"
  - "[[packet-specs]]"
  - "[[reducer-and-effects]]"
---

# Connection phases

Phases are `:handshake → :login → :configuration → :play` (and back to `:configuration` on `start-configuration`). The table `packet/transitions` says which client packet moves to which phase; `conn` flips its own copy before writing that packet, `game/emit` flips `:bot/phase` when queuing it. Same table, two consumers, no shared state.

## Key files
- `packet.clj`, `transitions` - `intention → login`, `login-acknowledged → configuration`, `finish-configuration → play`, `configuration-acknowledged → configuration`.
- `conn.clj`, `open` - reader thread decodes with the phase current when a frame has fully arrived; writer thread flips then writes; `login-compression` sets the threshold inside `conn` and never reaches the game.
- `conn.clj`, `read-frame` - a frame is `[varint len][data]`, or with compression `[varint len][varint size][payload]` where size 0 means raw.
- `game.clj`, `on-packet` - the replies: `login-finished → login-acknowledged`, `select-known-packs → empty packs`, `finish-configuration → echo`, `login → client-information`, `player-position → accept-teleportation + move-player-pos-rot + player-loaded (once)`, keep-alive and ping echoed in both phases, `chunk-batch-finished → chunk-batch-received 20.0`.

## Gotchas
- `player-loaded` is sent after the first accepted teleport; without it the server never finishes loading the player and nothing (pickup included) ticks.
- Offline UUID is MD5 name-UUID of `OfflinePlayer:<name>`; the server kicks on a mismatch.
- The server's compression threshold is 256 by default, so compression is not optional.

## Limits
Encryption (online mode) is not implemented; the servers are offline mode.

## See also
- [[packet-specs]]
