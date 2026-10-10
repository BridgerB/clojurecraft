---
title: Spawn protection
description: Why a non-op bot cannot break blocks near world spawn on a default server (spawn-protection 16 silently reverts the break) and the server.properties line that turns it off.
type: reference
tags: [game, server, properties, dig]
aliases: [spawn-protection=0, block restored after dig, non-op cannot break]
status: verified
lastUpdated: 2026-10-09
verifiedAgainst: 26.1.2
sourceRefs:
  - scripts/server.sh#spawn-protection=0
  - src/clojurecraft/harness.clj#positioned over motion_blocking_no_leaves run tp
related:
  - "[[game/server/_moc|Server]]"
  - "[[local-server]]"
---

# Spawn protection

## Facts
- `spawn-protection` (default 16) protects a square of that radius around world spawn from non-operator players: a break is rejected and the server sends the block back. The bot's FINISH is acknowledged but the block reappears.
- `scripts/server.sh` writes `spawn-protection=0`, so neither the local server nor CI needs the bot to be op.
- The harness lands the bot at the nearest forest from where it spawned, which can be inside the protected square; with protection off that never matters.

## Limits
The default radius and the "sent back" behaviour are vanilla facts recorded in [[local-server]]; they were not re-observed by a test in this repo.

## See also
- [[local-server]] - the other properties that matter.
