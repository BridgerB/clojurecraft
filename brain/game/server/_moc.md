---
title: Server
type: moc
tags: [game, server]
status: verified
lastUpdated: 2026-10-09
verifiedAgainst: 26.1.2
related:
  - "[[game/_moc|Game pillar]]"
---

# Server

Server configuration and login facts the bot depends on.

- [[mc-compression-threshold]] - default 256, switched on mid-login, both framings required.
- [[mc-offline-uuid]] - the MD5 name-UUID an offline server expects in hello.
- [[mc-spawn-protection]] - why breaks near spawn revert for a non-op, and the line that stops it.
- [[mc-pause-when-empty]] - an empty 26.x server stops ticking after 60 s unless told not to.
- [[mc-gamerules-snake-case]] - keep_inventory, not keepInventory.

## See also
- [[local-server]] - our server.properties.
