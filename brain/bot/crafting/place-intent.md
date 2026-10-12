---
title: Place intent
description: How :place puts a held block (a crafting table) into the world - equip by held slot or a hotbar-swap click, pick a spot in rings 1-3 around the feet, settle, right-click the support's top face - and how it decides placed, rejected or no answer from the server's reports only.
type: reference
tags: [bot, crafting, intents, placement]
aliases: [:place, place.clj, spot, place a crafting table, rejected placement, no-spot]
status: verified
lastUpdated: 2026-10-09
verifiedAgainst: 85e133d
sourceRefs:
  - src/clojurecraft/place.clj#defmethod intent/run :place
  - src/clojurecraft/place.clj#defmethod place-stage :equip
  - src/clojurecraft/place.clj#defmethod place-stage :sent
  - src/clojurecraft/place.clj#defn spot
  - src/clojurecraft/place.clj#defn passage?
  - src/clojurecraft/place.clj#defn niche
  - test/clojurecraft/sim_test.clj#a-table-and-a-furnace-placed-in-a-staircase-never-plug-it
  - test/clojurecraft/place_test.clj#a-niche-is-cut-in-the-side-wall-not-ahead
  - src/clojurecraft/place.clj#defmethod intent/run :equip
  - test/clojurecraft/place_test.clj#no-niche-where-the-tools-cannot-drop
  - test/clojurecraft/place_test.clj#a-block-is-never-placed-on-a-table
  - test/clojurecraft/place_test.clj#a-block-dug-again-at-once-may-go-in-a-passage
  - src/clojurecraft/place.clj#defn use-item-on
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
1. **`:equip`** (`place/equip`, shared with the scoop): find the lowest player slot holding the item (none fails `:not-held`). Already the held slot → `:spot`. In the hotbar → `set-carried-item` to it, set `:player/held-slot`, `:spot`. In the main inventory → a mode-2 click on that slot with `button` = the held slot (swap into the hand), and stay in `:equip` until the answer arrives.
2. **`:spot`**: `spot` picks `[target support]`, never a `passage?` (a cell the feet could stand in with two or more solid horizontal neighbours: a staircase or tunnel, the way the bot came). With none, `niche`: a cell beside the feet at their level, solid with solid under and over it, no fluid beside it, not a block the bot placed, the one nearest the landing first (a side wall, never the wall ahead where a tunnel goes on: batch iron-a2 run 1 put its table there and its tunnel dug through it, `a-niche-is-cut-in-the-side-wall-not-ahead`); it is dug with a child `:dig` (stage `:niche`), then the item is equipped again and the spot sought again. Neither: `:no-spot`. Gym batch iron-a1 run 2 put its table on the stair behind it at the bottom of its staircase, then found no spot for the furnace (`a-table-and-a-furnace-placed-in-a-staircase-never-plug-it`).
3. **`:settle`**: look at the target's centre; after 3 still ticks and 300 ms, send `use-item-on` against the **support** block, face 1 (top), cursor `[0.5 1.0 0.5]`, with the next `:bot/sequence`, plus a swing; → `:sent`.
4. **`:sent`**: done when `terrain/block-at` at the target is a state of the item's block, by name (the server's `block-update` wrote it; a furnace's state depends on which way the server faced it). Fails `:rejected` once `:stats/last-ack` has reached our sequence and 500 ms have passed without the block. Fails `:no-answer` after 3000 ms.

## Equip
`{:intent/kind :equip :intent/item :stone_pickaxe}` is `equip` alone: the item into the held slot (a hotbar select, or a window-0 swap from the main inventory), done once it is there. Staircases and the mine act start one when the tool a dig needs is only in the main inventory ([[dig-timeline]]).

## Key files
- `place.clj`, `spot` - pure: the first cell of `around` (rings 1-3, feet level then one down then one up) whose target is loaded, not solid and not liquid, whose support below is solid, which does not intersect the player (`inside-player?`), and whose centre is within `place-reach` (4.0) of the eye ([[spot-two-blocks-away]]).
- `place.clj`, `use-item-on` - the packet, sequence and timestamp.
- `place_test.clj` - a property (300 cases) that a spot always exists on flat ground, never overlaps the player, sits on its support, and is in reach; and that a rejection (block-update with air, then the ack) fails `:rejected` while a confirming block-update is done and remembered.

## Gotchas
- **A passage is fine for a table.** The `:place-table` row says `:goal/passage-ok? true`: the table is taken along when the bot goes on (`make/take-table`), so the way is free again. A plain spot is tried first, then a passage, then a niche. Gym batch solid-b1 run 6 broke its pickaxe in a stairwell one wide, where the only spot was the stair and no niche could be cut without a pickaxe, and failed `:no-spot` three times (`a-table-may-go-in-the-passage-a-furnace-not`).
- **A passage is fine for a block dug again at once.** The redig's place says `:intent/passage-ok?`: its gravel is dug again straight away and leaves the way open. Gym batch iron-a6 run 1, at the foot of its stairs between its table and furnace, had no spot for the gravel but the stair behind it and failed `:no-spot` (`a-block-dug-again-at-once-may-go-in-a-passage`).
- **Never on a table.** A right-click on a crafting table or a furnace opens it instead of placing against it, so `spot` never takes one as support: gym batch iron-a5 run 4's only spot was on its table, and the gravel it meant to dig again for flint was never placed (`:rejected` three times, `a-block-is-never-placed-on-a-table`).
- The local world is never written by the intent; only the server's `block-update` makes the table appear, and then memory records it (`memory/watched?` includes tables and furnaces, `memory/placed-kinds`), and `make/near` finds either within 24 blocks.
- A swap click's answer changes slot state ids exactly like a craft click; the intent waits for it.

## See also
- [[open-container-intent]] - what follows a placement.
- [[sib-ghost-block-placement]] - why nothing is predicted.
