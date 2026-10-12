---
title: Land physics
description: The pure vanilla movement step over :player/* attributes, its constants, and how the solidity oracle treats unknown and passable blocks.
type: reference
tags: [bot, world, physics]
aliases: [movement, collision, solid?, controls]
status: verified
lastUpdated: 2026-10-11
verifiedAgainst: HEAD
sourceRefs:
  - src/clojurecraft/physics.clj#defn step
  - src/clojurecraft/physics.clj#def ground-acceleration
  - src/clojurecraft/terrain.clj#defn solid-fn
  - src/clojurecraft/blocks.clj#def passable-types
related:
  - "[[bot/world/_moc|World]]"
  - "[[grass-type-is-grass]]"
  - "[[resting-vertical-velocity]]"
---

# Land physics

`(physics/step solid? world controls)` is one vanilla tick: jump, input acceleration along yaw, per-axis AABB sweep (y, then x, then z) against full-cube solids, then gravity, drag and friction. Controls are `{:control/forward? :control/jump? :control/yaw :control/look}`.

## Key files
- `physics.clj`, `step` - the tick; writes `:player/pos :player/vel :player/on-ground? :player/horizontal-collision? :player/jump-ticks`.
- `physics.clj`, constants - gravity 0.08, vertical drag 0.98, ground inertia 0.546, air 0.91, jump 0.42, AABB 0.6 by 1.8, eye 1.62; `ground-acceleration` is 0.1 times 0.16277136 over inertia cubed. `tick-ms` (50) is the one game tick every per-tick constant counts in, and the only place the bot, the server model and the main loop read its length; `forward-input` 0.98 (a held forward key), `jump-cooldown` 10 ticks, `bank-probe` 0.6 (how far up the box is moved to ask whether a bank's top is free).
- `terrain.clj`, `solid-fn` - unknown (unloaded) blocks are solid.
- `blocks.clj`, `passable-types` - block definition types with no full-cube collision; everything else is solid.

## How it works
1. Steady walking speed is 0.216 blocks per tick (4.3 m/s), reached after about a second.
2. A one-block ledge needs a jump; there is no step-up.
3. Liquids are passable to the sweep; what the body does in them is the liquid branch of the same step ([[fluid-physics]]).

## Gotchas
- `grass_block`'s definition type is `grass`; it must stay solid ([[grass-type-is-grass]]).
- The on-ground flag sent to the server is `0x01`; typecraft writes `0x80`, which is wrong.

## Limits
No step-up, no non-full-cube shapes (slabs, fences are treated as full cubes); sneaking only sinks in a liquid. Fluids are [[fluid-physics]].

## See also
- [[goals-and-intents]] - who writes the controls.
- [[fluid-physics]] - the step in water and lava.
