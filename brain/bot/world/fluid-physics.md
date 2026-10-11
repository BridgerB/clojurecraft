---
title: Fluid physics
description: How the pure movement step handles water and lava (issue #5) - the fluid oracle, which box tests "in water" and "in lava", the eye rule at y+1.62, the liquid branch's constants, the out-of-liquid impulse that is the only lift onto a bank, fall distance, and the four attributes the step writes.
type: reference
tags: [bot, world, physics, water, lava]
aliases: [water physics, liquid branch, fluid-at, in-water?, eye-in-water?, out-of-liquid impulse, swim-up, fall distance, water-contract]
status: verified
lastUpdated: 2026-10-11
verifiedAgainst: HEAD
sourceRefs:
  - src/clojurecraft/physics.clj#defn fluid-height
  - src/clojurecraft/physics.clj#defn in-fluid?
  - src/clojurecraft/physics.clj#defn eye-in-water?
  - src/clojurecraft/physics.clj#defn free?
  - src/clojurecraft/physics.clj#defn step
  - src/clojurecraft/physics.clj#def water-contract [0.001 0.401 0.001]
  - src/clojurecraft/physics.clj#def out-of-liquid-impulse 0.3
  - src/clojurecraft/blocks.clj#defn fluid
  - src/clojurecraft/terrain.clj#defn fluid-fn
  - test/clojurecraft/physics_test.clj#defn pond-at
related:
  - "[[bot/world/_moc|World]]"
  - "[[land-physics]]"
  - "[[physics-constants]]"
  - "[[water-escape]]"
  - "[[sib-water-traps]]"
---

# Fluid physics

`(physics/step solid? fluid-at world controls)` is the same tick as [[land-physics]] with a second oracle: `(fluid-at x y z)` answers `{:fluid/kind :water|:lava :fluid/level 0-15}` or nil. `terrain/fluid-fn` builds it from the loaded columns; the three-arity `step` passes `no-fluid`, so a world with no oracle is dry.

## Key files
- `blocks.clj`, `fluid` - the kind and level of a state id: water and lava states by id range (level = id − base), and the always-waterlogged definition types (kelp, seagrass, bubble columns) as a water source.
- `physics.clj`, `fluid-height` - a cell's liquid surface: full at level 8 and above (falling), else `1 − (level+1)/9` (typecraft's `getRenderedDepth`).
- `physics.clj`, `in-fluid?` - is any cell under the contracted box a fluid whose surface the box reaches. Water contracts the box by `water-contract` (0.001, 0.401, 0.001), lava by `lava-contract` (0.1, 0.4, 0.1), as vanilla does.
- `physics.clj`, `eye-in-water?` - the one block at y + 1.62, never feet+1 ([[sib-water-traps]] item 4: a surface floater has feet+1 in water and the eye in air).
- `physics.clj`, `free?` - may the box, moved by a velocity, sit there with no solid in it.
- `physics.clj`, `step` - the liquid branch below.

## The liquid branch
In order, as vanilla: a jump on the ground is not a jump in a liquid; `:control/jump?` adds `swim-up` (0.04) to vy and `:control/sneak?` takes `swim-down` (0.04); input acceleration is `liquid-acceleration` (0.02); the sweep moves the box; then `vy = vy × inertia − gravity` with `water-inertia` 0.8 / `water-gravity` 0.005 (a sixteenth of land gravity, 1.13+) or `lava-inertia` 0.5 / `lava-gravity` 0.02; horizontal velocity keeps the same inertia. Last, the impulse: if the body collided horizontally this tick and the box moved by `[vx (+ vy 0.6) vz]` is free of solids, vy becomes `out-of-liquid-impulse` (0.3). That is the only real lift out of water; the swim-up alone never clears a one-block bank ([[sib-water-traps]] item 1), and ruststeve's port tested the free space at the world origin for weeks (item 3).

Fall distance: `:player/fall-distance` accumulates the drop while vy is negative out of a liquid, and resets on landing or in a liquid. Vanilla's damage is the fall less three blocks, so three are free; nothing consumes the distance yet.

## What the step writes
`:player/in-water?`, `:player/in-lava?`, `:player/eye-in-water?` and `:player/fall-distance`, every tick, by the step alone: typecraft computed in-water and never published it, and every consumer was blind ([[sib-water-traps]] item 2). `game/breathe` turns the eye flag into `:player/air` ([[death-and-respawn]]); `trap/trapped-tick` turns it into the trap ([[water-escape]]).

## Behaviour the tests pin
From rest in a two-deep pond the bot sinks with vy settling in (−0.006, −0.004); with jump held the feet rise to float-depth 1.03 under the surface with the eye in air; pressed forward into a one-high bank the impulse fires and the bot stands on it within 60 ticks; a four-block fall counts 4.0 and water resets it; horizontal speed in water settles at 0.02 / (1 − 0.8) = 0.1 blocks a tick. `pond-at` builds the ponds.

## Gotchas
- `step` now takes the fluid oracle as its second argument; its selection `fdef` allows both arities.
- Flow currents (`getFlow`) are not modelled: a river does not push the bot. Add when a recording shows drift.
- `:player/air` is an estimate from the eye flag, not the server's own-entity metadata ([[death-and-respawn]]).

## See also
- [[land-physics]] - the dry half of the step.
- [[water-escape]] - what the bot does with these flags.
