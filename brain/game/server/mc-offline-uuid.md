---
title: Offline UUID
description: The UUID an online-mode=false 26.1.2 server assigns a player (MD5 name-UUID v3 of "OfflinePlayer:<name>"), and why the login hello must carry exactly it.
type: reference
tags: [game, server, login, uuid]
aliases: [offline mode uuid, OfflinePlayer, nameUUIDFromBytes, hello uuid]
status: verified
lastUpdated: 2026-10-09
verifiedAgainst: 26.1.2
sourceRefs:
  - src/clojurecraft/bytes.clj#The UUID an online-mode=false server assigns: MD5 name-UUID (v3) of OfflinePlayer:<name>.
  - test/clojurecraft/bytes_test.clj#MD5 name-uuid of "OfflinePlayer:Steve"
  - scripts/server.sh#online-mode=false
related:
  - "[[game/server/_moc|Server]]"
  - "[[bytes-primitives]]"
  - "[[connection-phases]]"
---

# Offline UUID

## Facts
- With `online-mode=false` (our servers) there is no Mojang authentication and no encryption. The server derives the player's UUID from the name: `UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(UTF_8))`, a version-3 (MD5) UUID.
- `OfflinePlayer:Steve` → `5627dd98-e6be-3c21-b8a8-e92344183641` (pinned by the bytes test).
- The login `hello` carries the username and this UUID; `game/init` computes it once as `:bot/uuid`.
- The UUID is stable per name, so RCON commands, `data get entity <name>` and world player data all key on the name the bot used.

## Gotchas
- Changing `--name` changes the UUID and therefore which player data file the server loads (inventory, position).

## See also
- [[connection-phases]] - where hello is sent.
