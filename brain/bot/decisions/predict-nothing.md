---
title: Predict nothing
description: Why every container click claims no changed slots and an empty cursor, so the server answers each click with authoritative slots and the bot never models a predicted window.
type: decision
tags: [bot, decision, crafting, protocol]
aliases: [empty prediction, changed [] cursor nil, authoritative slots, no client prediction]
status: verified
lastUpdated: 2026-10-09
verifiedAgainst: 5c7d6c1
sourceRefs:
  - src/clojurecraft/packet.clj#775 clicks carry the client's prediction as hashed slots
  - src/clojurecraft/window.clj#defn click
  - "docs/issues/01-crafting-as-data.md#We have no prediction to drift: the world only changes on container-set-slot/container-set-content, and the executor gates each click on the state id advancing."
related:
  - "[[bot/decisions/_moc|Decisions]]"
  - "[[mc-state-ids-prediction]]"
  - "[[phantom-cursor-stale-window]]"
---

# Predict nothing

## The choice
Every `container-click` is sent with `:changed []` and `:cursor nil`. The world changes only when the server says so (`container-set-slot`, `container-set-content`, `set-cursor-item`), and the craft intent waits for the state id to move before the next click. One exception, forced by the protocol: `window/click` drops `:window/cursor` as it sends, because the empty claim becomes the server's record ([[phantom-cursor-stale-window]]).

## What was rejected
Client-side prediction (apply the click locally, send the predicted slots, reconcile). Per issue 01's research, ruststeve's prediction drifted and it ended up sending empty `changedSlots` to force authoritative replies, and found replies lag one click if you act on the first packet; typecraft abused a stale state id to force a full resend. A predicted window is a second copy of the truth that can disagree with the server, which is exactly the steve wood-lock class of bug ([[sib-wood-lock]]).

## What would change the answer
Latency: one round trip per click makes a lay-out cost about `clicks × RTT`. If crafting on a remote server becomes the bottleneck, predicting and batching clicks would be worth its reconciliation cost. No such measurement exists yet.

## See also
- [[sim-window-sync]] - the sim models the server's response to an empty prediction.
