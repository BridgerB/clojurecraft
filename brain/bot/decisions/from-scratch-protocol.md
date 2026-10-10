---
title: From-scratch protocol
description: Why the wire protocol is implemented as data in this repo rather than through MCProtocolLib or another Java library.
type: decision
tags: [bot, decision, protocol]
aliases: [why not MCProtocolLib, no Java library, packets as data decision]
status: verified
lastUpdated: 2026-10-09
verifiedAgainst: 60b624e
sourceRefs:
  - src/clojurecraft/packet.clj#def specs
  - deps.edn#org.clojure/core.async
related:
  - "[[bot/decisions/_moc|Decisions]]"
  - "[[packet-specs]]"
---

# From-scratch protocol

## The choice
Implement protocol 775 ourselves: about fifty packet specs as a data table, one interpreter, JDK-only sockets and zlib. The only dependency is core.async.

## What was rejected
- MCProtocolLib (GeyserMC) via Java interop: class-per-packet, mutable session objects, and 26.1.2 support could not be confirmed from its repository page on 2026-10-09. It would have made every packet an object and every handler a listener, the opposite of the design in `docs/hickey.md`.
- Clojurecraft (2011, Steve Losh) and witchcraft (Bukkit server-side): abandoned or server-side; not clients.

## What would change the answer
A maintained Clojure or Java client library that exposes packets as plain data for the current protocol and keeps pace with Mojang's release cadence.

## See also
- [[packet-specs]]
