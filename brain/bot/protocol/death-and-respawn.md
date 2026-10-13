---
title: Death and respawn
description: What the bot does when its health reaches zero (issue #5) - the death fact, the intent dropped, client-command 0 - what the respawn packet resets, the breath estimate from the eye flag, the four packet specs added for it, and how the server model drowns and respawns a player.
type: reference
tags: [bot, protocol, survival, packets]
aliases: [set-health 0, :player/dead?, client-command, respawn packet, :player/air, breathe, drowning, :answer/kind :death, damage-event, player-combat-kill]
status: verified
lastUpdated: 2026-10-11
verifiedAgainst: HEAD
sourceRefs:
  - src/clojurecraft/game.clj#defn change-dimension
  - test/clojurecraft/game_test.clj#a-respawn-into-another-dimension-forgets-the-old-one
  - test/clojurecraft/game_test.clj#a-login-says-the-dimension-the-bot-joins-in
  - src/clojurecraft/game.clj#defn die
  - src/clojurecraft/game.clj#defn breathe
  - src/clojurecraft/game.clj#def max-air 300
  - src/clojurecraft/game.clj#defmethod on-packet [:play :respawn]
  - "src/clojurecraft/packet.clj#[:play :c2s :client-command]"
  - src/clojurecraft/sim.clj#defn drown
  - src/clojurecraft/sim.clj#defmethod on-packet [:play :client-command]
  - test/clojurecraft/sim_test.clj#held-under-water-the-bot-drowns-and-respawns-with-no-intent
  - test/clojurecraft/props_test.clj#step-is-total-after-a-death
related:
  - "[[bot/protocol/_moc|Protocol]]"
  - "[[packet-table]]"
  - "[[intentions-as-facts]]"
  - "[[water-escape]]"
  - "[[sib-dimension-change]]"
---

# Death and respawn

## Dying
`set-health` with health at or under zero, when the bot was not already dead, is `die`: `:player/dead? true`, controls cleared, `:stats/deaths` counted, a `{:answer/kind :death}` fact appended to memory (so "what was I doing when I died" is `memory/intention-as-of` at the tick before it, [[intentions-as-facts]]), `:plan/intent` and `:plan/last` dropped (a new life plans afresh; keep_inventory is on, so the goal re-derives from what is held), and `{:packet/name :client-command :action 0}` emitted, the "perform respawn" the vanilla client sends. A dead player's tick runs no physics. The property `step-is-total-after-a-death` holds that a set-health of 0 followed by any packets never throws, leaves a valid world with no intent, and asked to respawn.

## Respawning
The `respawn` packet (its fields now modelled: dimension type, dimension, seed, game type, previous game type, debug, flat; the death location and what follows are an unmodelled tail) gives fresh menus as before, and now also: not dead, no entities, velocity, fall distance and air reset. The `player-position` teleport that follows places the bot ([[sib-dimension-change]] is the other reader of this packet: a dimension change, not handled yet).

## Breath
`breathe` runs after the physics each tick: `:player/air` falls by one while `:player/eye-in-water?` and rises by four otherwise, within 0 and `max-air` (300, vanilla's fifteen seconds). It is an estimate: the server sends the own player's air only as entity metadata, which is not decoded. Nothing keys off it reaching zero; the escape keys off time under ([[water-escape]]).

## Packets added
`damage-event` (entity, source type, cause, direct), `player-combat-kill` (player id), `game-event` (event, param) and the c2s `client-command` (action), recorded in `packet_specs_ever.edn`; `respawn` grew from no fields to its prefix, as specs only may.

## The server model
`sim/drown`: each tick, the model's `:sim/air` falls while the position the client last reported puts the eye in a water block of its column and refills otherwise; at zero it takes `drown-damage` (2) every `drown-every` (20) ticks and tells the client with `set-health`. `client-command` 0 from a dead player (health 0) respawns it at `:sim/respawn` (an input to `sim/init`, the spawn by default: a gym landing differs from the world spawn) with full health and breath, sending `respawn` then the teleport. `held-under-water-the-bot-drowns-and-respawns-with-no-intent` runs it: a well drowns the bot in 25 s of model time and it comes back on the world spawn beside it, lungs full, one death counted, the death a fact.

## Gotchas
- **A respawn can change the dimension** (a portal, #8). `change-dimension` sets `:player/dimension` from the packet's `:dimension` name; when it differs from the one the bot is in (`dimension/of`: the login's, which now reads the spawn info, else the overworld), the columns and the block overlay go and `:player/loaded?` is false, so the teleport that places the bot sends `player-loaded` again. Nether chunks decode at their own height ([[chunk-sections]], #11).
- A hand-built `{:packet/name :respawn}` no longer passes the packet spec: use `fixtures/respawn` (the decoder's shape).
- Whether 26.1.2 wants `player-loaded` again after a respawn teleport, and whether a `game-event` precedes `respawn`, is still to be read from a recorded death ([[water-escape]] has no live death yet).

## See also
- [[packet-table]] - every modelled packet.
- [[water-escape]] - the goal that keeps this from happening.
