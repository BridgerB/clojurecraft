---
title: Property tests
description: What each generative property guarantees (keep-alive answered once, step total over decodable play packets, phase only along the transition table, clicks-then-match round trip) and how the generators are built from the spec table.
type: reference
tags: [bot, tooling, tests, properties]
aliases: [props_test, test.check, generative tests, field-gen, quick-check]
status: verified
lastUpdated: 2026-10-09
verifiedAgainst: 5c7d6c1
sourceRefs:
  - test/clojurecraft/props_test.clj#defn field-gen
  - test/clojurecraft/props_test.clj#every-keep-alive-is-answered-once
  - test/clojurecraft/props_test.clj#step-is-total-over-decodable-play-packets
  - test/clojurecraft/props_test.clj#the-phase-only-moves-along-the-transition-table
  - test/clojurecraft/recipe_test.clj#clicks-then-match-round-trip
related:
  - "[[bot/tooling/_moc|Tooling]]"
  - "[[specs-and-instrumentation]]"
  - "[[packet-specs]]"
---

# Property tests

`props_test.clj` checks the reducer over generated inputs with test.check (200 cases each); `recipe_test.clj` adds one property over the recipe table (300 cases). The packet generator is derived from `packet/specs` itself, so a new s2c play spec is covered without editing the test.

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
| `clicks-then-match-round-trip` (recipe_test) | for every recipe that fits 2x2, a stock of one species per ingredient yields clicks whose right-clicked cells are exactly `placement`'s, and `match` on that grid gives the recipe's result |

## Gotchas
- The totality property is also a spec check: instrumented `game/step` validates the world on every call, so a handler that writes an attribute with the wrong shape fails here.
- Packets with a `:uuid` field always get nil; a handler that dereferences a UUID would not be exercised.
- The click property uses `(first (sort s))` as the species, so it checks the lay-out, not species choice.

## See also
- [[specs-and-instrumentation]] - what `::world` requires.
- [[test-fixtures]] - `fold` and friends.
