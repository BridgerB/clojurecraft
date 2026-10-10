---
title: Specs and instrumentation
description: What spec.clj specs (world attributes, events, effects, intents, goals, windows), which functions are fdef'd and instrumented in tests, and what is deliberately not specced.
type: reference
tags: [bot, tooling, spec, tests]
aliases: [spec.clj, clojure.spec, s/fdef, instrument, ::world, goals-valid?]
status: verified
lastUpdated: 2026-10-09
verifiedAgainst: 5c7d6c1
sourceRefs:
  - src/clojurecraft/spec.clj#(s/def ::world
  - src/clojurecraft/spec.clj#(s/fdef game/step
  - src/clojurecraft/spec.clj#def goals-valid?
  - test/clojurecraft/fixtures.clj#defn instrumented
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
- **events/effects/packets**: `:event/kind` in `#{:start :packet :tick :go :closed}`, `:event/rand` a double in [0, 1], `:go/goals`; `:effect/kind` in `#{:send :log}`; a packet needs only `:packet/name`.
- **plan**: intents (`:intent/kind` required; status, target, recipe optional; `:intent/window` in `#{:inventory :table}`, `:intent/item`), `:plan/status`, `:plan/blacklist`, `:plan/goals`, and goal rows (`::goal` requires `:goal/id :goal/priority :goal/provides :goal/done?`; needs, act, target? optional; need keys are keywords or item sets, amounts a count or `:near`).
- `::world` requires only `:bot/phase :bot/effects :time/now :time/tick`; everything else is optional, matching "absent, never nil-filled".

## Instrumented functions
`fdef`s exist for `game/step`, `plan/step`, `intent/run` (world, event, intent in; world out) and `plan/choose`. `fixtures/instrumented` instruments the first three for a whole test namespace; game, plan and sim tests use it. `goals-valid?` is a def evaluated at load: every row of `plan/goals` conforms to `::goal`.

## Gotchas
- `instrument` checks args only; the `:ret` specs are documentation unless a test calls `s/valid?` (the totality property does).
- Not specced: chunk column internals (`map?`), packet fields beyond the name, `:plan/intent` stage keys, `:sim/*`. Accretion is free: an extra key never fails a spec.

## See also
- [[property-tests]] - generators over these shapes.
