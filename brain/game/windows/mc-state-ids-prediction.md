---
title: State ids and click prediction
description: How window state ids and the click's prediction fields work in 26.1.2 - the server records the prediction, answers only differences, bumps the state id per update, resyncs on a stale id - read from the game's code and seen live.
type: reference
tags: [game, windows, protocol, prediction]
aliases: [state id, stateId, prediction, set-cursor-item only when different, resync, authoritative slots]
status: verified
lastUpdated: 2026-10-11
verifiedAgainst: 26.1.2
sourceRefs:
  - src/clojurecraft/sim.clj#defn sync-window
  - src/clojurecraft/window.clj#defn click
  - src/clojurecraft/window.clj#defn resync-click
  - "src/clojurecraft/window.clj#775 click semantics, all verified live: a click carries the client's prediction (changed slots and cursor); the server adopts it and then sends only what differs."
  - "ruststeve:src/bot/inventory.rs#Send EMPTY changedSlots so the server ALWAYS sees a prediction mismatch and"
related:
  - "[[game/windows/_moc|Windows]]"
  - "[[sim-window-sync]]"
  - "[[phantom-cursor-stale-window]]"
  - "[[predict-nothing]]"
---

# State ids and click prediction

Every window update the server sends (`container-set-content`, `container-set-slot`) carries a state id; a click carries the state id the client last saw, plus its prediction (changed slots with hashes, and the cursor).

## Facts
- **Verified live (2026-10-09, recorded in `window.clj`'s docstring):** the server adopts the click's prediction and sends only what differs from it; for the cursor, it adopts the click's `cursor` field as its record of the client's cursor and sends `set-cursor-item` only when the real cursor differs from it. A client that claims an empty cursor gets no cursor packet when the cursor really is empty after the click. Encoded in `window/click`.
- **Read in the 26.1.2 jar (`javap -c`, 2026-10-11):** `ServerGamePacketListenerImpl.handleContainerClick` ignores a click whose window id is not the open menu's; otherwise it notes whether the click's state id differs from the menu's, applies the click, records the click's changed slots and carried item as what the client believes (`setRemoteSlotUnsafe`, `setRemoteCarried`), and then calls `broadcastFullState` when the ids differed, else `broadcastChanges`. The player's synchronizer sends the full state as one `container-set-content` and each changed slot as a `container-set-slot`, each under `incrementStateId()`. So a current-id click is answered per changed slot, each with a new state id, and a stale one with the whole window and the real cursor whatever it changed; the sim models exactly this ([[sim-window-sync]]), and `window/resync-click` uses the second to learn the cursor after a lost click ([[smelt-intent]]).
- **Sibling observation:** ruststeve sends empty `changedSlots` and comments that the server then always replies with a full `container_set_content`. The code says otherwise for a current state id: slots the click does not list keep the server's last record, so only what really changed comes back; a full content comes only for a stale id.

## Gotchas
- A click that changes nothing produces no packet at all; a client waiting for an answer must time out ([[craft-intent]]).

## Limits
The jar reading is of bytecode, not source, and it is not pinned as a file in the repo; the call order above is what the bytecode does. The furnace's own updates (its ticks) also go out through `broadcastChanges`, so they move the state id with no click.

## See also
- [[sim-window-sync]] - the model.
- [[sib-empty-prediction-clicks]] - what the siblings did.
