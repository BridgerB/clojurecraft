---
title: Sim furnace
description: How the server model's furnace opens, takes clicks, cooks by the game's smelting and fuel tables every tick whether or not its window is open, and tells the client - the rules checked against 26.1.2's AbstractFurnaceBlockEntity.
type: reference
tags: [bot, tooling, sim, furnace, smelting]
aliases: [sim/furnaces, cook, tick-furnaces, open-furnace, furnace-data, accepts?, take-onto, can-burn?, reset-on-new-input]
status: verified
lastUpdated: 2026-10-11
verifiedAgainst: HEAD
sourceRefs:
  - src/clojurecraft/sim.clj#defn cook
  - src/clojurecraft/sim.clj#defn reset-on-new-input
  - src/clojurecraft/sim.clj#defn tick-furnaces
  - src/clojurecraft/sim.clj#defn open-furnace
  - src/clojurecraft/sim.clj#defn furnace-data
  - src/clojurecraft/sim.clj#defn accepts?
  - src/clojurecraft/sim.clj#defn take-onto
  - test/clojurecraft/sim_test.clj#four-raw-iron-cook-on-one-coal-in-200-ticks-each
  - test/clojurecraft/sim_test.clj#a-plank-runs-dry-after-one-ingot-and-the-progress-unwinds
  - test/clojurecraft/sim_test.clj#a-furnace-cooks-with-its-window-closed
  - test/clojurecraft/sim_test.clj#the-furnace-click-rules
related:
  - "[[bot/crafting/_moc|Crafting]]"
  - "[[server-model]]"
  - "[[sim-window-clicks]]"
  - "[[sib-smelt-fuel-deadlock]]"
---

# Sim furnace

The server model keeps every furnace it has opened in `:sim/furnaces`, `{pos {:slots {0 input 1 fuel 2 output} :lit :lit-total :cook :cook-total :cooking}}`. A furnace's slots live at its position, not on the window, so closing the window keeps them, and `tick` cooks in every furnace each game tick, open or not.

## Key files
- `sim.clj`, `open-furnace` - `use-item-on` against a furnace ([[sim-placement]]) makes one at that position if the model has none, opens window kind `:furnace` (menu type 14, 39 slots: input, fuel, output, then the player's 36 from slot 3) and sends all four properties.
- `sim.clj`, `cook` - one game tick of `AbstractFurnaceBlockEntity.serverTick`, read from the 26.1.2 jar with `javap`: a lit furnace burns down one tick; when lit, or holding both fuel and input, and the input can cook (`can-burn?`: a recipe in `smelting.edn` takes it and the output has room), an unlit furnace lights for the fuel's ticks from `fuel.edn` and takes one fuel; lit and able, progress grows by one, and at the cook total one input becomes the result; lit but unable, progress resets; with neither fuel nor lit, progress unwinds by two (`Mth.clamp(timer - 2, 0, total)`).
- `sim.clj`, `reset-on-new-input` - the game's `setItem` on slot 0: a different item resets progress and sets the cook total from its recipe (200 when none); `:cooking` remembers which item the progress is for.
- `sim.clj`, `tick-furnaces` - for the open furnace, after cooking: a `container-set-slot` per changed slot (each moves the state id, as a click's answer does), then a `container-set-data` per property that changed ([[sim-window-sync]]).
- `sim.clj`, `furnace-data` - the properties as the wire numbers them, read from the furnace layout's `:properties`: `:lit` 0 (fire left), `:lit-total` 1, `:cook` 2 (progress), `:cook-total` 3 ([[window-zero-model]]); the furnace's own slots are `furnace-input`, `furnace-fuel` and `furnace-output` (`inventory/slot-in`).
- `sim.clj`, `accepts?` and `take-onto` - the furnace's slot rules for clicks ([[sim-window-clicks]]): nothing is put into the output, the fuel slot takes only what burns (or a bucket), and a cursor a slot refuses takes what fits of the same item from it, which is how a left click empties the output.

## Timing the tests pin
- Four raw iron on one coal: three ingots at tick 799, four at tick 800, the coal lit at the first tick with 801 ticks left.
- Three raw iron on one plank (300 ticks): one ingot, the second at 100 of 200 when the plank burns out at tick 300; then progress falls 2 a tick to 0 by tick 350, and two raw iron wait for fuel. This is steve's three-ingot wall ([[sib-smelt-fuel-deadlock]]) in miniature.
- A furnace closed after loading still cooks: opened again 200 ticks later, the output holds the ingot.

## Gotchas
- A furnace's ticking moves the window's state id with nobody clicking. A client that judges a click answered by the state id moving sees the furnace's own updates too, so it must check the slot it clicked, not just the state id.
- The model's tick is one game tick per `sim/step` tick event (`run` sends one every 50 ms), the same unit the breath uses.

## Limits
A lava bucket's empty bucket left in the fuel slot, experience, the block's lit state and hopper inputs are not modelled. A furnace that was never opened does not tick (nothing is in it).

## See also
- [[sim-window-clicks]] - the click rules the furnace shares.
- [[server-model]] - the rest of the model.
