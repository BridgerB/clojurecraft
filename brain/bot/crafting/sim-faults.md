---
title: Sim fault knobs and violations
description: The sim's fault inputs (:sim/drop-clicks, :sim/lag-ticks) and its record of client misbehaviour (:sim/violations - empty-result click, close with cursor, place into player), and the tests that use them.
type: reference
tags: [bot, tooling, sim, tests, crafting]
aliases: [drop-clicks, lag-ticks, network lag, sim/violations, click-inventory-while-open, click-on-empty-result, close-with-cursor, place-into-player, fault injection]
status: verified
lastUpdated: 2026-10-09
verifiedAgainst: 2d7f669
sourceRefs:
  - src/clojurecraft/sim.clj#Fault knobs are inputs, not hidden state
  - src/clojurecraft/sim.clj#defmethod on-packet [:play :container-close]
  - src/clojurecraft/sim.clj#defn init [{:keys [column spawn keep-alive-every drop-clicks inventory lag-ticks]
  - src/clojurecraft/sim.clj#:sim/lag-ticks in sim0 delays every server→client packet by that many ticks,
  - test/clojurecraft/sim_test.clj#no-blind-take-under-lag-or-a-dropped-click
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
- `sim.clj`, `init` - accepts `:lag-ticks` (server→client delay, applied in order by `run`), `:drop-clicks` (a set of 0-based click ordinals the server silently loses) and `:inventory` (a starting window-0 map); starts `:sim/violations []`, `:sim/clicks 0`, `:sim/state-id 1`.
- `sim.clj`, `on-packet [:play :container-click]` - increments `:sim/clicks` before checking the drop set, so ordinal n is the n-th click the bot ever sent, in any window.

## Violations recorded
| Entry | When |
|---|---|
| `[:click-on-empty-result mode]` | a click on slot 0 when the grid makes nothing |
| `[:close-with-cursor item]` | `container-close` while the cursor is loaded (vanilla would drop the item) |
| `[:click-inventory-while-open window-id]` | a window-0 click while a container is open (vanilla ignores it; the sim records and ignores it) |
| `[:place-into-player pos]` | `use-item-on` whose destination overlaps the player's box ([[sim-placement]]) |

## How the tests use them
- `no-blind-take-under-lag-or-a-dropped-click` enumerates every single lost click (0-27) at lags 0, 3 and 10 ticks (87 runs) and requires no violations, no item but the kit's own (no buttons, no pressure plates), and a table plus sticks or an empty grid. It was mutation-checked: with the grid reclaim disabled, drops 5-8 leave a dirty grid and drop 14 mints a button; a 12-sample random version of the same property missed them, which is why it is enumerated.
- The kit and pickaxe end-to-end tests assert `:sim/violations` is `[]`.
- `a-lost-click-is-a-stale-window-never-a-blind-take` drops click 1 (the right-click that lays the first log into the grid). The bot waits, fails `:stale-window` after 3 s, the planner retries, `:settle` puts down the log still on the cursor, and the goal still completes with 4 sticks and no violations.

## Limits
Lost clicks and uniform lag are injectable. Reordered or duplicated packets, a lost `set-cursor-item`, and server-side resyncs are not modelled.

## See also
- [[craft-intent]] - the recovery path being exercised.
