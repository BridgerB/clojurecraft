---
title: Window views
description: How window.clj gives the craft, smelt and place intents one click layer for window 0, an open crafting table and an open furnace - a view map (id, state id, size, own slots, slot map), click, resync-click, waiting and free-slot.
type: reference
tags: [bot, crafting, windows]
aliases: [window.clj, view, view/slot-of, player->window-slot, free-slot, window/click]
status: verified
lastUpdated: 2026-10-17
verifiedAgainst: HEAD
sourceRefs:
  - src/clojurecraft/window.clj#defn view
  - src/clojurecraft/window.clj#defn click
  - src/clojurecraft/window.clj#defn resync-click
  - src/clojurecraft/window.clj#defn waiting
  - src/clojurecraft/window.clj#defn free-slot
  - src/clojurecraft/window.clj#What a full inventory throws away to make room
  - src/clojurecraft/window.clj#defn junk-slot
  - src/clojurecraft/window.clj#defn worn-spare-slot
  - test/clojurecraft/craft_test.clj#a-worn-tool-with-a-better-one-beside-it-is-thrown
  - src/clojurecraft/window.clj#def filler-keep
  - src/clojurecraft/window.clj#defn tossable
  - src/clojurecraft/window.clj#defmethod intent/run :toss
  - src/clojurecraft/window.clj#Throw the whole stack at player slot p out of view v
  - src/clojurecraft/window.clj#With no slot free, the resync click throws a junk stack out instead
  - src/clojurecraft/inventory.clj#defn toss-click
  - test/clojurecraft/craft_test.clj#a-full-inventory-throws-junk-before-it-crafts
  - test/clojurecraft/craft_test.clj#cobblestone-is-thrown-only-above-what-is-kept
  - test/clojurecraft/craft_test.clj#every-junk-item-is-an-item-of-the-game
  - test/clojurecraft/sim_test.clj#a-full-inventory-throws-junk-to-take-the-pickaxe
  - test/clojurecraft/craft_test.clj#surplus-building-blocks-make-room-without-spending-the-reserve
  - test/clojurecraft/sim_test.clj#a-full-inventory-throws-surplus-deepslate-to-replace-the-pickaxe
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
- `window.clj`, `junk`, `junk-slot`, `tossable`, `toss` - making room in a full inventory. `junk` is data: what mining and walking pick up that no stage asks for, no recipe the goals reach takes and no floor is laid from (sand, sandstone, clay, raw copper, lapis, saplings, dripstone, moss...). The fortress's own bricks and fences are junk too (issue #580: 705 nether bricks dug through on the way in, no slot for a craft's result). `junk-slot` is the first player slot holding junk (main inventory first), else a building-block stack whose loss still leaves `filler-keep` (128 of its kind, including `cobblestone-keep`). `tossable` is that slot when the view has no free slot; the craft's and the smelt's `:settle` throw it before they move anything into the inventory. `toss` first looks up to `toss-pitch` (-30°) for a tick, then sends a throw click (`inventory/toss-click`, mode 4, button 1: the whole stack): thrown along a look down at a table, the stack lands a block off, in the pickup box, and comes back after its two seconds. `resync` with no free slot throws a junk stack with its resync click instead of failing; vanilla throws only with nothing on the cursor and answers a stale state id in full either way. The `:toss` intent throws junk out of window 0 the same way until a slot is free (done with nothing to throw), for a row about to go out for what is picked up on contact (`make/make-room`; sim race 38806318231, issue #161, world R3/7: 36 slots full, every log's collect timed out `:not-picked-up` for half an hour).

## Gotchas
- The `:toss` intent closes an open container before sending window-0 clicks, as equipping and inventory crafting do: a toss right after a table craft (to make room for the table's drop, issue #135) is otherwise ignored by the server while the table menu is open.
- **Surplus fillers can fill every slot too.** Sim race 39044500865 runner 2 (issue #382, R4/23): 638 cobbled deepslate and 457 dirt filled the inventory alongside exactly 128 protected cobblestone. Nothing counted as junk, so replacing the pickaxe failed `:inventory-full` three times. `junk-slot` now also discards surplus deepslate, andesite, diorite, granite, tuff and dirt, only when the whole stack's loss leaves 128 of its own kind (`filler-keep`). Gathering's `:toss`, crafting settlement and lost-click resync share that rule; tools, ores and fuel are not candidates.
- **A full inventory had no room for a result.** Race 38805920624 runner 5 (issues #172, #192): eight bots at 36 full slots (450 to 830 cobblestone, granite, diorite, andesite, raw copper, lapis, sand, clay, up to ten stone pickaxes) shift-clicked a craft's result or a furnace's output into the inventory; the server had nowhere to put it and answered nothing, the click went overdue, and the resync found no free slot either, so the intent failed `:inventory-full` three times. One stack of junk is thrown first now (`a-full-inventory-throws-junk-before-it-crafts`, `a-full-inventory-throws-junk-to-take-the-pickaxe`). What fills the slots is unchanged: the pickaxes that pile up are not thrown.
- The namespace docstring records the 775 click semantics as verified live: the server adopts the click's prediction and sends only what differs ([[mc-state-ids-prediction]]).
- `waiting` compares against the state id of the window the click was sent to; a click into a table window that was closed since has no state id to compare and is no longer "waiting".
- `click` writes onto `:plan/intent`, so it is only for use inside an intent.

## See also
- [[craft-intent]], [[place-intent]] - the two users.
- **Worn spares fill the slots too.** Race 39337645053 (issues #463, #457, #172, #446): bots at the flint and steel, the cast and the portal kit held 16 to 18 stone pickaxes, each worn under `spare-uses` by the stairs and tunnels and each made again as a spare, one slot apiece because tools do not stack; the Nether fortress tunnel filled 36 slots with netherrack, soul sand and soil, wart blocks and nuggets. `worn-spare-slot` makes a worn tool (`worn?`, moved here from `make.clj` with `uses-left`) junk when the inventory holds a better one of the same item, the most worn first and the last stack never; `junk` gains the Nether pick-ups and `filler-keep` gains netherrack (a filler, `stairs/fillers`).
