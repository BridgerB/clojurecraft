---
title: Smelt intent
description: The :smelt intent's five stages (settle, load, cook, take, close) in an open furnace, how much fuel it loads and in what order, the fuel top-up, and how it recovers a lost click from the server's own answer instead of failing.
type: reference
tags: [bot, crafting, intents, furnace, smelting]
aliases: [:smelt, smelt.clj, burn-order, batch, fuel-clicks, lay, lost click, resync, intent/resync, intent/lost]
status: verified
lastUpdated: 2026-10-11
verifiedAgainst: HEAD
sourceRefs:
  - src/clojurecraft/smelt.clj#defmethod intent/run :smelt
  - src/clojurecraft/smelt.clj#def burn-order
  - src/clojurecraft/smelt.clj#defn batch
  - src/clojurecraft/smelt.clj#defn fuel-clicks
  - src/clojurecraft/smelt.clj#defn lay
  - src/clojurecraft/smelt.clj#defmethod stage :cook
  - src/clojurecraft/smelt.clj#defn lost
  - "src/clojurecraft/window.clj#Learn the server's cursor after a lost click"
  - src/clojurecraft/window.clj#defn lost
  - src/clojurecraft/window.clj#defn resync-click
  - src/clojurecraft/make.clj#defmethod plan/act :smelt
  - test/clojurecraft/sim_test.clj#four-raw-iron-and-a-coal-become-four-ingots-in-a-placed-furnace
  - test/clojurecraft/sim_test.clj#a-second-stack-of-fuel-is-loaded-when-the-first-burns-out
  - test/clojurecraft/sim_test.clj#every-lost-smelt-click-is-recovered-from-the-servers-slots
  - test/clojurecraft/props_test.clj#the-smelt-never-closes-with-a-loaded-cursor-or-clicks-outside-the-window
related:
  - "[[bot/crafting/_moc|Crafting]]"
  - "[[craft-intent]]"
  - "[[sim-furnace]]"
  - "[[sib-smelt-fuel-deadlock]]"
  - "[[make-goals]]"
---

# Smelt intent

`{:intent/kind :smelt :intent/smelt :iron_ingot_from_smelting_raw_iron :intent/count 4}` cooks up to count of a furnace recipe's input in the open furnace and takes the result. Like [[craft-intent]], it is an `intent/run` method with a `stage` multimethod, one click per round trip, and absolute deadlines; it runs only while a furnace window is open (`window/view :furnace`, else it fails `:no-furnace-window`).

## Who starts it
The needs planner's generated smelt rows (one per `smelting.edn` recipe: the input, `:fuel/ticks` for it and `:block/furnace :near` as needs, the result as what it provides; [[make-goals]]) act with `:smelt`, so a smelt is chosen only once the fuel is held. The act sizes the batch with `smelt/batch`: all the input held, up to a stack, as far as the held fuel cooks (the input itself never counts as fuel); a batch of 0 waits `:no-fuel`. It then goes through `make/at-container`, shared with the table craft: the intent once the furnace window is open, else open the remembered furnace within reach, else walk to it.

## Fuel
- `burn-order`: coal and charcoal, then planks, each only where `fuel.edn` burns it (crimson and warped planks do not). Never a log: it burns 300 ticks, the four planks it makes 1200, so the planner turns logs into planks first. Never a stick: it is a pickaxe's handle and burns 100 ticks, half one smelt. Counted as fuel, the ingots' fuel took the two sticks a new stone pickaxe needed deep in the mine, the planner sent the bot up for wood instead, and it dug coal with its last pickaxe until it broke (issue #36, race 38477665442 bot Clj_r20). Tools, tables and the rest of the fuel table are never burned. The planner nets fuel in this same order (`make/burn`), so what it reserved is what the intent burns.
- `fuel-clicks` loads from one held stack, the first in burn-order, only as many as the batch needs (`ceil(ticks / burn)`): four raw iron take one coal; three take two planks, not the stack. Wood burned here is wood the tools lack ([[sib-smelt-fuel-deadlock]]).
- `lay`: a whole stack is two clicks (pick up, put down); part of one is a pick up, a right click per item, and the rest put back.

## Stages
1. **`:settle`**: an unknown recipe fails `:unknown-recipe`; no state id yet waits up to 5 s (`:no-window`); a loaded cursor is put down; with no slot free, a junk stack is thrown out (`window/tossable`, [[window-views]]; race 38805920624 bot Clj_r09 shift-clicked a furnace's leftover output into 36 full slots and failed `:inventory-full`, issue #192); anything in the furnace's input, fuel or output is shift-clicked out; then `load-plan` (fuel, then input; nil fails `:no-ingredient`) records the clicks, the batch (capped by the input stack), `:intent/result`, `:intent/makes` and `:intent/before`.
2. **`:load`**: the next planned click, or on to `:cook`.
3. **`:cook`**: done cooking when the output holds the batch → `:take`. First it waits (5 s, else `:not-loaded`) until the furnace holds the input and either fuel or a fire (`loaded?`), and sets `:intent/cook-by` to now + input × cook ticks × 50 ms + 5 s. When the fire is out, the fuel slot empty and input left, it lays fuel from the next held stack and returns through `:load` (`:out-of-fuel` when none). Past `cook-by` fails `:not-cooked`.
4. **`:take`**: shift-click the output until the inventory holds before + makes (5 s, else `:not-taken`).
5. **`:close`**: put a loaded cursor down, shift-click anything left out, then `container-close`: done.

## A lost click
A furnace's own ticks move the window's state id, and what a failed smelt leaves on the cursor or in the furnace is not in the inventory the planner plans from, so a planner retry could not find it. So an unanswered click (`window/waiting` overdue) is recovered inside the intent (`lost`, which calls `window/lost`): the next click is a resync, then a lost take or close carries on and a lost settle or load click starts over at `:settle`. The third loss fails `:stale-window`.

The resync (`window/resync`, `window/resync-click`) puts the cursor down into a free slot with state id -1. 26.1.2's `handleContainerClick` applies any click and, when its state id is not the menu's, answers with `broadcastFullState`: the whole window and the real cursor, under a fresh state id ([[mc-state-ids-prediction]]). So the bot learns the cursor whether the click moved anything or not; `window/click` had forgotten it as it sent. Without the resync, losing the click that lays the coal left the coal on the server's cursor, unseen, and the plan failed `:no-fuel`.

`every-lost-smelt-click-is-recovered-from-the-servers-slots` loses each of the smelt's clicks in turn and requires four ingots and no violation every time. `the-smelt-never-closes-with-a-loaded-cursor-or-clicks-outside-the-window` (200 cases) feeds the intent any furnace packets in any order and holds that it closes only with an empty cursor and clicks only the furnace window's own slots ([[property-tests]]).

## Gotchas
- The planner plans the whole target at once: two raw iron never start a smelt toward four ingots; the batch only covers what the planner chose.
- A smelt abandoned mid-cook (a higher-priority target, an escape) closes the furnace with the batch inside; the planner does not know what a furnace holds, so that input is lost to the plan. Not yet seen in a run.

## See also
- [[sim-furnace]] - what it is tested against.
- [[craft-intent]] - the craft's stages, which this follows.
