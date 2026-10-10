---
title: Property tests
description: What each generative property guarantees (keep-alive answered once, step total over decodable play packets, phase only along the transition table, clicks-then-match round trip) and how the generators are built from the spec table.
type: reference
tags: [bot, tooling, tests, properties]
aliases: [props_test, test.check, generative tests, field-gen, quick-check]
status: verified
lastUpdated: 2026-10-10
verifiedAgainst: 38bab1d
sourceRefs:
  - test/clojurecraft/props_test.clj#defn field-gen
  - test/clojurecraft/props_test.clj#every-keep-alive-is-answered-once
  - test/clojurecraft/props_test.clj#step-is-total-over-decodable-play-packets
  - test/clojurecraft/props_test.clj#the-phase-only-moves-along-the-transition-table
  - test/clojurecraft/recipe_test.clj#clicks-then-match-round-trip
  - test/clojurecraft/props_test.clj#a-dig-never-finishes-before-its-deadline
  - test/clojurecraft/make_test.clj#the-planner-only-picks-executable-intents
related:
  - "[[bot/tooling/_moc|Tooling]]"
  - "[[specs-and-instrumentation]]"
  - "[[packet-specs]]"
---

# Property tests

`props_test.clj` checks the reducer and the dig executor over generated inputs with test.check (200 cases each); `make_test.clj` checks the planner over every target (400 cases); `recipe_test.clj` adds one property over the recipe table (300 cases). Together they are the five properties `docs/hickey.md` names, plus the recipe round trip. Each was mutation-checked: an early FINISH or a need counted one short makes its property fail. The packet generator is derived from `packet/specs` itself, so a new s2c play spec is covered without editing the test.

## Key files
- `props_test.clj`, `field-gen` - a generator per wire type: structs become `gen/hash-map`, `[:vec T]` 0-3 elements, numbers within their wire range, floats finite in ±1e6, slots nil or `{:item :count}`, `:uuid` always nil.
- `props_test.clj`, `s2c-play-packet` - one of every `[:play :s2c name]` spec with generated fields.
- `props_test.clj`, `in-play` - a world folded through start, login-finished, finish-configuration and login.

## The properties
| Property | Guarantees |
|---|---|
| `every-keep-alive-is-answered-once` | for any i64 id, a play keep-alive produces exactly one effect, a keep-alive with the same id |
| `step-is-total-over-decodable-play-packets` | 1-20 arbitrary play packets never throw and leave a world valid against `::world` |
| `the-phase-only-moves-along-the-transition-table` | under arbitrary play packets the phase stays in play/configuration and equals the phase the emitted packets imply through `next-state` |
| `a-dig-never-finishes-before-its-deadline` | over uneven tick gaps (1-400 ms) and both diggable kinds, a dig sends exactly one START and one FINISH, and FINISH comes no sooner than `intent/finish-delay` (and so the vanilla break time) after START |
| `the-planner-only-picks-executable-intents` (make_test) | over generated inventories, a remembered table or none, and every target in the goal table: a done target plans nothing, and any other intent is executable from what is held and known (the essay's "never selects a goal whose needs are unmet") |
| `the-wood-goal-holds-in-generated-forests` (sim_test) | the whole bot against the server model, in 100 generated forests, always ends holding a log with no violation ([[server-model]]) |
| `clicks-then-match-round-trip` (recipe_test) | for every recipe that fits 2x2, a stock of one species per ingredient yields clicks whose right-clicked cells are exactly `placement`'s, and `match` on that grid gives the recipe's result |

## Gotchas
- The totality property is also a spec check: instrumented `game/step` validates the world on every call, so a handler that writes an attribute with the wrong shape fails here.
- Packets with a `:uuid` field always get nil; a handler that dereferences a UUID would not be exercised.
- The click property uses `(first (sort s))` as the species, so it checks the lay-out, not species choice.

## See also
- [[specs-and-instrumentation]] - what `::world` requires.
- [[test-fixtures]] - `fold` and friends.
