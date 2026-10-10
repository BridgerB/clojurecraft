---
title: RESULT line
description: Every field of the RESULT EDN line (:ok, :until, :reason, the game summary, :held, :plan) and the --until values that select goals, so a run log can be read and grepped.
type: reference
tags: [bot, runtime, result, ci]
aliases: [RESULT, result map, --until, planned, summary, :held]
status: verified
lastUpdated: 2026-10-09
verifiedAgainst: 5c7d6c1
sourceRefs:
  - src/clojurecraft/main.clj#defn result
  - src/clojurecraft/main.clj#def planned
  - src/clojurecraft/game.clj#defn summary
  - src/clojurecraft/plan.clj#defn summary
related:
  - "[[bot/runtime/_moc|Runtime]]"
  - "[[main-loop]]"
  - "[[ci-wood-workflow]]"
  - "[[goals-in-play-from-go]]"
---

# RESULT line

The bot prints exactly one line `RESULT {...}` on stdout (everything else goes to stderr) and exits 0 when `:ok` is true.

## Fields
| Key | Value |
|---|---|
| `:ok` | goal predicate true and neither closed nor disconnected |
| `:until` | the `--until` argument (`"replay"` for a replay) |
| `:reason` | `:goal`, `:disconnected`, `:closed`, the plan's `:plan/reason` when it failed (e.g. `:no-log`, `:stale-window`, `:stuck`), else `:timeout` |
| `:phase :pos :on-ground? :loaded?` | from `game/summary` |
| `:chunks :sightings :logs :inventory :entities` | counts: columns loaded, sightings, logs held, inventory slots used, tracked items |
| `:stats` | every `stats/*` attribute (keep-alives, teleports, chunks, unknown packet counts, pickups, last ack) |
| `:disconnected :closed` | reasons, nil when none |
| `:held` | `recipe/counts` of the inventory: `{item-name n}` |
| `:plan` | every `plan/*` attribute except the blacklist (status, goals, attempts, last intent, current intent, waiting reason), present once a plan began |

## --until values
`main/planned`: `"wood"` → goals `[:wood]`, `"table"` → `[:kit]`, `"pickaxe"` → `[:pickaxe]`. Any other value (e.g. `"play"`) plans nothing and succeeds once `:player/loaded?`.

## Gotchas
- Map key order is arbitrary; CI greps for `:ok true` anywhere on a line starting with `RESULT {`.
- A recording includes the hold period, so a replayed RESULT can show slightly higher counts than the live one ([[record-replay]]).

## See also
- [[ci-wood-workflow]] - the judge that reads it.
