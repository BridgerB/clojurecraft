---
title: RESULT line
description: Every field of the RESULT EDN line (:ok, :until, :reason, the game summary, :held, :plan) and the --until values that select goals, so a run log can be read and grepped.
type: reference
tags: [bot, runtime, result, ci]
aliases: [RESULT, result map, --until, goals-for, summary, :held]
status: verified
lastUpdated: 2026-10-10
verifiedAgainst: 96ac5ed
sourceRefs:
  - src/clojurecraft/main.clj#defn result
  - src/clojurecraft/plan.clj#defn goals-for
  - src/clojurecraft/game.clj#defn summary
  - src/clojurecraft/plan.clj#defn summary
related:
  - "[[bot/runtime/_moc|Runtime]]"
  - "[[main-loop]]"
  - "[[gym-on-runners]]"
  - "[[goals-in-play-from-go]]"
---

# RESULT line

The bot prints exactly one line `RESULT {...}` on stdout (everything else goes to stderr) and exits 0 when `:ok` is true.

## Fields
| Key | Value |
|---|---|
| `:ok` | goal predicate true and neither closed nor disconnected |
| `:until` | the `--until` argument (`"replay"` for a replay) |
| `:reason` | `:goal`, `:disconnected`, `:closed`, the plan's `:plan/reason` when it failed (e.g. `:no-log`, `:stale-window`, `:stuck`, `:no-landing`), else `:timeout` |
| `:phase :pos :on-ground? :loaded?` | from `game/summary` |
| `:chunks :sightings :logs :inventory :entities` | counts: columns loaded, sightings, logs held, inventory slots used, tracked items |
| `:stats` | every `stats/*` attribute (keep-alives, teleports, chunks, unknown packet counts, pickups, last ack) |
| `:disconnected :closed` | reasons, nil when none |
| `:held` | `recipe/counts` of the inventory: `{item-name n}` |
| `:plan` | every `plan/*` attribute except the blacklist (status, goals, landing `:plan/go-at`, attempts, last intent, current intent, waiting reason), present once a plan began |

## --until values
`plan/goals-for` reads `:goal/until` from the target rows: `"wood"` → goals `[:wood]`, `"table"` → `[:kit]`, `"pickaxe"` → `[:pickaxe]`. Any other value (e.g. `"play"`) plans nothing and succeeds once `:player/loaded?`.

## Gotchas
- Map key order is arbitrary; CI greps for `:ok true` anywhere on a line starting with `RESULT {`.
- A recording ends at RESULT and holds a copy of it, so a replay reaches exactly the live RESULT (`:replay/result :identical`, [[record-replay]]).
- With no position yet (a run that ended before its first teleport) there is no `:table/pos`; RESULT is still printed, which the gym's judge depends on.

## See also
- [[gym-on-runners]] - the judge that reads it.
