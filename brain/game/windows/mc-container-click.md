---
title: Container click
description: The 775 container-click packet - field layout with hashed-slot predictions - and what the modes and buttons the bot uses do (mode 0 left/right, mode 1 shift-click), with the other modes listed.
type: reference
tags: [game, windows, packets, clicks]
aliases: [container_click, click modes, shift-click mode 1, right-click button 1, hashed slot, window click]
status: verified
lastUpdated: 2026-10-09
verifiedAgainst: 26.1.2
sourceRefs:
  - src/clojurecraft/packet.clj#[:play :c2s :container-click]
  - src/clojurecraft/packet.clj#defn- write-hashed-slot
  - test/clojurecraft/packet_test.clj#775 layout: window, state id, slot, button, mode, changed slots, cursor
  - resources/clojurecraft/packets.edn#:container-click 18
  - steve:src/lib/typecraft/bot/inventory.ts#await bot.clickWindow(i, 0, 4, bot.inventory); // mode 4 = drop
related:
  - "[[game/windows/_moc|Windows]]"
  - "[[recipe-clicks]]"
  - "[[mc-state-ids-prediction]]"
---

# Container click

`container-click` (play c2s id 18 in 775) is how a client moves items in any window.

## Layout
`window-id varint, state-id varint, slot i16, button i8, mode varint, changed [ (slot i16, hashed-slot) ], cursor hashed-slot`.
A hashed slot is `bool present` then `item varint, count varint, added-components [(type varint, hash i32)], removed-components [type varint]`. The packet test pins the bytes of a click with no prediction: `12 00 07 00 24 01 00 00 00` (id 18, window 0, state 7, slot 36, button 1, mode 0, zero changed, absent cursor).

## Modes and buttons the bot relies on
| Mode | Button | Effect |
|---|---|---|
| 0 | 0 | left click: pick up a stack, put it down, merge, or swap |
| 0 | 1 | right click: pick up half, or place one item |
| 1 | 0 | shift-click: move the stack to the other section; on a result slot, craft as many as fit |
| 4 | 0 | drop one item from the slot (typecraft's drop) |

Other vanilla modes (2 hotbar swap, 3 clone, 4 with button 1 drop stack, 5 drag, 6 double-click collect) are not used by any of our code and are not verified here.

## Gotchas
- The `changed` and `cursor` fields are the client's prediction, and the server adopts the cursor claim as its record; see [[mc-state-ids-prediction]] and [[phantom-cursor-stale-window]].
- A click whose window id is not the open window is ignored silently ([[sib-stale-window-clicks]]).

## See also
- [[recipe-clicks]] - the clicks the bot sends.
