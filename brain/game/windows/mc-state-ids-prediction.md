---
title: State ids and click prediction
description: How window state ids and the click's prediction fields work in 26.1.2 - the server records the prediction, answers only differences, bumps the state id per update, resyncs on a stale id - and which parts are verified live versus modelled.
type: reference
tags: [game, windows, protocol, prediction]
aliases: [state id, stateId, prediction, set-cursor-item only when different, resync, authoritative slots]
status: draft
lastUpdated: 2026-10-09
verifiedAgainst: 26.1.2
sourceRefs:
  - src/clojurecraft/sim.clj#defn- sync-window
  - src/clojurecraft/window.clj#defn click
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
- **Modelled (sim), consistent with live runs so far:** the server answers a current-state-id click with a `container-set-slot` per slot that differs from the client's prediction, each with a new state id; a stale state id gets a full `container-set-content`.
- **Sibling observation:** ruststeve sends empty `changedSlots` and comments that the server then always replies with a full authoritative `container_set_content`, and that one click can draw several packets (it waits for 100 ms of quiet). That is a different shape from the sim's per-slot answer; which one 26.1.2 sends for a current-id click is not settled by a repo file.

## Gotchas
- A click that changes nothing produces no packet at all; a client waiting for an answer must time out ([[craft-intent]]).

## Limits
Draft: the per-slot versus full-resync behaviour and the exact state-id increments are the sim's model and the siblings' comments, not a read of vanilla code or a recording pinned in the repo. Re-check by recording a `--until table` run and grepping it for `container-set-slot`, `container-set-content` and `set-cursor-item` after each click.

## See also
- [[sim-window-sync]] - the model.
- [[sib-empty-prediction-clicks]] - what the siblings did.
