---
title: Specs and instrumentation
description: What spec.clj specs (world attributes, events, effects, intents, goals, windows), which functions are fdef'd and instrumented in tests, and what is deliberately not specced.
type: reference
tags: [bot, tooling, spec, tests]
aliases: [spec.clj, clojure.spec, s/fdef, instrument, ::world, goals-valid?]
status: verified
lastUpdated: 2026-10-10
verifiedAgainst: 6a96171
sourceRefs:
  - src/clojurecraft/spec.clj#(s/def ::world
  - src/clojurecraft/spec.clj#(s/fdef game/step
  - src/clojurecraft/spec.clj#def goals-valid?
  - src/clojurecraft/spec.clj#defn packet-ok?
  - src/clojurecraft/spec.clj#def wire-types
  - test/clojurecraft/sim_test.clj#every-packet-the-bot-sends-fits-the-table
  - test/clojurecraft/fixtures.clj#defn instrumented
  - src/clojurecraft/spec.clj#(s/fdef sim/step
  - src/clojurecraft/spec.clj#defn requires
  - src/clojurecraft/spec.clj#def selected
  - test/clojurecraft/selection_test.clj#a-world-without-what-a-function-selects-is-refused
related:
  - "[[bot/tooling/_moc|Tooling]]"
  - "[[world-value]]"
  - "[[property-tests]]"
---

# Specs and instrumentation

`spec.clj` holds the shape of the information model: what an attribute is, never what a function requires. It is loaded only by tests (and anything that requires it); nothing in the hot loop validates.

## What is specced
- **bot/time**: `:bot/phase` (the four phases), `:bot/effects` (vector of effects), `:bot/sequence`, `:time/now`, `:time/tick` (ints).
- **player**: `:player/pos` and `:player/vel` (vectors of 3 doubles), `:player/look` (2 doubles), booleans, `:player/inventory` (`{int {:item :count}}`), `:player/controls` (optional `:control/*` keys).
- **window**: `:window/state-id`, `:window/grid` (`{#{0..4} slot-item}`), `:window/cursor`, `:window/open` (`{:window/id :window/menu-type}` plus optional `:window/state-id` and `:window/slots`), `:player/held-slot` (0-8).
- **world**: chunks, the block overlay (`{[int int int] int}`), sightings, entities.
- **events/effects/packets**: `:event/kind` in `#{:start :packet :tick :go :closed}`, `:event/rand` a double in [0, 1], `:go/goals`, `:go/at`; `:effect/kind` in `#{:send :log}`.
- **packets, from the table**: `::packet` is derived from `packet/specs`, so it can never drift from what the codec reads and writes. A packet whose name the table models must carry every field of one of that name's shapes (`shapes`), each fitting its wire type in range (`wire-types`: an `:i8` is -128..127, a `:varint` an int32, a `:slot` nil or `{:item :count}`); extra keys are fine. Names the table does not model (`:closed`, `:unknown`, `:decode-error`) need only the name.
- **plan**: intents (`:intent/kind` required; status, target, recipe optional; `:intent/window` in `#{:inventory :table}`, `:intent/item`), `:plan/status`, `:plan/blacklist`, `:plan/goals`, and goal rows (`::goal` requires `:goal/id :goal/priority :goal/provides :goal/done?`; needs, act, target? optional; need keys are keywords or item sets, amounts a count or `:near`).
- `::world` requires only `:bot/phase :bot/effects :time/now :time/tick`; everything else is optional, matching "absent, never nil-filled".

## Selection, apart from schema
*Maybe Not* separates schema (what an attribute is) from selection (what a function needs). The attribute specs above are the schema. Each function that reads the world states its selection as an `fdef` on its world argument, built with `requires`: a presence check only, so shapes stay the schema's job. `selected` lists them: `game/eye` and `physics/step` need `:player/pos`, the inventory queries `:player/inventory`, the terrain queries `:world/chunks`, the memory queries `:world/facts`, `make/decide` all three, and so on. `fixtures/instrumented` instruments them with the reducers, so every test call is checked; the full suite's wall time did not change (91 s before, 92 s after). `selection_test` shows a world without a selected key is refused, and weakening a selection makes it fail.

## Instrumented functions
`fdef`s exist for `game/step`, `plan/step`, `intent/run` (world, event, intent in; world out), `plan/choose`, and the server model's reducer `sim/step` (`::sim`, `::sim-event`; every packet in `:sim/out` must fit the packet table). `fixtures/instrumented` instruments the four reducers for a whole test namespace; game, plan and sim tests use it. Making the model send a `login-finished` without its username fails the sim tests. `goals-valid?` is a def evaluated at load: every row of `plan/goals` conforms to `::goal`.

## Gotchas
- `instrument` checks args only; the `:ret` specs are documentation unless a test calls `s/valid?` (the totality property does).
- Instrumentation sees what a reducer is given, not the effects it returns (the fold clears them first), so `sim_test`'s `every-packet-the-bot-sends-fits-the-table` checks every packet the bot sends over a whole pickaxe run; dropping one field from the dig's START packet fails it.
- The packet spec caught hand-built test packets the decoder can never produce (a bare `{:packet/name :login-finished}`); `fixtures/login-finished` is the real shape.
- Not specced: chunk column internals (`map?`) and a few server-model internals kept as plain maps (`:sim/window`, `:sim/dig`, `:sim/cursor`). Accretion is free: an extra key never fails a spec.

## See also
- [[property-tests]] - generators over these shapes.
