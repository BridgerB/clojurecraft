---
title: Window views
description: How window.clj gives the craft and place intents one click layer for window 0 and an open crafting table - a view map (id, state id, size, grid, slot map), click, waiting and free-slot.
type: reference
tags: [bot, crafting, windows]
aliases: [window.clj, view, view/slot-of, table-slot, free-slot, window/click]
status: verified
lastUpdated: 2026-10-09
verifiedAgainst: 5c7d6c1
sourceRefs:
  - src/clojurecraft/window.clj#defn view
  - src/clojurecraft/window.clj#defn click
  - src/clojurecraft/window.clj#defn waiting
  - src/clojurecraft/window.clj#defn free-slot
  - src/clojurecraft/window.clj#defn table-slot
related:
  - "[[bot/crafting/_moc|Crafting]]"
  - "[[craft-intent]]"
  - "[[window-zero-model]]"
  - "[[mc-crafting-table-window]]"
---

# Window views

A view is a plain map describing one window the way the click rules need it, so window 0 and an open crafting table share every rule.

| Key | `:inventory` (window 0) | `:table` (open crafting table) |
|---|---|---|
| `:view/id` | 0 | `:window/id` of `:window/open` |
| `:view/state-id` | `:window/state-id` | `:window/open`'s `:window/state-id` |
| `:view/size` | 2 | 3 |
| `:view/grid` | `:window/grid` | `:window/open`'s `:window/slots` |
| `:view/slot-of` | `recipe/player->container-slot` | `table-slot`: hotbar 0-8 → 37-45, main 9-35 → 10-36 |

## Key files
- `window.clj`, `view` - builds the map; `:table` is nil unless the open window's menu type is 12 (crafting).
- `window.clj`, `click` - emits `container-click` with the view's id and state id, `:changed []`, `:cursor nil`; drops `:window/cursor`; records `:intent/awaiting`, `:intent/awaiting-window` and `:intent/sent-at` on the current intent.
- `window.clj`, `waiting` - nil when nothing is unanswered, `:waiting` while that window's state id has not moved, `:overdue` after `answer-timeout` (3000 ms).
- `window.clj`, `free-slot` - the first empty player slot, main inventory before hotbar, as a slot of this view.

## Gotchas
- The namespace docstring records the 775 click semantics as verified live: the server adopts the click's prediction and sends only what differs ([[mc-state-ids-prediction]]).
- `waiting` compares against the state id of the window the click was sent to; a click into a table window that was closed since has no state id to compare and is no longer "waiting".
- `click` writes onto `:plan/intent`, so it is only for use inside an intent.

## See also
- [[craft-intent]], [[place-intent]] - the two users.
