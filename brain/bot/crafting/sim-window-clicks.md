---
title: Sim window clicks
description: The vanilla click rules the sim implements over window 0 and a crafting table (pickup, place, swap, merge, right-click split and place-one, shift-click, hotbar swap, result take), the two layouts, and what it records as a violation.
type: reference
tags: [bot, tooling, sim, crafting]
aliases: [sim click, click-view, layouts, sim/inv, sim/cursor, result-of, craft-once, insert]
status: verified
lastUpdated: 2026-10-09
verifiedAgainst: 5c7d6c1
sourceRefs:
  - src/clojurecraft/sim.clj#defn- click-view
  - src/clojurecraft/sim.clj#def layouts
  - src/clojurecraft/sim.clj#defn- result-of
  - src/clojurecraft/sim.clj#defn- insert
  - src/clojurecraft/sim.clj#defn- view-of
related:
  - "[[bot/crafting/_moc|Crafting]]"
  - "[[server-model]]"
  - "[[sim-window-sync]]"
  - "[[recipe-match]]"
---

# Sim window clicks

The sim stores the player's window-0 slots in `:sim/inv` (1-44 meaningful), the cursor in `:sim/cursor`, and an open crafting table in `:sim/window` (`{:id :grid :state-id}`). A click is applied by `click-view` to a **view**: a slot map of one window, built by `view-of` (window 0 is `:sim/inv` itself; the table is its grid plus the inventory shifted up by one). Slot 0 is never stored: `result-of` computes it from the grid with `recipe/match`.

## Layouts (`layouts`)
| Kind | Grid size | Inventory slots (`:store`) | Hotbar starts |
|---|---|---|---|
| `:inventory` | 2 (slots 1-4) | 9-44 | 36 |
| `:table` | 3 (slots 1-9) | 10-45 | 37 |

## Rules (`click-view`, returns `{:view :cursor :violation}`)
- **Slot 0, mode 1 (shift)**: craft repeatedly, inserting each result into the store, until the grid stops matching or the result does not fit.
- **Slot 0, other modes**: with an empty or same-item cursor that can hold it, craft once onto the cursor; else nothing.
- **Slot 0 with no result**: violation `[:click-on-empty-result mode]`.
- **Mode 2 (hotbar swap)**: swap the slot with hotbar slot `hotbar + button`.
- **Mode 1 on a grid cell**: move the stack into the store, leftover stays. Elsewhere nothing.
- **Button 0**: pick up, put down, merge to 64 (remainder stays on the cursor), or swap.
- **Button 1**: empty cursor picks up half (rounded up); else place one into an empty or same-item, non-full slot.

## Gotchas
- `insert` fills same-item stacks then empty slots in store order, so a picked-up log lands in main slot 9 first rather than the hotbar as vanilla prefers. Bot code reads any slot.
- `max-stack` is 64 for every item; tools and unstackables are not modelled.
- Closing the table window returns its grid items to the inventory (as vanilla does) and syncs window 0.

## See also
- [[sim-window-sync]] - how the result of a click is reported back.
- [[sim-placement]] - how the table gets into the world and opens.
