---
title: Phantom cursor and stale-window
description: Symptom - a craft fails :stale-window on a click that should be harmless (putting the cursor down). Cause - in 775 the server adopts the click's cursor field as its record and only reports the cursor when it differs, so the bot's model kept an item the cursor no longer held.
type: reference
tags: [bot, gotcha, crafting, protocol]
aliases: [stale-window, phantom cursor, cursor never cleared, set-cursor-item missing, no answer to click]
status: verified
lastUpdated: 2026-10-09
verifiedAgainst: 5c7d6c1
sourceRefs:
  - src/clojurecraft/window.clj#defn click
  - src/clojurecraft/window.clj#an empty cursor, so the world drops :window/cursor on every click and the server corrects it only when it is not empty.
  - src/clojurecraft/sim.clj#defn- sync-window
related:
  - "[[bot/crafting/_moc|Crafting]]"
  - "[[bot/gotchas/_moc|Gotchas]]"
  - "[[craft-intent]]"
  - "[[mc-state-ids-prediction]]"
---

# Phantom cursor and stale-window

Symptom: a craft's last click (the put-back of the source stack) empties the cursor, but `:window/cursor` still holds the stack. The next `:settle` sees a loaded cursor and clicks an empty slot to put it down; the server has nothing to put down, the click changes nothing, no packet comes back, the state id never moves, and three seconds later the intent fails `:stale-window`. Verified live by the coordinator on 26.1.2 on 2026-10-09.

## Root cause
In protocol 775 a `container-click` carries the client's prediction: the slots it thinks changed and what it thinks is on the cursor. The server **adopts the click's `cursor` field as its record of the client's cursor**, and sends `set-cursor-item` only when the real cursor differs from that record. The bot always claims an empty cursor (`:cursor nil`). So when a click really does leave the cursor empty, the server says nothing about the cursor; a model that only updates `:window/cursor` from `set-cursor-item` keeps the old item forever.

## Fix
`window/click` drops `:window/cursor` from the world as it sends every click: the bot believes exactly what it claimed. If the cursor is in fact loaded after the click (after picking up the source stack), the server sees the difference from the empty claim and sends `set-cursor-item`, which restores it. The sim's `sync-window` models the same rule (cursor sent only when it differs from the predicted cursor), so the sim stays silent about an emptied cursor exactly as the server does.

## Gotchas
- Never claim a non-empty cursor without also tracking it locally the same way; the claim and the model must agree or one of them goes silent.
- Any click that changes nothing gets no answer at all; `window/waiting` treats silence as the only failure signal, so every no-op click is a 3 s stall.

## See also
- [[sim-window-sync]] - the model of the server side.
- [[predict-nothing]] - why the claim is empty.
