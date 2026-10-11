---
title: Physics constants
description: Every constant physics.clj uses (gravity, drag, inertia, accelerations, jump, player box, eye height), with its value, meaning and the test that pins the behaviour it produces.
type: reference
tags: [bot, world, physics, constants, index]
aliases: [gravity 0.08, jump velocity, eye height 1.62, ground inertia, walking speed, player hitbox]
status: verified
lastUpdated: 2026-10-11
verifiedAgainst: HEAD
sourceRefs:
  - src/clojurecraft/physics.clj#def gravity 0.08
  - src/clojurecraft/physics.clj#def ground-acceleration
  - src/clojurecraft/physics.clj#def negligible 0.003
  - src/clojurecraft/physics.clj#defn step
  - test/clojurecraft/physics_test.clj#vanilla jump peaks about 1.25 blocks up
related:
  - "[[bot/world/_moc|World]]"
  - "[[land-physics]]"
  - "[[resting-vertical-velocity]]"
---

# Physics constants

| Name | Value | Meaning |
|---|---|---|
| `gravity` | 0.08 | subtracted from vy every tick, after the move |
| `vertical-drag` | 0.98 | multiplies vy after gravity |
| `half-width` | 0.3 | player box is 0.6 wide (x and z) |
| `height` | 1.8 | player box height |
| `eye-height` | 1.62 | eye above the feet; `eye` and every reach check use it |
| `jump-velocity` | 0.42 | vy set on a jump |
| `ground-inertia` | 0.6 × 0.91 = 0.546 | horizontal multiplier after a tick that landed (default block slipperiness 0.6) |
| `air-inertia` | 0.91 | horizontal multiplier otherwise |
| `air-acceleration` | 0.02 | input acceleration while airborne |
| `ground-acceleration` | 0.1 × 0.16277136 / 0.546³ ≈ 0.1 | input acceleration on the ground |
| `negligible` | 0.003 | velocity components below this snap to 0 at the start of a tick |
| jump cooldown | 10 ticks | `:player/jump-ticks` set on a jump, counted down each tick |
| forward input | 0.98 | multiplier on acceleration when `:control/forward?` |
| `liquid-acceleration` | 0.02 | input acceleration in water or lava |
| `water-inertia` / `lava-inertia` | 0.8 / 0.5 | velocity kept per tick in a liquid (both axes) |
| `water-gravity` / `lava-gravity` | 0.005 / 0.02 | gravity in a liquid (water's is a sixteenth of land's) |
| `swim-up` / `swim-down` | 0.04 | vy added per tick with jump held, taken with sneak held, in a liquid |
| `out-of-liquid-impulse` | 0.3 | vy when colliding horizontally in a liquid with the box free 0.6 up |
| `water-contract` / `lava-contract` | (0.001, 0.401, 0.001) / (0.1, 0.4, 0.1) | the box shrinks by this before asking "in water?" / "in lava?" |
| `safe-fall` | 3.0 | blocks of fall vanilla forgives |

## Behaviour the tests pin
- A fall settles at y 64.0 with vy in (-0.08, 0): the resting -0.0784 ([[resting-vertical-velocity]]).
- 60 ticks forward at yaw 0 cover 10-13 blocks (about 0.216 per tick); yaw 90 walks toward -x.
- A jump peaks 1.2-1.3 blocks up; a one-block ledge is cleared with jump held.
- `look-at` convention: south yaw 0, east yaw -90, looking down is positive pitch.

## Gotchas
- `on-ground?` is "this tick's vertical move was clipped while falling"; it drives which inertia the next tick uses.
- The forward multiplier is 0.98 and there is no sprint or strafe input; sneak only acts in a liquid.

## See also
- [[land-physics]] - the step and the solidity oracle.
- [[fluid-physics]] - the liquid branch these constants drive.
