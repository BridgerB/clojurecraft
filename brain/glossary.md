---
title: Glossary
type: glossary
tags: [brain, glossary]
status: verified
lastUpdated: 2026-10-09
---

# Glossary

- **World**: the one immutable map of namespaced attributes (`:player/pos`, `:world/chunks`, `:plan/intent` ...) held in one atom; see [[world-value]].
- **Event**: a map with `:event/kind` fed to the reducer: `:start`, `:packet`, `:tick`, `:go`, `:closed`; see [[reducer-and-effects]].
- **Effect**: a map in `:bot/effects` describing something the loop should do (`:send`, `:log`); never performed inside a reducer.
- **Packet**: a flat map with `:packet/name` and the wire's own field names; see [[packet-specs]].
- **Phase**: the protocol state (`:handshake`, `:login`, `:configuration`, `:play`), advanced only by emitting a transition packet; see [[connection-phases]].
- **Intent**: a value in `:plan/intent` (`{:intent/kind :dig ...}`) advanced one tick at a time by `intent/run`; see [[goals-and-intents]].
- **Goal**: a row in `plan/goals`, answered by `plan/goal-done?` and `plan/next-intent` defmethods.
- **Sighting**: a remembered block state with a timestamp in `:world/sightings`; see [[memory-sightings]].
- **Recording**: an EDN file of every event of a run, replayable with no server; see [[record-replay]].
- **Sim**: the pure server model in `sim.clj` that tests run the whole bot against; see [[server-model]].
- **Gym**: a run of one goal with prerequisites given over RCON and judged by `RESULT` plus an RCON truth read; see [[ci-wood-workflow]].
- **Trunk bottom**: a remembered log with no log below it; the walk target for the wood goal.
- **775**: the protocol number of Minecraft 26.1.x; **26.1.2** is the server version every pin in the `game` pillar refers to.
