---
title: Goals in play chosen by :go
description: Why the set of goals a run pursues arrives on the :go event (:go/goals) and is stored as :plan/goals, instead of being a flag, a config or the whole goal table.
type: decision
tags: [bot, decision, plan]
aliases: [go/goals, plan/goals, --until, planned, which goals run]
status: verified
lastUpdated: 2026-10-09
verifiedAgainst: 5c7d6c1
sourceRefs:
  - src/clojurecraft/plan.clj#defn choose
  - src/clojurecraft/plan.clj#defn- begin
  - src/clojurecraft/main.clj#def planned
  - src/clojurecraft/spec.clj#(s/def :go/goals (s/coll-of keyword?))
related:
  - "[[bot/decisions/_moc|Decisions]]"
  - "[[goals-and-intents]]"
  - "[[result-line]]"
---

# Goals in play chosen by :go

## The choice
`main/planned` maps `--until` to goal ids (`"wood"` → `[:wood]`, `"table"` → `[:kit]`, `"pickaxe"` → `[:pickaxe]`); the loop puts them on the `:go` event as `:go/goals`; `plan/begin` stores them as the set `:plan/goals`; `plan/choose` only considers goals in that set (every goal when it is absent). The goal table itself stays whole.

## What was rejected
- Running the whole table always: the `:wood` gym would go on to craft the kit, so its judge (one log held) and its timing would depend on unrelated goals.
- A CLI flag read inside the planner or a dynamic var: a hidden input that a recording would not contain. On the `:go` event the choice is in the recording, so `clojure -M:replay` re-runs the same goals ([[record-replay]]).

## What would change the answer
Nothing for the mechanism. A full race simply sends every goal id; a gym matrix ([[ci-wood-workflow]]) sends one row's.

## See also
- [[randomness-on-the-tick]] - the same rule for another input.
