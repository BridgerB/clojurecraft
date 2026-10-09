---
title: Sim window sync
description: How the sim answers a click the way vanilla 775 does - prediction recorded, only differences sent, one state id per changed slot, full content on a stale state id, cursor sent only when it differs from the claim - for window 0 and the table window alike.
type: reference
tags: [bot, tooling, sim, crafting, protocol]
aliases: [sync-window, state id bump, stale state id, container-set-content resync, predicted cursor]
status: verified
lastUpdated: 2026-10-09
verifiedAgainst: 5c7d6c1
sourceRefs:
  - src/clojurecraft/sim.clj#defn- sync-window
  - src/clojurecraft/sim.clj#defmethod on-packet [:play :container-click]
  - src/clojurecraft/sim.clj#defn- give
  - src/clojurecraft/packet.clj#775 clicks carry the client's prediction as hashed slots
related:
  - "[[bot/crafting/_moc|Crafting]]"
  - "[[sim-window-clicks]]"
  - "[[mc-state-ids-prediction]]"
  - "[[phantom-cursor-stale-window]]"
---

# Sim window sync

After applying a click ([[sim-window-clicks]]), the sim reports the result with `sync-window`, which models vanilla's prediction protocol: the click's `changed` slots and `cursor` are the client's prediction; the server records them as what the client believes and sends only what differs.

## Key files
- `sim.clj`, `on-packet [:play :container-click]` - counts the click; ignores it if the window id is neither 0 nor the open table's, or its ordinal is in `:sim/drop-clicks`; else snapshots the full view (slot 0 included), applies the click, records any violation, and syncs with `(:cursor pkt)` as the predicted cursor and `stale?` = the packet's state id differs from that window's.
- `sim.clj`, `sync-window` - the answer, per window kind: window 0's state id is `:sim/state-id`, the table's is `[:sim/window :state-id]`.
- `sim.clj`, `give` - a pickup reuses `sync-window` for window 0 with the current cursor as the "prediction", so only slot changes go out.
- `packet.clj`, the `container-click` spec comment - the bot always predicts `:changed []` and `:cursor nil`.

## How it works
1. **Stale state id**: bump the state id once and send `container-set-content` for all 46 slots plus `carried` (the real cursor).
2. **Current state id**: for every slot 0-45 whose value changed, bump the state id and send `container-set-slot` with it; one click can move the state id several times (slot 0's computed result counts).
3. **Cursor**: send `set-cursor-item` only when the real cursor differs from the predicted one; with an empty claim, only when it is non-empty.

## Gotchas
- Because nothing is predicted for slots, every changed slot comes back; this is why `window/waiting` can detect an answer purely by the state id moving.
- A click that changes nothing produces no packets and no state id change: silence, then `:stale-window` ([[phantom-cursor-stale-window]]).

## Limits
The sim compares whole slot values; vanilla compares hashed components. Equivalent for the component-free items the bot handles today. Whether vanilla answers a current-id click per slot or with a full resync is discussed in [[mc-state-ids-prediction]].

## See also
- [[mc-state-ids-prediction]] - the vanilla model this copies.
