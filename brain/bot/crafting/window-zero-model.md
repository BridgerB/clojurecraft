---
title: Window model
description: The world attributes for windows - window 0 (:window/grid, :window/cursor, :window/state-id), an open container (:window/open with its own slots and state id), :player/held-slot - which packets write them, and why no item is ever counted twice.
type: reference
tags: [bot, crafting, world, windows]
aliases: [inventory/slot, named slots, window/grid, window/cursor, window/state-id, window/open, window/slots, held-slot, set-window-0-slot, layouts, layout-of-menu]
status: verified
lastUpdated: 2026-10-11
verifiedAgainst: HEAD
sourceRefs:
  - src/clojurecraft/inventory.clj#defn set-window-0-slot
  - src/clojurecraft/inventory.clj#defn set-open-window-slot
  - src/clojurecraft/inventory.clj#def layouts
  - src/clojurecraft/inventory.clj#defn window->player-slot
  - src/clojurecraft/inventory.clj#defn slot-in
  - src/clojurecraft/inventory.clj#def main-then-hotbar
  - src/clojurecraft/inventory.clj#defn hotbar?
  - src/clojurecraft/sim.clj#defn held-slot
  - src/clojurecraft/game.clj#defmethod on-packet [:play :container-set-content]
  - src/clojurecraft/game.clj#defmethod on-packet [:play :set-held-slot]
  - test/clojurecraft/game_test.clj#the-crafting-grid-is-never-invisible
related:
  - "[[bot/crafting/_moc|Crafting]]"
  - "[[window-views]]"
  - "[[mc-window-zero-slots]]"
  - "[[grid-in-its-own-attribute]]"
  - "[[world-value]]"
---

# Window model

| Attribute | Value | Written by |
|---|---|---|
| `:window/grid` | window-0 slots 0-4 verbatim (0 = result, 1-4 = 2x2 grid) | `container-set-content`, `container-set-slot` for window 0 |
| `:window/state-id` | window 0's latest state id | same, window 0 |
| `:window/cursor` | the item on the cursor, absent when empty | `carried` of any `container-set-content`, `set-cursor-item`; dropped by `window/click` |
| `:window/open` | `{:window/id :window/menu-type :window/slots {} :window/state-id}` of another open screen, plus `:window/data {property value}` once the server sends a property (a furnace's fuel and cook progress) | `open-screen` creates it with empty slots; content and slot packets for that id fill it; `container-set-data` for that id writes its data; `container-close` either way drops it |
| `:player/held-slot` | selected hotbar slot 0-8, starts 0 | `set-held-slot` (ignored outside 0-8), and the bot's own `set-carried-item` |

## Key files
- `inventory.clj`, `set-window-0-slot` - slots 0-4 to `:window/grid`, others via `window->player-slot` over window 0's layout to `:player/inventory`.
- `inventory.clj`, `layouts` - every window the bot clicks in as data: window 0 (grid size 2, player inventory from slot 9, armour 5-8, offhand 45), the crafting table (size 3, player inventory from slot 10, menu type 12) and the furnace (no grid: input 0, fuel 1, output 2, player inventory from slot 3, menu type 14), the menu types read from `menus.edn`; `layout-of-menu` finds a container's by its menu type. The server model reads the same table.
- `inventory.clj`, `window->player-slot` - a window slot to the player slot it shows: from the layout's `:inventory` on, 27 main slots then 9 hotbar map to player slots 9-35 then 0-8; anything before is the container's own.
- `inventory.clj`, `slot-in` - a place in any window by name, as that window's slot number, read from the same layouts: `[:hotbar n]` (0-8), `[:main n]` (0-26 from the top left), in window 0 `[:armour n]` (head to feet) and `[:offhand]`, a crafting window's `[:result]` and `[:grid n]`, a furnace's `[:input]`, `[:fuel]` and `[:output]` (`[:hotbar 0]` is 36 in window 0, 30 in a furnace); `slot` is window 0's. Tests and the server model name places with it (`sim/held-slot`, the selected hotbar place) rather than write wire numbers.
- `inventory.clj`, the player's own slot numbers: `hotbar?` (0-8, what the hand can hold from), `main-then-hotbar` (the order a free slot is looked for in), `head-slot` 39 down to the boots at 36, `offhand-slot` 40.
- `inventory.clj`, `set-open-window-slot` - the container's own slots go to `:window/open`'s `:window/slots`; slots that are the player's inventory update `:player/inventory`.
- `game.clj`, `on-packet` for `container-set-content` / `container-set-slot` - dispatch on window id: 0, the open window's id, or ignored.

## Gotchas
- Each item lives in exactly one attribute: grid items in `:window/grid` or `:window/slots`, never in `:player/inventory` too; `inventory/item-count` reads the inventory only. This is the answer to steve's wood-lock ([[sib-wood-lock]]).
- An unknown menu type has no layout, so every slot of it is stored as the container's own and the player's inventory is not updated from it.
- `set-held-slot` outside 0-8 used to break the world spec; the totality property found it ([[property-tests]]).
- The cursor is not authoritative after our own click: see [[phantom-cursor-stale-window]].

## See also
- [[window-views]] - how intents read these.
- [[mc-window-zero-slots]], [[mc-crafting-table-window]] - the vanilla layouts.
