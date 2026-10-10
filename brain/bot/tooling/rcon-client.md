---
title: RCON client
description: The minimal RCON client used by the harness and the CI judge - packet layout, auth, how responses are matched by id - and the clojure -M:rcon command line.
type: reference
tags: [bot, tooling, rcon]
aliases: [rcon.clj, clojure -M:rcon, rcon auth, RCON judge]
status: verified
lastUpdated: 2026-10-09
verifiedAgainst: 5c7d6c1
sourceRefs:
  - src/clojurecraft/rcon.clj#defn encode-packet
  - src/clojurecraft/rcon.clj#defn connect
  - src/clojurecraft/rcon.clj#defn command
  - test/clojurecraft/rcon_test.clj#frame-roundtrip
related:
  - "[[bot/tooling/_moc|Tooling]]"
  - "[[harness-landing]]"
  - "[[gym-on-runners]]"
---

# RCON client

## Key files
- `rcon.clj`, `encode-packet` - little-endian `[len i32][id i32][type i32][body UTF-8][0][0]`, len = 10 + body length.
- `rcon.clj`, `connect` - 5 s connect timeout, sends type 3 (login) with the password as id 1; a response id of -1 throws "rcon auth failed".
- `rcon.clj`, `command` - sends type 2 with a fresh id (counter from 10), reads responses until one carries that id, returns its body.
- `rcon.clj`, `with-rcon` - connect, call, always close.
- `rcon.clj`, `-main` - `clojure -M:rcon --host H --port P --pass S <words...>`; default host 127.0.0.1, port 25575.

## Gotchas
- Multi-packet responses are not reassembled: `command` returns at the first packet with its id, so if the server splits a long reply, only the first part comes back. The judge's `data get entity ... Inventory` on a small inventory has fit so far.
- Responses with other ids are skipped, not queued.
- The password is never in the repo: local runs read `data/local-server/rcon.pass`, CI masks a random one.

## See also
- [[harness-landing]] - the fixture that uses it.
