---
title: Land physics
description: The pure vanilla movement step over :player/* attributes, its constants, how the collision oracle treats unknown, passable and flat blocks, and the step onto a carpet.
type: reference
tags: [bot, world, physics]
aliases: [movement, collision, solid?, controls, rise-onto, step-height, collision-top, flat-shape, carpet]
status: verified
lastUpdated: 2026-10-18
verifiedAgainst: HEAD
sourceRefs:
  - src/clojurecraft/physics.clj#defn step
  - src/clojurecraft/physics.clj#def ground-acceleration
  - src/clojurecraft/physics.clj#def step-height
  - src/clojurecraft/physics.clj#defn rise-onto
  - src/clojurecraft/physics.clj#defn solid-boxes
  - src/clojurecraft/terrain.clj#defn solid-fn
  - src/clojurecraft/blocks.clj#def passable-types
  - src/clojurecraft/blocks.clj#def flat-shape
  - src/clojurecraft/blocks.clj#defn collision-top
  - test/clojurecraft/physics_test.clj#a-carpet-is-stood-on-and-stepped-onto
related:
  - "[[bot/world/_moc|World]]"
  - "[[grass-type-is-grass]]"
  - "[[resting-vertical-velocity]]"
---

# Land physics

`(physics/step solid? world controls)` is one vanilla tick: jump, input acceleration along yaw, per-axis AABB sweep (y, then x, then z) against full-cube solids and the flat boxes of carpets, a step onto a flat box the sweep stopped at (`rise-onto`), then gravity, drag and friction. Controls are `{:control/forward? :control/jump? :control/yaw :control/look}`.

## Key files
- `physics.clj`, `step` - the tick; writes `:player/pos :player/vel :player/on-ground? :player/horizontal-collision? :player/jump-ticks`.
- `physics.clj`, constants - gravity 0.08, vertical drag 0.98, ground inertia 0.546, air 0.91, jump 0.42, AABB 0.6 by 1.8, eye 1.62; `ground-acceleration` is 0.1 times 0.16277136 over inertia cubed. `tick-ms` (50) is the one game tick every per-tick constant counts in, and the only place the bot, the server model and the main loop read its length; `forward-input` 0.98 (a held forward key), `jump-cooldown` 10 ticks, `bank-probe` 0.6 (how far up the box is moved to ask whether a bank's top is free).
- `terrain.clj`, `solid-fn` - the collision oracle: 1.0 for a full cube, the top of a flat shape's box, nil for nothing; unknown (unloaded) blocks are full cubes.
- `blocks.clj`, `passable-types` - block definition types with no full-cube collision; everything else is solid. `collision-top` gives a flat shape among them (`partial-shapes` no taller than `flat-shape`, 0.125: a carpet, a pale moss carpet, 0.0625) its box, which `physics/solid-boxes` builds up to that top.
- `physics.clj`, `rise-onto` - `step-height` 0.6 (vanilla's maxUpStep): a body on the ground stopped by something lower than that is lifted onto it, moved along and set down; a full block is never stepped onto.

## How it works
1. Steady walking speed is 0.216 blocks per tick (4.3 m/s), reached after about a second.
2. A one-block ledge needs a jump; the rise onto a low box takes only what is under `step-height`, a carpet.
3. Liquids are passable to the sweep; what the body does in them is the liquid branch of the same step ([[fluid-physics]]).
4. A carpet holds the feet a sixteenth over its block, where the server holds them: a body whose feet are inside the carpet has every move refused and is teleported back each tick (gym batch stronghold-265c run 3, a pale garden, 2021 teleports and no step for three minutes, every walk `:no-path`).

## Gotchas
- `grass_block`'s definition type is `grass`; it must stay solid ([[grass-type-is-grass]]).
- The on-ground flag sent to the server is `0x01`; typecraft writes `0x80`, which is wrong.

## Limits
No non-full-cube shapes but the flat ones (slabs, fences are treated as full cubes, which the server accepts: a body held too high is fine, one inside a shape is not); sneaking only sinks in a liquid. Fluids are [[fluid-physics]].

## See also
- [[goals-and-intents]] - who writes the controls.
- [[fluid-physics]] - the step in water and lava.
