---
title: Window 0 slot layout
description: The vanilla 26.1.2 slot numbers of the player's own window (0 result, 1-4 grid, 5-8 armour, 9-35 main, 36-44 hotbar, 45 offhand) and how they map to player-inventory slots.
type: reference
tags: [game, windows, inventory, slots]
aliases: [inventory slots, window 0, hotbar slots 36-44, offhand 45, armour slots, player inventory slot numbers]
status: verified
lastUpdated: 2026-10-09
verifiedAgainst: 26.1.2
sourceRefs:
  - src/clojurecraft/inventory.clj#defn container->player-slot
  - test/clojurecraft/game_test.clj#window 5 is the helmet, player slot 39
  - src/clojurecraft/recipe.clj#defn player->container-slot
  - src/clojurecraft/inventory.clj#Window 0 is the player's own screen: slots 0-4 are the crafting grid (0 is the result)
  - src/clojurecraft/sim.clj#:items (mapv #(get-in sim [:sim/inv %]) (range 46)) :carried nil})
related:
  - "[[game/windows/_moc|Windows]]"
  - "[[window-zero-model]]"
  - "[[mc-container-click]]"
---

# Window 0 slot layout

Window 0 (the player's inventory screen, always open, never announced by `open-screen`) has 46 slots:

| Window slot | What | Player-inventory slot (`set-player-inventory`, `:player/inventory`) |
|---|---|---|
| 0 | crafting result | - |
| 1-4 | 2x2 crafting grid, reading order (1 2 / 3 4) | - |
| 5-8 | armour: head, chest, legs, feet | 39-36 (feet-first player numbering: 5 head → 39, 8 feet → 36) |
| 9-35 | main inventory | 9-35 (same numbers) |
| 36-44 | hotbar | 0-8 |
| 45 | offhand | 40 |

## Facts
- `container-set-content` for window 0 carries exactly 46 slots; the sim sends `(range 46)`.
- The player-inventory numbering (hotbar 0-8, main 9-35, armour 36-39, offhand 40) is what `set-player-inventory` uses; `inventory/container->player-slot` and `recipe/player->container-slot` are the two directions of the map.
- `set-carried-item` (held slot) takes a hotbar index 0-8, i.e. window slots 36-44.

## Gotchas
- The armour map follows vanilla's feet-first player numbering (36 feet .. 39 head), fixed on 2026-10-09 after the first version mapped window order straight across. It has not been observed live yet: equip a helmet and watch `set-player-inventory` before relying on it.
- A crafting table is a different window with its own id and layout ([[mc-crafting-table-window]]).

## See also
- [[window-zero-model]] - how the bot stores these.
