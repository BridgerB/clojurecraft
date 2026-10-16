---
title: Eyes, the end portal and the End in the server model
description: How sim.clj throws an eye of ender toward a stronghold, fills end portal frames and lights the portal, takes the player into the End onto the obsidian platform, and keeps a perched dragon its crystals heal (end.clj).
type: reference
tags: [bot, tooling, sim, end]
aliases: [end.clj, eye of ender, :sim/stronghold, :sim/eyes, end portal frame, :sim/end, arrive-end, perched dragon, end crystal]
status: verified
lastUpdated: 2026-10-16
verifiedAgainst: HEAD
sourceRefs:
  - src/clojurecraft/end.clj#defn frame-state
  - src/clojurecraft/end.clj#defn lit
  - src/clojurecraft/end.clj#defn platform
  - src/clojurecraft/end.clj#def spikes
  - src/clojurecraft/end.clj#defn eye-target
  - src/clojurecraft/sim.clj#defmethod use-held :eye
  - src/clojurecraft/sim.clj#defn tick-eyes
  - src/clojurecraft/sim.clj#defn frame-takes-eye
  - src/clojurecraft/sim.clj#defn arrive-end
  - src/clojurecraft/sim.clj#defn perch
  - test/clojurecraft/end_test.clj#a-thrown-eye-flies-toward-the-stronghold-and-drops-or-shatters
  - test/clojurecraft/end_test.clj#twelve-eyes-in-twelve-frames-light-the-portal
  - test/clojurecraft/end_test.clj#stepping-into-the-portal-arrives-on-the-platform-with-the-dragon-and-its-crystals
  - test/clojurecraft/end_test.clj#crystals-heal-the-dragon-until-a-hit-blasts-them
related:
  - "[[bot/tooling/_moc|Tooling]]"
  - "[[server-model]]"
  - "[[sim-mobs]]"
  - "[[sib-end-and-dragon]]"
---

# Eyes, the end portal and the End in the server model

Everything past the fortress (the stronghold, the portal, the dragon) is played against the model before a real server (issue #260). `end.clj` holds the rules as values and pure functions; `sim.clj` holds the state and the clock.

## An eye of ender (`use-held :eye`, `tick-eyes`)
- `init`'s `:stronghold [x y z]` (`:sim/stronghold`) is where an eye flies toward; with none, an eye is not thrown and stays in hand, as the game throws none in a world without a stronghold.
- A `use-item` with an eye: one taken, an `add-entity` of type `eye_of_ender` (50) at the middle of the body (feet + 0.9). It flies for `end/eye-target`: twelve blocks along the line to the stronghold on the plane and eight up when the stronghold is farther, else the stronghold itself (`EyeOfEnder.signalTo`); there after 40 ticks, a straight line (a model's pace), each move told as `move-entity-pos`, so the client's sum of the moves is the model's point and two throws give two lines to cross. At 80 ticks it is removed; four in five drop as an eye item where they fall to, the rest shatter (rolled from the mobs' stream at the throw).

## The portal (`frame-takes-eye`)
`use-item-on` an `end_portal_frame` with an eye held: the frame takes it (`end/frame-state`: the eye property first, true's states lowest, so a frame without an eye is four states above the same frame with one; the game places frames without, 9473 facing north), one eye taken; when that closes a ring of twelve round a 3×3 interior (`end/ring-offsets`, `end/lit`), the nine cells become `end_portal`. Anything else held is a placement against the frame. The frames' facing is not checked.

## The End (`through-end-portal`, `arrive-end`)
Feet in an `end_portal` outside the End: at once (the game's end portal has no wait) the overworld's columns, blocks, items, eyes and mobs go, `respawn` into `minecraft:the_end`, a teleport to `end/spawn` (100.5 49 0.5), the End's columns (`:end {:columns ...}`, a void when absent), then the obsidian platform block by block (`end/platform`: five by five of obsidian under the feet, three layers of air over it, `EndPlatformFeature`), the dragon (`mob/kinds :ender_dragon`, 200 health, perched at `:end :dragon`, the fountain's top when absent) and a crystal on every spike (`end/spikes`: ten on a circle of 42, heights 76 to 103, unshuffled; or `:end :crystals`). `end_portal` is passable (`blocks/passable-types`), so the bot's physics lets a player fall into it.

## The dragon and the crystals
The perched dragon heals one every ten ticks while any crystal stands (`perch`, `EnderDragon.checkCrystals`). An `attack` on a crystal in reach blows it up (power 6, `blast`, hurting a player beside it); an attack on the dragon hurts it by the held item, full damage wherever it lands; at zero it dies (`entity-event` 3) and is removed 200 ticks later (`mob/dragon-death-ticks`).

## Limits
Not modelled: the dragon's flight, breath and charges, the exit portal and the credits, the iron cages, the spikes' seeded shuffle, the frames' facing, a death in the End (the respawn sends the overworld but the model keeps the End's columns), the End's own island when no columns are given.

## See also
- [[sim-mobs]] - the mobs, the attack and the blast this reuses.
- [[server-model]] - the model this extends.
