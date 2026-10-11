---
title: Window views
description: How window.clj gives the craft, smelt and place intents one click layer for window 0, an open crafting table and an open furnace - a view map (id, state id, size, own slots, slot map), click, resync-click, waiting and free-slot.
type: reference
tags: [bot, crafting, windows]
aliases: [window.clj, view, view/slot-of, player->window-slot, free-slot, window/click]
status: verified
lastUpdated: 2026-10-11
verifiedAgainst: HEAD
sourceRefs:
  - src/clojurecraft/window.clj#defn view
  - src/clojurecraft/window.clj#defn click
  - src/clojurecraft/window.clj#defn resync-click
  - src/clojurecraft/window.clj#defn waiting
  - src/clojurecraft/window.clj#defn free-slot
  - src/clojurecraft/inventory.clj#defn player->window-slot
related:
  - "[[bot/crafting/_moc|Crafting]]"
  - "[[craft-intent]]"
  - "[[window-zero-model]]"
  - "[[mc-crafting-table-window]]"
---

# Window views

A view is a plain map describing one window the way the click rules need it, so window 0, an open crafting table and an open furnace share every rule. A furnace's view is built the table's way from its layout: size 0, and its `:view/grid` holds the furnace's own slots (input 0, fuel 1, output 2).

| Key | `:inventory` (window 0) | `:table` (open crafting table) |
|---|---|---|
| `:view/id` | 0 | `:window/id` of `:window/open` |
| `:view/state-id` | `:window/state-id` | `:window/open`'s `:window/state-id` |
| `:view/size` | 2 | 3 |
| `:view/grid` | `:window/grid` | `:window/open`'s `:window/slots` |
| `:view/slot-of` | `inventory/player->window-slot` over window 0's layout | the same over the table's: hotbar 0-8 → 37-45, main 9-35 → 10-36 |

## Key files
- `window.clj`, `view` - builds the map from `inventory/layouts`; a container kind (`:table`, `:furnace`) is nil unless the open window's menu type is that layout's (12, 14).
- `window.clj`, `click` - emits `container-click` with the view's id and state id, `:changed []`, `:cursor nil`; drops `:window/cursor`; records `:intent/awaiting`, `:intent/awaiting-window` and `:intent/sent-at` on the current intent.
- `window.clj`, `resync-click` - the same click carrying state id -1, which the server always answers with the whole window and the real cursor ([[mc-state-ids-prediction]]); the smelt intent's way to learn the cursor after a lost click ([[smelt-intent]]).
- `window.clj`, `waiting` - nil when nothing is unanswered, `:waiting` while that window's state id has not moved, `:overdue` after `answer-timeout` (3000 ms).
- `window.clj`, `free-slot` - the first empty player slot, main inventory before hotbar, as a slot of this view.

## Gotchas
- The namespace docstring records the 775 click semantics as verified live: the server adopts the click's prediction and sends only what differs ([[mc-state-ids-prediction]]).
- `waiting` compares against the state id of the window the click was sent to; a click into a table window that was closed since has no state id to compare and is no longer "waiting".
- `click` writes onto `:plan/intent`, so it is only for use inside an intent.

## See also
- [[craft-intent]], [[place-intent]] - the two users.
