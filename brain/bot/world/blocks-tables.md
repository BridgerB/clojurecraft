---
title: Block tables
description: How blocks.clj turns the generated tables into a state→row map and sets of state ids (row, name-of, type-of, solid?, log?, leaves?), what passable-types and falling-types contain, and how log items are recognised.
type: reference
tags: [bot, world, blocks, data]
aliases: [blocks.clj, solid?, log?, passable-types, falling-types, name-of, items table, entity-types]
status: verified
lastUpdated: 2026-10-11
verifiedAgainst: HEAD
sourceRefs:
  - src/clojurecraft/blocks.clj#def passable-types
  - src/clojurecraft/blocks.clj#def falling-types
  - src/clojurecraft/blocks.clj#defn solid?
  - src/clojurecraft/blocks.clj#defn replaceable?
  - src/clojurecraft/blocks.clj#def replaceable-names
  - src/clojurecraft/blocks.clj#defn states-where
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

`blocks.clj` loads three generated tables and precomputes, from the block table, a map from state id to its row and a set of state ids per question (`states-where`). Each predicate is one set lookup ([[sets-over-arrays]] has the measurement that replaced arrays).

## Key files
- `blocks.clj`, `table` / `items` / `entity-types` - `blocks.edn` rows `[name type min-state max-state]`, `items.edn` `{name id}`, `entity-types.edn` `{name id}`.
- `blocks.clj`, `row`, `name-of`, `type-of` - `rows`, a map from state id to its row.
- `blocks.clj`, `replaceable?` - `replaceable-states`: every state of the blocks in `replaceable-names`, air and the grasses, ferns, dead bush, seagrass, vines, roots and lichen the game's replaceable tag lists; a flower, a flower bed (wildflowers, leaf litter), a bush or a sapling is not one. A placement goes only into these ([[place-intent]], [[sim-placement]]).
- `blocks.clj`, `solid?` - `solid-states`: every state whose definition type is not in `passable-types`; an id the table does not know is not solid.
- `blocks.clj`, `passable-types` - about 100 definition types with no full-cube collision: air, liquids, plants, crops, torches, rails, buttons, pressure plates, carpets, signs, ladders, snow layers, fire, lily pads and similar.
- `blocks.clj`, `falling-types` - the definition types that fall when the cell under them is free: the 26.1.2 jar's `FallingBlock` subclasses (sand and red sand, gravel, concrete powder, anvils, the dragon egg) and `BrushableBlock` (suspicious sand and gravel). The server model makes them fall ([[server-model]]).
- `blocks.clj`, `log?` / `log-items` - a block state or item is a log when its name ends in `_log`.
- `blocks.clj`, `leaves?` - a block state whose name ends in `_leaves`; used for the leaf dig time and the collect blocker ([[leaf-canopy-over-the-drop]]).

## Gotchas
- **A nether portal is passable** (`:nether_portal` in `passable-types`): the body walks into one to change dimension, and a route must be able to end in it (#8). It counted as solid before.
- `grass` (grass_block's type) must stay out of `passable-types` ([[grass-type-is-grass]]).
- `solid?` is an approximation: slabs, stairs, fences, walls, doors, leaves and snow blocks are full cubes; `snow_layer` is passable whatever its height.
- `solid?` of an unknown id (-1, beyond the table) is false, but `terrain/solid-fn` treats an *unloaded* block (nil) as solid; the two are different questions.
- `_log` includes `stripped_*_log` but not `*_wood`, `crimson_stem` or `warped_stem`, unlike the `#logs` item tag the recipes use ([[any-log-species]]).
- `air` is 0 and `item-entity-type` is the `:item` entity id (71 at 26.1.2).

## See also
- [[mc-block-ids]] - the concrete ids.
- [[land-physics]] - the consumer.
