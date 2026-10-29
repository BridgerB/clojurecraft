---
title: End portal
description: How the bot lights the end portal and enters the End (issue #266) - an eye into each remembered frame without one from a cell a route reaches, the frames and stairs walked as floors, the nine portal blocks awaited, the feet brought over the portal's middle; the vanilla portal room the gym builds.
type: reference
tags: [bot, plan, end, stronghold, goals]
aliases: [end_portal.clj, end-portal, :end-portal, :enter-end, :portal-lit?, :in-end?, :eyed, :no-frames, :no-eyes, :lighting, :no-ring, :no-stand, stood-on, portal-room, :portal-room, :portal, :intent/enter?]
status: verified
lastUpdated: 2026-10-18
verifiedAgainst: HEAD
sourceRefs:
  - src/clojurecraft/end_portal.clj#defn frames
  - src/clojurecraft/end_portal.clj#defn empty-frames
  - src/clojurecraft/end_portal.clj#defn middle
  - src/clojurecraft/end_portal.clj#defmethod build/holds? :eyed
  - src/clojurecraft/end_portal.clj#defn eye-op
  - src/clojurecraft/end_portal.clj#defn lit-op
  - src/clojurecraft/end_portal.clj#defn enter-op
  - src/clojurecraft/end_portal.clj#defn next-op
  - src/clojurecraft/end_portal.clj#defmethod intent/run :end-portal
  - src/clojurecraft/end_portal.clj#defn toward-frame
  - src/clojurecraft/end_portal.clj#defn next-toward
  - src/clojurecraft/end_portal.clj#defmethod plan/interrupts? :end-portal
  - src/clojurecraft/end_portal.clj#defmethod plan/interrupts? :enter-end
  - src/clojurecraft/end_portal.clj#defmethod plan/done-by :portal-lit?
  - src/clojurecraft/end_portal.clj#defmethod plan/done-by :in-end?
  - src/clojurecraft/path.clj#def stood-on
  - src/clojurecraft/memory.clj#def landmark-kinds
  - src/clojurecraft/build.clj#defn reachable-stand
  - src/clojurecraft/build.clj#defn work-op
  - src/clojurecraft/end.clj#defn lit
  - src/clojurecraft/sim.clj#defn frame-takes-eye
  - src/clojurecraft/sim.clj#defn through-end-portal
  - src/clojurecraft/main.clj#defn result
  - "resources/clojurecraft/goals.edn#{:goal/id :end-portal"
  - "resources/clojurecraft/goals.edn#{:goal/id :enter-end"
  - "resources/clojurecraft/problems.edn#{:problem/stage :end-portal"
  - src/clojurecraft/gym.clj#def portal-room
  - src/clojurecraft/gym.clj#defmethod land! :portal-room
  - "src/clojurecraft/gym.clj#{:gym/goal :end-portal"
  - "src/clojurecraft/gym.clj#{:gym/goal :enter-end"
  - test/clojurecraft/end_portal_test.clj#the-ring-is-whole-among-twelve-frames-and-not-eleven
  - test/clojurecraft/end_portal_test.clj#frames-the-portal-and-stairs-are-floors-to-a-route
  - test/clojurecraft/end_portal_test.clj#the-void-has-no-landing
  - test/clojurecraft/end_portal_test.clj#the-next-op-is-an-eye-into-the-first-empty-frame-then-the-step-in
  - test/clojurecraft/end_portal_test.clj#the-planner-waits-for-frames-eyes-and-the-lighting
  - test/clojurecraft/end_portal_test.clj#twelve-eyes-light-the-portal-against-the-model
  - test/clojurecraft/end_portal_test.clj#the-end-is-entered-against-the-model
  - test/clojurecraft/gym_test.clj#the-portal-room-is-the-vanilla-piece-round-the-landing
  - test/clojurecraft/gym_test.clj#the-portal-room-landing-puts-the-bot-on-its-floor
related:
  - "[[bot/plan/_moc|Plan]]"
  - "[[stronghold]]"
  - "[[eyes-of-ender]]"
  - "[[build-plan]]"
  - "[[pathfinder]]"
  - "[[sib-end-and-dragon]]"
---

# End portal

The stage after the stronghold ([[stronghold]]): in its portal room, with eyes of ender held ([[eyes-of-ender]]), an eye into each frame that lacks one, then through the portal into the End. Issue #266 splits it from the roadmap's `:end-and-dragon` problem into its own row of `problems.edn` (`:end-portal`, goals `:end-portal` and `:enter-end`). No sibling has done it: steve's End attempts started in the End by teleport, as ruststeve's gyms did.

## The room

The vanilla piece (`StrongholdPieces.PortalRoom`, 11 by 8 by 16) is the whole problem. The twelve frames float two blocks over the floor the player walks on, round a 3x3 pit of lava two blocks under them; a staircase of three stairs blocks leads up to the frames' height, with a silverfish spawner in the middle of its top step; two troughs of lava flank the door. Every frame faces inward (the northern three face south), as `EndPortalFrameBlock`'s pattern requires. So the frames cannot be reached from the floor (the eye is 2.6 under a frame's top), the way up is the stairs, and the far side of the ring is reached by walking on the frames themselves, as a player does.

## Floors a route takes

`path/stood-on`: a stairs block, an end portal frame and the end portal are `:solid` to a route, where before the two shapes were `:awkward` and the portal `:clear`. The physics' solidity oracle (`blocks/solid?`) already took the stairs and the frame for full cubes, and `intent/feet` reads the feet's cell a half block up, so the bot stands on a frame top (13/16) or a stair as on a block, climbing each stair with a jump. The end portal has no collision at all: a route may end on it as on a floor because the body never falls through it, the server moving the player the moment the feet are in its cell (`sim/through-end-portal`; the game's end portal has no four-second wait). A spawner stays awkward, so the route up the stairs goes by a side of the top step.

## The ops

`end_portal.clj`. What is known comes from memory: the frames are landmarks (`memory/landmark-kinds`), and so now is the lit portal's block, so `frames` and `portals` are `memory/positions-now` queries in the bot's dimension. `empty-frames` are the frames whose state (the loaded block, else the last sighting) has no eye (`end/eyed?`), in position order, so the first stays the first while it is worked.

`next-op` names the op to work, read against the world each tick as the nether portal's cast is ([[build-plan]]):
- `eye-op`: a `:use-on` of the frame's top with `:ender_eye` held, from `build/reachable-stand` (the feet when they already reach, else the nearest standable cell to the frame a route reaches: the frame's own top first, then its neighbours on the ring), done `[:eyed frame]` (`build/holds? :eyed`: the loaded block holds an eye). `:no-stand` when no cell reaches it.
- with `enter?`, once no frame is empty: `middle` finds the ring's middle among the remembered frames (a cell some frame borders with a frame at all twelve cells round it; `:no-ring` when none), `lit-op` waits where the feet are for `[:block middle :end_portal]` (the server tells the nine portal blocks a moment after the twelfth eye), then `enter-op` brings the feet over the middle (`:build/stand` the cell over the portal block) and is done `[:dimension minecraft:the_end]`.
- `nil` once every frame is eyed (the lighting row) or the dimension is the End.

`intent/run :end-portal` works that op through `build/work-op` (its child the walk to the stand, the `:use-on`, the `:await`); nothing records progress, so a death or a reload resumes where the world says. `:intent/enter?` tells the two rows apart.

## The goals
- `:end-portal` (priority 24, aside, `--until end-portal`, overworld only): done `:portal-lit?`, an end portal block on record. `next-toward` waits `:no-frames` with no frame remembered, `:no-eyes` when fewer eyes are held than frames lack them (the eyes stage is the one to look at), `:lighting` once every frame is eyed and the blocks are not yet on record.
- `:enter-end` (priority 25, aside, `--until enter-end`): done `:in-end?`, `:player/dimension` the End. The same ops and then the wait and the step in. On arrival the bot stands on the obsidian platform over the void: a route never steps off it, since a column open to the void has no landing within three blocks (`path/landing`), and the row is done.

## Gym
`locate structure minecraft:stronghold` names a stronghold's start, not its portal room, and no command finds the room, so the gym builds it: `gym/portal-room` is the vanilla piece block for block as fill commands round the landing (`piece` maps the piece's cells, the bot landing at its (5, 1, 2), inside the door on the floor), and `land! :portal-room` runs them with the chunks loaded, teleports the bot to the landing itself (the surface over it is now the roof) and pins the spawn. The iron bars are left out; the spawner is bare and spawns nothing. Rows `end-portal` (judged by `execute if block {at} minecraft:end_portal` at the RESULT's `:portal`, the nearest end portal block remembered, `main/result`) and `enter-end` (judged by `gym/dimension-test`), fourteen eyes given, five minutes each. Plan `ci/plan/end-portal-a.edn`.

## Proof
- `end_portal_test`: the ring among twelve frames and not eleven; frames, the portal and stairs as floors, a route from the floor up the stairs onto the near row and along the ring to the far row, none into the interior until it is lit; no route off the End's platform; the ops in order (an eye into the first empty frame, the wait, the step in); the waits; the rows' done predicates; and against the model's End, twelve eyes light the portal and the End is entered onto the platform.
- `gym_test`: the room's commands are the vanilla piece round the landing; the landing builds, teleports, then gives.
- Batch end-portal-a (plan run 39132430055, at c26ccd5): `end-portal` 3/3, 27 to 31 s from the room's floor to the lit portal; `enter-end` 3/3, 28 to 34 s to the End. The first eyes in frames and the first End of any clojurecraft bot, from the gym's room; a stronghold's own room is reached by #265's rows.
