---
title: Sim window clicks
description: The vanilla click rules the sim implements over window 0, a crafting table and a furnace (pickup, place, swap, merge, right-click split and place-one, shift-click, hotbar swap, result take, slots that refuse an item), the three layouts, and what it records as a violation.
type: reference
tags: [bot, tooling, sim, crafting]
aliases: [sim click, click-view, layouts, sim/inv, sim/cursor, result-of, craft-once, insert, accepts?, own-slots]
status: verified
lastUpdated: 2026-10-11
verifiedAgainst: HEAD
sourceRefs:
  - src/clojurecraft/sim.clj#defmulti click-view
  - src/clojurecraft/sim.clj#defn click-kind
  - src/clojurecraft/inventory.clj#def layouts
  - src/clojurecraft/sim.clj#defn result-of
  - src/clojurecraft/sim.clj#defn insert
  - src/clojurecraft/sim.clj#defn view-of
  - src/clojurecraft/sim.clj#defn accepts?
  - src/clojurecraft/inventory.clj#defn own-slots
related:
  - "[[bot/crafting/_moc|Crafting]]"
  - "[[server-model]]"
  - "[[sim-window-sync]]"
  - "[[recipe-match]]"
  - "[[sim-furnace]]"
---

# Sim window clicks

The sim stores the player's window-0 slots in `:sim/inv` (1-44 meaningful), the cursor in `:sim/cursor`, and the open container in `:sim/window` (`{:id :kind :state-id}`, plus `:grid` for a table or `:pos` for a furnace). A click is applied by `click-view` to a **view**: a slot map of one window, built by `view-of` (window 0 is `:sim/inv` itself; a container is its own slots, `own-path`, plus the player's where its layout shows them). A crafting window's slot 0 is never stored: `result-of` computes it from the grid with `recipe/match`. A furnace's slots are stored at its position ([[sim-furnace]]).

## Layouts (`inventory/layouts`, shared with the bot's views)
| Kind | Grid size | Inventory slots (`store-slots`) | Hotbar starts (`hotbar-start`) |
|---|---|---|---|
| `:inventory` | 2 (slots 1-4) | 9-44 | 36 |
| `:table` | 3 (slots 1-9) | 10-45 | 37 |
| `:furnace` | none (input 0, fuel 1, output 2) | 3-38 | 30 |

## Rules (`click-view`, one method per `click-kind` of the layout and the click, returns `{:view :cursor :violation}`)
- **Slot 0 is a result only in a window that crafts** (`inventory/crafting?`); a furnace's slot 0 is its input and takes ordinary clicks.
- **Slot 0, mode 1 (shift)**: craft repeatedly, inserting each result into the store, until the grid stops matching or the result does not fit.
- **Slot 0, other modes**: with an empty or same-item cursor that can hold it, craft once onto the cursor; else nothing.
- **Slot 0 with no result**: violation `[:click-on-empty-result mode]`.
- **Mode 2 (hotbar swap)**: swap the slot with hotbar slot `hotbar + button`, unless the slot refuses the hotbar's item.
- **Mode 1 on the container's own slot** (`inventory/own-slots`: a grid cell, or a furnace's input, fuel or output): move the stack into the store, leftover stays. Elsewhere nothing.
- **Button 0**: pick up, put down, merge to 64 (remainder stays on the cursor), or swap.
- **Button 1**: empty cursor picks up half (rounded up); else place one into an empty or same-item, non-full slot.
- **A slot that refuses the cursor's item** (`accepts?`: a furnace's output takes nothing, its fuel slot only what burns or a bucket): either button takes what fits of the same item from the slot onto the cursor (`take-onto`), else nothing, as vanilla's `doClick` does when `mayPlace` is false.

## Gotchas
- `insert` fills same-item stacks then empty slots in store order, so a picked-up log lands in main slot 9 first rather than the hotbar as vanilla prefers. Bot code reads any slot.
- `max-stack` is 64 for every item; tools and unstackables are not modelled.
- Closing the table window returns its grid items to the inventory (as vanilla does) and syncs window 0; a furnace keeps its slots. Opening a container while another is open closes the old one first, with a `container-close` to the client (`open-window`).

## See also
- [[sim-window-sync]] - how the result of a click is reported back.
- [[sim-placement]] - how the table gets into the world and opens.
