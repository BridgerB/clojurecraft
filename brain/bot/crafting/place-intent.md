---
title: Place intent
description: How :place puts a held block (a crafting table) into the world - equip by held slot or a hotbar-swap click, pick a spot two blocks away, settle, right-click the support's top face - and how it decides placed, rejected or no answer from the server's reports only.
type: reference
tags: [bot, crafting, intents, placement]
aliases: [:place, place.clj, spot, place a crafting table, rejected placement, no-spot]
status: verified
lastUpdated: 2026-10-09
verifiedAgainst: 5c7d6c1
sourceRefs:
  - src/clojurecraft/place.clj#defmethod intent/run :place
  - src/clojurecraft/place.clj#defmethod place-stage :equip
  - src/clojurecraft/place.clj#defmethod place-stage :sent
  - src/clojurecraft/place.clj#defn spot
  - src/clojurecraft/place.clj#defn- use-item-on
  - test/clojurecraft/place_test.clj#a-rejected-placement-fails-instead-of-believing
related:
  - "[[bot/crafting/_moc|Crafting]]"
  - "[[placement-judged-by-server]]"
  - "[[spot-two-blocks-away]]"
  - "[[sim-placement]]"
  - "[[mc-use-item-on]]"
---

# Place intent

`{:intent/kind :place :intent/item :crafting_table}` places one held block next to the bot. Stages dispatch through `place-stage` on `:intent/stage`; like `:craft`, the whole intent is gated on `window/waiting` because equipping can be a click.

## Stages
1. **`:equip`**: find the lowest player slot holding the item (none fails `:not-held`). Already the held slot → `:spot`. In the hotbar → `set-carried-item` to it, set `:player/held-slot`, `:spot`. In the main inventory → a mode-2 click on that slot with `button` = the held slot (swap into the hand), and stay in `:equip` until the answer arrives.
2. **`:spot`**: `spot` picks `[target support]` (none fails `:no-spot`).
3. **`:settle`**: look at the target's centre; after 3 still ticks and 300 ms, send `use-item-on` against the **support** block, face 1 (top), cursor `[0.5 1.0 0.5]`, with the next `:bot/sequence`, plus a swing; → `:sent`.
4. **`:sent`**: done when `game/block-at` at the target equals `memory/placed-state` of the item (the server's `block-update` wrote it). Fails `:rejected` once `:stats/last-ack` has reached our sequence and 500 ms have passed without the block. Fails `:no-answer` after 3000 ms.

## Key files
- `place.clj`, `spot` - pure: the first of twelve feet-level cells two blocks away (`around`) whose target is loaded, not solid and not liquid, whose support below is solid, and whose centre is within `place-reach` (4.0) of the eye.
- `place.clj`, `use-item-on` - the packet, sequence and timestamp.
- `place_test.clj` - a property (300 cases) that a spot always exists on flat ground, never overlaps the player, sits on its support, and is in reach; and that a rejection (block-update with air, then the ack) fails `:rejected` while a confirming block-update is done and remembered.

## Gotchas
- The local world is never written by the intent; only the server's `block-update` makes the table appear, and then memory records it (`memory/watched?` includes crafting tables).
- A swap click's answer changes slot state ids exactly like a craft click; the intent waits for it.

## See also
- [[open-container-intent]] - what follows a placement.
- [[sib-ghost-block-placement]] - why nothing is predicted.
