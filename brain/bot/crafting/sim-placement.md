---
title: Sim placement and container windows
description: How the sim answers use-item-on - opening a crafting table or a furnace, or placing a held block only into an empty, supported cell that does not overlap the player, else rejecting with the old state and an ack.
type: reference
tags: [bot, tooling, sim, placement]
aliases: [sim use-item-on, sim/placed, sim/window, overlaps-player?, rejected placement, open-window, sim/place]
status: verified
lastUpdated: 2026-10-11
verifiedAgainst: HEAD
sourceRefs:
  - src/clojurecraft/sim.clj#defmethod on-packet [:play :use-item-on]
  - src/clojurecraft/sim.clj#defn overlaps-player?
  - src/clojurecraft/sim.clj#defn place
  - src/clojurecraft/sim.clj#defn open-window
  - src/clojurecraft/sim.clj#defn block-at
  - test/clojurecraft/sim_test.clj#places-a-table-and-crafts-a-wooden-pickaxe
related:
  - "[[bot/crafting/_moc|Crafting]]"
  - "[[server-model]]"
  - "[[place-intent]]"
  - "[[sim-furnace]]"
---

# Sim placement and table window

## How it works
`on-packet [:play :use-item-on]` with `{:pos :face :sequence}`, by the name of the clicked block (`sim/block-at`):
1. **A crafting table**: send the ack, then `open-window` `:table`: window id `:sim/next-window` (then increment) with an empty grid and state id 1, `open-screen` (menu type 12) and a full `container-set-content` with the real cursor. An open container is closed first, as vanilla's `openMenu` does.
2. **A furnace**: the ack, then `open-furnace`: the same for window kind `:furnace` (menu type 14), then its four properties ([[sim-furnace]]).
3. **Otherwise it is a placement** (`place`) at `pos + face`. It succeeds only if the held hotbar slot (`36 + :sim/held`, set by `set-carried-item`) holds a block item (`blocks/default-state` of its name: a table, a furnace, gravel), the destination is not solid and not liquid, the clicked block is solid, and the destination does not overlap the player's 0.6 × 1.8 box. Success: record it in `:sim/placed`, take one from the held stack, send `block-update` with that state, sync window 0, then the ack.
4. **Rejection**: send `block-update` with the destination's current state, then the ack. An overlap also records `[:place-into-player dest]`.

`sim/block-at` answers placed blocks first, then broken positions (air), then the fixture column.

## Gotchas
- Only a held item whose name is a block can be placed; any other is a rejection. The model places the block's lowest state, where a real server picks one (a furnace's facing), which is why the bot judges a placement by name.
- `run` delivers the last tick's outgoing packets to the sim before returning, so a test sees the final `container-close` in `:sim/window`.
- The pickaxe test asserts one table placed, the table window closed on both sides, no violations, no failed attempts, and that the bot remembers the table where the server put it.

## See also
- [[place-intent]] - the client side.
- [[sim-window-clicks]] - clicks inside the opened table.
