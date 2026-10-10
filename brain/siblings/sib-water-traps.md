---
title: Water traps both bots hit
description: The list of water failures from steve and ruststeve (weak swim-up, wall-impulse-only lift, a physics flag never published, head-in-water at the wrong height, momentary-bob false escapes, lily-pad lids, aquifer scoops, dry biomes), each with where it was fixed.
type: reference
tags: [siblings, water, gotcha]
aliases: [water walls, escape water lessons, aquifer trap, drowning]
status: verified
lastUpdated: 2026-10-09
verifiedAgainst: steve c28028b, ruststeve bc575e3
sourceRefs:
  - steve:src/lib/typecraft/physics/physics.ts#const gravity = 0.08;
  - steve:src/lib/typecraft/physics/physics.ts#Floating in liquid — gentle swim-up only.
  - steve:src/lib/typecraft/physics/physics.ts#Without this, bot.entity.isInWater is stuck at its default (false) forever:
  - ruststeve:src/physics/physics.rs#`does_not_collide` takes a POSITION.
  - ruststeve:src/bot_utils.rs#EYE height (vanilla eye-in-fluid; the SDK watchdog's `head_submerged` uses the same), not feet+1.
  - steve:src/lib/steve/lib/bot-utils.ts#Don't declare victory on a momentary bob to the surface.
  - steve:src/lib/steve/lib/bot-utils.ts#A lily pad over the bot's head is a lid: it stops the rise to the surface.
  - steve:src/lib/steve/tasks/bucket/main.ts#Prefer SURFACE ponds, never deep cave/aquifer water.
related:
  - "[[siblings/_moc|Siblings]]"
  - "[[land-physics]]"
  - "[[sib-water-escape]]"
  - "[[sib-bucket-water-find]]"
---

# Water traps both bots hit

One list of every water failure the siblings paid for, so the roadmap's water issue (docs/issues/06) starts from the scars. The escape design itself is [[sib-water-escape]]; finding scoopable water is [[sib-bucket-water-find]].

## Key files
- steve `src/lib/typecraft/physics/physics.ts` - typecraft's port of prismarine-physics: constants (`const gravity = 0.08;`), the liquid branch, `applyPlayerState`.
- ruststeve `src/physics/physics.rs` - the Rust port of the same physics, with the out-of-liquid impulse fix.
- ruststeve `src/bot_utils.rs`, `head_in_water` - the eye-height rule.
- steve `src/lib/steve/lib/bot-utils.ts` - `isOnDryLand`, `isInWaterTrap`, `escapeWaterInner`.
- steve `src/lib/steve/tasks/bucket/main.ts`, `fillWaterBucket` - the aquifer refusal.

## The list
1. **Almost no lift in water.** In typecraft the jump key in liquid off the ground adds only `vel.y += 0.04` per tick ("Floating in liquid — gentle swim-up only"); standing on the floor of shallow water does a real 0.42 jump. The real lift is `outOfLiquidImpulse` 0.3, which fires only when the body is colliding horizontally and the space 0.6 up is free: you escape by pressing into a bank or a dug notch.
2. **A flag never published.** `applyPlayerState` did not copy `isInWater` back to the entity, so "every consumer (water-escape, drowning guard, digging-in-water) was blind" until the line was added.
3. **The impulse tested the wrong place.** ruststeve's port passed a bare offset to `does_not_collide`, which takes a position, so the free-space test ran at the world origin and a swimming bot pressed against a 1-high bank never climbed out.
4. **Head-in-water at feet+1.** ruststeve now tests the eye block (y + 1.62): a bot bobbing at the surface has feet+1 in water and the eye in air, and counting that as submerged looped `leave_water` for 3 h in one race.
5. **Survival only between steps.** ruststeve's `handle_survival` ran in the main loop, so a head that went under mid-step drowned inside the step; fixed by a per-tick breath watchdog ([[sib-water-escape]]).
6. **Momentary bob counted as escaped.** In a 1-wide pocket the bot pops to a dry Y for one tick; steve settles 300 ms and re-checks before declaring dry land.
7. **Lids.** A lily pad over the head stops the rise; steve breaks it first.
8. **The aquifer scoop.** Deep cave water 20+ blocks down drowned the best steve run; the scoop now refuses it ([[sib-bucket-water-find]]).
9. **Dry biomes.** The two deepest steve runs hung at Fill Water Buckets with a full iron kit in inland jungle and savanna.

## What it means here
Liquids are passable in our physics today ([[land-physics]]). The water issue (docs/issues/06) adds in-water movement with the 0.04 swim-up and the 0.3 wall impulse tested at the real position, eye-height submersion, a priority-zero escape goal re-derived every tick, and liquid sightings.

## Limits
Items 5, 8 and 9 rest on the comments and CHANGES entries quoted in the linked notes, not on a reproduced run. Item 9 is from steve's operator notes (not code) and is cited through [[sib-bucket-water-find]].

## See also
- [[sib-water-escape]] - how each bot detects and leaves water.
- [[land-physics]] - our movement step, liquids still passable.
