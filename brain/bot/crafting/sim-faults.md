---
title: Sim fault knobs and violations
description: The sim's fault-injection input (:sim/drop-clicks) and its record of client misbehaviour (:sim/violations - empty-result click, close with cursor, place into player), and the tests that use them.
type: reference
tags: [bot, tooling, sim, tests, crafting]
aliases: [drop-clicks, sim/violations, click-on-empty-result, close-with-cursor, place-into-player, fault injection]
status: verified
lastUpdated: 2026-10-09
verifiedAgainst: 5c7d6c1
sourceRefs:
  - src/clojurecraft/sim.clj#Fault knobs are inputs, not hidden state
  - src/clojurecraft/sim.clj#defmethod on-packet [:play :container-close]
  - src/clojurecraft/sim.clj#defn init [{:keys [column spawn keep-alive-every drop-clicks inventory]
  - src/clojurecraft/sim.clj#(update :sim/violations conj [:place-into-player dest])
  - test/clojurecraft/sim_test.clj#a-lost-click-is-a-stale-window-never-a-blind-take
related:
  - "[[bot/crafting/_moc|Crafting]]"
  - "[[server-model]]"
  - "[[craft-intent]]"
---

# Sim fault knobs and violations

Faults are inputs to `sim/init`, never hidden randomness, so a faulty run is as reproducible as a clean one.

## Key files
- `sim.clj`, `init` - accepts `:drop-clicks` (a set of 0-based click ordinals the server silently loses) and `:inventory` (a starting window-0 map); starts `:sim/violations []`, `:sim/clicks 0`, `:sim/state-id 1`.
- `sim.clj`, `on-packet [:play :container-click]` - increments `:sim/clicks` before checking the drop set, so ordinal n is the n-th click the bot ever sent, in any window.

## Violations recorded
| Entry | When |
|---|---|
| `[:click-on-empty-result mode]` | a click on slot 0 when the grid makes nothing |
| `[:close-with-cursor item]` | `container-close` while the cursor is loaded (vanilla would drop the item) |
| `[:place-into-player pos]` | `use-item-on` whose destination overlaps the player's box ([[sim-placement]]) |

## How the tests use them
- The kit and pickaxe end-to-end tests assert `:sim/violations` is `[]`.
- `a-lost-click-is-a-stale-window-never-a-blind-take` drops click 1 (the right-click that lays the first log into the grid). The bot waits, fails `:stale-window` after 3 s, the planner retries, `:settle` puts down the log still on the cursor, and the goal still completes with 4 sticks and no violations.

## Limits
Only lost clicks are injectable. Reordered or duplicated packets, a lost `set-cursor-item`, and server-side resyncs are not modelled.

## See also
- [[craft-intent]] - the recovery path being exercised.
