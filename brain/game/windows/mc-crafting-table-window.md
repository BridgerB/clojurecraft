---
title: Crafting table window
description: The 26.1.2 crafting table screen - menu type 12, result 0, 3x3 grid 1-9, the player's main inventory 10-36 and hotbar 37-45 - and how it is opened and closed.
type: reference
tags: [game, windows, crafting]
aliases: [menu type 12, crafting menu, 3x3 grid slots, table window layout, open-screen crafting]
status: verified
lastUpdated: 2026-10-09
verifiedAgainst: 26.1.2
sourceRefs:
  - src/clojurecraft/game.clj#{12 {:menu/name :crafting :menu/size 3 :menu/grid (range 0 10) :menu/inventory 10}}
  - src/clojurecraft/window.clj#defn table-slot
  - src/clojurecraft/sim.clj#a crafting table has a 3x3 grid and the
related:
  - "[[game/windows/_moc|Windows]]"
  - "[[mc-window-zero-slots]]"
  - "[[window-views]]"
---

# Crafting table window

## Facts
- Right-clicking a crafting table (`use-item-on`) opens a new window: the server sends `open-screen` with a fresh window id and menu type 12 (`minecraft:crafting` in the menu registry), then `container-set-content` for that window with its own state id.
- Layout: 0 result, 1-9 the 3x3 grid in reading order, 10-36 the player's main inventory (player slots 9-35), 37-45 the hotbar (player slots 0-8). Every vanilla container appends the player's 36 slots after its own, main first.
- The table window has its own state id, separate from window 0's.
- Closing (`container-close` with the window id) returns anything left in the grid to the player's inventory.
- Verified live on 2026-10-09: the bot placed a table, opened it and crafted a wooden pickaxe in it (commit 5c7d6c1's message: "wooden pickaxe in 41 s from landing").

## Gotchas
- The armour and offhand slots are not part of a container window.
- Menu type ids are registry indexes and change between versions; `game/menus` is keyed by the 26.1.2 number.

## See also
- [[window-zero-model]] - how the bot stores the open window.
- [[mc-use-item-on]] - the packet that opens it.
