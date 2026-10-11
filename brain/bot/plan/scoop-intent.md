---
title: Scoop intent
description: How the bot fills a bucket - the :scoop act choosing surface water in sight or a remembered pool (never cave water), hunting outward and downhill when none is known, and the :scoop intent that equips the bucket, aims at the water's surface and sends use-item, done only when the inventory shows the water bucket.
type: reference
tags: [bot, plan, water, bucket, intents]
aliases: [:scoop, scoop.clj, fill-bucket, water bucket, use-item, no-scoop, no-water, aim-point]
status: verified
lastUpdated: 2026-10-11
verifiedAgainst: HEAD
sourceRefs:
  - src/clojurecraft/scoop.clj#defn scoop-next
  - src/clojurecraft/scoop.clj#defn nearest-source
  - src/clojurecraft/scoop.clj#defn hunt-target
  - test/clojurecraft/scoop_test.clj#with-no-water-known-the-hunt-walks-out-and-downhill
  - src/clojurecraft/scoop.clj#defn aim-point
  - src/clojurecraft/scoop.clj#defmethod scoop-stage :sent
  - src/clojurecraft/place.clj#defn equip
  - src/clojurecraft/sim.clj#defn scooped
  - test/clojurecraft/scoop_test.clj#the-act-scoops-in-reach-walks-to-water-in-sight-and-refuses-cave-water
  - test/clojurecraft/scoop_test.clj#a-scoop-the-server-never-answers-fails-no-scoop
  - test/clojurecraft/sim_test.clj#an-empty-bucket-is-filled-at-a-pond-from-its-bank
related:
  - "[[bot/plan/_moc|Plan]]"
  - "[[sib-bucket-water-find]]"
  - "[[mc-bucket-use-item]]"
  - "[[memory-sightings]]"
---

# Scoop intent

The `:fill-bucket` row needs an empty bucket and provides a water bucket; its act is `:scoop`.

## Where the water is (`scoop-next`)
1. `nearest-source`: the nearest plain water source (`blocks/water-base`: not a waterlogged block) with no water above it in the loaded terrain, within 24 of the eye, no more than `memory/surface-water-below` (5) under it, not blacklisted. In reach (4 blocks, eye to centre): `{:intent/kind :scoop :intent/target src}`; else a walk to it, which the pathfinder ends on the bank, since water is a wall to it ([[pathfinder]]).
2. With none in sight, a walk to the nearest remembered pool within 96, the same depth rule ([[memory-sightings]]): one fact per chunk, so a far pond is known without a fact per water cell.
3. Else a hunt (`hunt-target`): of eight bearings, the ground 40 blocks out (the highest cell the feet could stand in, 16 over to 24 under the eye, in a loaded chunk), leaving out any nearer the landing (`:plan/go-at`) than the bot is now, lowest first, since water lies low; a walk there tagged `:intent/for :water-hunt`. The chunks it loads are remembered as they arrive, pools included, so the next pass finds the water in sight or remembered. Nothing is stored for the hunt: going outward from the landing is what keeps it from walking back over dry ground. This is the siblings' directional hunt, downhill first ([[sib-bucket-water-find]]).
4. With no bearing that has ground, `{:plan/wait :no-water}`.

The hunt came from gym batch `water-a1` (forest landings, set A): 1 pass in 5, and all four failures waited `:no-water` about 33 s in, with no water in sight within 24 and none remembered within 96.

## The intent
1. `:equip`: `place/equip`, shared with the place intent: the bucket into the held slot (select it in the hotbar, or swap it there with a window-0 click).
2. `:aim`: look at `aim-point`, the centre of the source's surface (0.85 up the block, a source being 8/9 of a block tall), and after 3 still ticks and 300 ms send `use-item` with that look and a fresh sequence. A target that is no longer a surface source fails `:target-gone`. Aimed at the block's centre from the bank, the ray clipped the bank block's corner and the sim's pond test failed three scoops in a row.
3. `:sent`: done when the inventory holds one more water bucket than when the intent began; 3 s without, aim again; the third unanswered scoop fails `:no-scoop`, and the planner's failure path blacklists the source ([[goals-and-intents]]).

No flag stands for "the bucket is in use" (steve's `usingHeldItem`): only the inventory says the scoop worked.

## In the sim
`sim/scooped` casts a ray from the reported eye along the packet's look, in 0.05 steps up to 4.5: the first water source fills the held bucket (the source becomes air), a solid block first stops it. The pond test fills a bucket from hotbar slot 4, from the bank, without entering the water.

## Limits
The sim treats a water cell as wholly water; the game clips the ray against the fluid's 8/9 shape. A scoop on the real server has not been run yet ([[mc-bucket-use-item]]).

## See also
- [[sib-bucket-water-find]] - the rules this follows.
