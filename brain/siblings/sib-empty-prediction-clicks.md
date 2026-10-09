---
title: Empty-prediction clicks
description: How the siblings keep a window server-authoritative: ruststeve sends empty changedSlots so every click gets corrections and waits for 100 ms of quiet; typecraft forces a full resend with a bogus state id.
type: reference
tags: [siblings, ruststeve, steve, windows, protocol]
aliases: [changedSlots empty, authoritative window, resyncInventory, wait_for_inventory_ack, replies lag one click]
status: verified
lastUpdated: 2026-10-09
verifiedAgainst: steve c28028b, ruststeve bc575e3
sourceRefs:
  - "ruststeve:src/bot/inventory.rs#// Send EMPTY changedSlots so the server ALWAYS sees a prediction mismatch and"
  - "ruststeve:src/bot/inventory.rs#pub(crate) async fn wait_for_inventory_ack(&mut self, timeout: Duration) -> std::io::Result<()> {"
  - "ruststeve:src/bot/inventory.rs#let cursor = to_notch(registry, window.selected_item.as_ref());"
  - "steve:src/lib/typecraft/bot/inventory.ts#bot.resyncInventory = async (): Promise<boolean> => {"
  - src/clojurecraft/window.clj#defn click
related:
  - "[[siblings/_moc|Siblings]]"
  - "[[mc-state-ids-prediction]]"
  - "[[phantom-cursor-stale-window]]"
  - "[[sib-stale-window-clicks]]"
---

# Empty-prediction clicks

A 26.x `container-click` carries the client's prediction (changed slots, cursor). The server compares and corrects; it never kicks for a wrong guess. Both siblings exploit that to stop trusting their own window model.

## Key files
- ruststeve `src/bot/inventory.rs`, `click_window` - applies the click locally, then sends `changedSlots` empty "so the server ALWAYS sees a prediction mismatch and replies with a full, AUTHORITATIVE container_set_content"; the optimistic prediction had desynced under load into missing-ingredient and result-never-appeared failures. It does send its predicted cursor (`to_notch(... window.selected_item ...)`).
- the same file, `wait_for_inventory_ack` - waits for a slot/content update, then keeps reading until the window is quiet for 100 ms: "one click can draw several packets ... and returning on the first left the rest to be read as the NEXT click's ack. The client then ran one click behind the server (... planks pile into grid slot 3, the result is an oak_button)."
- typecraft `bot/inventory.ts`, `resyncInventory` - vanilla has no "send my inventory" packet, but a click whose state id does not match makes the server ignore it and resend the whole container; it clicks the always-empty slot 0 with a bogus state id and an empty cursor.

## What they learned
1. Never trust the local prediction; make the server send its truth after every click.
2. One click can produce several packets; an ack scheme that consumes the first one runs a click behind.
3. A stale state id is a resync request, not an error.

## What it means here
Our `click` sends `:changed []` and `:cursor nil`, and gates the next click on `:window/state-id` moving, so there is no local prediction to drift. Claiming an empty cursor has a cost the siblings did not pay because ruststeve claims its real cursor: the server then omits `set-cursor-item` when the real cursor is empty, so we drop `:window/cursor` ourselves on every click ([[phantom-cursor-stale-window]], [[mc-state-ids-prediction]]).

## Limits
Whether vanilla applies or ignores a stale-state-id click is stated differently by the two siblings' comments (typecraft says ignore); this note does not settle it.

## See also
- [[sib-stale-window-clicks]]
