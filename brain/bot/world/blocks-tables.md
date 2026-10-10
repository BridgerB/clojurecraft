---
title: Block tables
description: How blocks.clj turns the generated tables into id-indexed arrays (row, name-of, type-of, solid?, log?), what passable-types contains, and how log items are recognised.
type: reference
tags: [bot, world, blocks, data]
aliases: [blocks.clj, solid?, log?, passable-types, name-of, items table, entity-types]
status: verified
lastUpdated: 2026-10-09
verifiedAgainst: 5c7d6c1
sourceRefs:
  - src/clojurecraft/blocks.clj#def passable-types
  - src/clojurecraft/blocks.clj#defn solid?
  - src/clojurecraft/blocks.clj#defn log-name?
  - src/clojurecraft/blocks.clj#def log-items
  - src/clojurecraft/blocks.clj#defn leaves?
  - test/clojurecraft/blocks_test.clj#deftest solidity
related:
  - "[[bot/world/_moc|World]]"
  - "[[mc-block-ids]]"
  - "[[grass-type-is-grass]]"
  - "[[datagen]]"
---

# Block tables

`blocks.clj` loads three generated tables and precomputes arrays indexed by block state id, so the physics hot path is one bounds check and one array read.

## Key files
- `blocks.clj`, `table` / `items` / `entity-types` - `blocks.edn` rows `[name type min-state max-state]`, `items.edn` `{name id}`, `entity-types.edn` `{name id}`.
- `blocks.clj`, `row`, `name-of`, `type-of` - an object array from state id to its row.
- `blocks.clj`, `solid?` - a boolean array: every state whose definition type is not in `passable-types` is solid; ids outside 0..max-state are not solid.
- `blocks.clj`, `passable-types` - about 100 definition types with no full-cube collision: air, liquids, plants, crops, torches, rails, buttons, pressure plates, carpets, signs, ladders, snow layers, fire, lily pads and similar.
- `blocks.clj`, `log?` / `log-items` - a block state or item is a log when its name ends in `_log`.
- `blocks.clj`, `leaves?` - a block state whose name ends in `_leaves`; used for the leaf dig time and the collect blocker ([[leaf-canopy-over-the-drop]]).

## Gotchas
- `grass` (grass_block's type) must stay out of `passable-types` ([[grass-type-is-grass]]).
- `solid?` is an approximation: slabs, stairs, fences, walls, doors, leaves and snow blocks are full cubes; `snow_layer` is passable whatever its height.
- `solid?` of an unknown id (-1, beyond the table) is false, but `terrain/solid-fn` treats an *unloaded* block (nil) as solid; the two are different questions.
- `_log` includes `stripped_*_log` but not `*_wood`, `crimson_stem` or `warped_stem`, unlike the `#logs` item tag the recipes use ([[any-log-species]]).
- `air` is 0 and `item-entity-type` is the `:item` entity id (71 at 26.1.2).

## See also
- [[mc-block-ids]] - the concrete ids.
- [[land-physics]] - the consumer.
