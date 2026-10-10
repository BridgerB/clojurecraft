---
title: Sim placement and table window
description: How the sim answers use-item-on - opening a crafting table window, or placing a held table only into an empty, supported cell that does not overlap the player, else rejecting with the old state and an ack.
type: reference
tags: [bot, tooling, sim, placement]
aliases: [sim use-item-on, sim/placed, sim/window, overlaps-player?, rejected placement]
status: verified
lastUpdated: 2026-10-09
verifiedAgainst: 5c7d6c1
sourceRefs:
  - src/clojurecraft/sim.clj#defmethod on-packet [:play :use-item-on]
  - src/clojurecraft/sim.clj#defn overlaps-player?
  - src/clojurecraft/sim.clj#defn block-at
  - test/clojurecraft/sim_test.clj#places-a-table-and-crafts-a-wooden-pickaxe
related:
  - "[[bot/crafting/_moc|Crafting]]"
  - "[[server-model]]"
  - "[[place-intent]]"
---

# Sim placement and table window

## How it works
`on-packet [:play :use-item-on]` with `{:pos :face :sequence}`:
1. **The target is a crafting table** (`sim/block-at` = `table-state`): open window id `:sim/next-window` (then increment) with an empty grid and state id 1; send the ack, `open-screen` (menu type 12) and a full `container-set-content` for the table view with the real cursor.
2. **Otherwise it is a placement** at `pos + face`. It succeeds only if the held hotbar slot (`36 + :sim/held`, set by `set-carried-item`) holds a crafting table, the destination is not solid and not liquid, the clicked block is solid, and the destination does not overlap the player's 0.6 × 1.8 box. Success: record it in `:sim/placed`, take one from the held stack, send `block-update` with the table state, sync window 0, then the ack.
3. **Rejection**: send `block-update` with the destination's current state, then the ack. An overlap also records `[:place-into-player dest]`.

`sim/block-at` answers placed blocks first, then broken positions (air), then the fixture column.

## Gotchas
- Only crafting tables can be placed; any other held item is a rejection.
- `run` delivers the last tick's outgoing packets to the sim before returning, so a test sees the final `container-close` in `:sim/window`.
- The pickaxe test asserts one table placed, the table window closed on both sides, no violations, no failed attempts, and that the bot remembers the table where the server put it.

## See also
- [[place-intent]] - the client side.
- [[sim-window-clicks]] - clicks inside the opened table.
