---
title: Open-container intent
description: How :open-container right-clicks a remembered container within 4.5 blocks and is done only when the server has sent both open-screen and the window's contents.
type: reference
tags: [bot, crafting, intents, windows]
aliases: [:open-container, open the crafting table, open-screen wait, no-window]
status: verified
lastUpdated: 2026-10-09
verifiedAgainst: 5c7d6c1
sourceRefs:
  - src/clojurecraft/place.clj#defmethod intent/run :open-container
  - src/clojurecraft/place.clj#def open-reach 4.5
  - src/clojurecraft/game.clj#defmethod on-packet [:play :open-screen]
related:
  - "[[bot/crafting/_moc|Crafting]]"
  - "[[place-intent]]"
  - "[[window-zero-model]]"
---

# Open-container intent

`{:intent/kind :open-container :intent/target [x y z]}` opens a block's screen.

## How it works
1. Done as soon as `:window/open` exists **and** carries a `:window/state-id` - i.e. `open-screen` arrived and so did the `container-set-content` for that window ([[window-zero-model]]).
2. After sending, wait; no window within 3000 ms fails `:no-window`.
3. Before sending: farther than `open-reach` (4.5, eye to block centre) fails `:out-of-reach`; otherwise look at the centre and send `use-item-on` on the face nearest the eye (`intent/face-toward`) with cursor `[0.5 0.5 0.5]`.

## Gotchas
- It does not check what opened: [[window-views]] returns nil for `:table` unless the menu type is 12, and the craft then fails `:no-table-window`.
- Controls are zeroed every tick, so the bot must already be in reach; [[make-goals]] walks first.

## See also
- [[craft-intent]] - crafting in the opened table.
