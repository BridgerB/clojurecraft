---
title: Glossary
type: glossary
tags: [brain, glossary]
status: verified
lastUpdated: 2026-10-10
---

# Glossary

- **World**: the one immutable map of namespaced attributes (`:player/pos`, `:world/chunks`, `:plan/intent` ...) held in one atom; see [[world-value]].
- **Event**: a map with `:event/kind` fed to the reducer: `:start`, `:packet`, `:tick`, `:go`, `:closed`; see [[reducer-and-effects]].
- **Effect**: a map in `:bot/effects` describing something the loop should do (`:send`, `:log`); never performed inside a reducer.
- **Packet**: a flat map with `:packet/name` and the wire's own field names; see [[packet-specs]].
- **Phase**: the protocol state (`:handshake`, `:login`, `:configuration`, `:play`), advanced only by emitting a transition packet; see [[connection-phases]].
- **Intent**: a value in `:plan/intent` (`{:intent/kind :dig ...}`) advanced one tick at a time by `intent/run`; see [[goals-and-intents]].
- **Goal**: a row of data in `resources/clojurecraft/goals.edn` (`:goal/needs :goal/provides :goal/done? :goal/act`); `plan/done-by` and `plan/act` are the registries that give it meaning.
- **Sighting**: an observation fact `{:sight/pos :sight/state :sight/at}` in the DataScript value `:world/facts`; see [[memory-sightings]].
- **Recording**: an EDN file of every event of a run, replayable with no server; see [[record-replay]].
- **Sim**: the pure server model in `sim.clj` that tests run the whole bot against; see [[server-model]].
- **Gym**: a run of one goal with prerequisites given over RCON and judged by `RESULT` plus an RCON truth read; see [[gym-on-runners]].
- **Trunk bottom**: a remembered log with no log below it; the walk target for the wood goal.
- **Window 0**: the player's own inventory screen; slots 0-4 are the 2x2 crafting grid (0 = result) kept in `:window/grid`; see [[window-zero-model]], [[mc-window-zero-slots]].
- **State id**: the counter the server stamps on every window update and the client echoes in each click; an unanswered click is one whose state id never moves; see [[mc-state-ids-prediction]].
- **Prediction**: the `changed` slots and `cursor` a 775 click claims; we always claim nothing ([[predict-nothing]]), and the server adopts the cursor claim ([[phantom-cursor-stale-window]]).
- **Needs planner**: `make/next-intent :needs`, the walk from a target's provides through producer and recipe rows to one row whose needs are met; see [[make-goals]].
- **Needs / provides**: a goal row's maps of `:item/<name> n`, `:tag/<tag> n` or `:block/<name> :near`; the needs planner traces provides back through producer rows; see [[make-goals]].
- **View**: a map describing one window for the click rules (`:view/id :view/state-id :view/size :view/grid :view/slot-of`); see [[window-views]].
- **Menu type**: the registry index an `open-screen` names; 12 is the crafting table ([[mc-crafting-table-window]]).
- **Kit**: the `:kit` goal, a crafting table and four sticks; `--until table`.
- **Junk craft**: a pressure plate or button minted by leftover planks in the grid; see [[junk-crafts]].
- **Stale window**: `:stale-window`, the craft failure when a click goes unanswered for 3 s.
- **Gather chain**: walk then dig then collect toward a log (`wood/gather-next`), reused by any goal that needs logs.
- **Violation**: an entry in `:sim/violations`, something a real server would punish or a careful client never does; see [[sim-faults]].
- **Until**: `--until <name>`, matched against the target rows' `:goal/until` by `plan/goals-for`; the ids go on `:go/goals`; see [[goals-in-play-from-go]].
- **Landing**: `:plan/status :landing`, the wait between a fixture's `:go` with `:go/at` and the bot standing there; see [[harness-landing]].
- **775**: the protocol number of Minecraft 26.1.x; **26.1.2** is the server version every pin in the `game` pillar refers to.
