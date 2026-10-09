---
title: Goals and intents
description: The goal table, how the planner re-derives the current goal every tick, and how an intent advances, finishes, fails and is retried.
type: reference
tags: [bot, plan, goals, intents]
aliases: [planner, plan/step, intent/run, goal table, blacklist, retries]
status: verified
lastUpdated: 2026-10-09
verifiedAgainst: 5c7d6c1
sourceRefs:
  - src/clojurecraft/plan.clj#def goals
  - src/clojurecraft/plan.clj#defmulti goal-done?
  - src/clojurecraft/plan.clj#defmulti next-intent
  - src/clojurecraft/plan.clj#defn- run-intent
  - src/clojurecraft/intent.clj#defmulti run
  - src/clojurecraft/wood.clj#defmethod plan/next-intent :wood
  - src/clojurecraft/plan.clj#defn choose
related:
  - "[[bot/plan/_moc|Plan]]"
  - "[[dig-timeline]]"
  - "[[add-a-goal]]"
---

# Goals and intents

`plan/goals` is a table of `{:goal/id :goal/priority :goal/doc}` plus optional `:goal/wants`; today `:wood` (priority 1, hold one log), `:kit` (priority 2, a crafting table and four sticks) and `:pickaxe` (priority 3, a wooden pickaxe via a placed table). Two multimethods keyed on `:goal/id` answer for each goal: `goal-done?` (is the world already the way it wants) and `next-intent` (given the world and `:plan/last`, the intent just finished: an intent map, `{:plan/wait reason}` for not yet, or nil). Intents are `{:intent/kind k :intent/status :active|:done|:failed ...}` advanced by `intent/run`, a multimethod on `:intent/kind` (`:walk`, `:dig`, `:collect` in `intent.clj`; `:craft` in `craft.clj`; `:place` and `:open-container` in `place.clj`).

## Key files
- `plan.clj`, `goals`, `goal-done?`, `next-intent`, `run-intent` - the planner; `plan/step` reacts to `:go` (begin) and `:tick`.
- `intent.clj`, `run` - the executors; each returns the world with `:plan/intent`, `:player/controls` and effects updated.
- `plan.clj`, `choose` - the highest-priority goal in play (`:plan/goals`, or every goal when absent) that is not done.
- `wood.clj`, `next-intent :wood` - walk then dig then collect; a fresh search when nothing is in flight.
- `make.clj`, `next-intent :kit` - the recipe graph's next action ([[make-goals]]).

## How it works
1. Each tick with an active intent: run it; if it ended, plan again in the same tick (finish or fail, then choose).
2. With no intent: choose the highest-priority goal in play that is not done ([[goals-in-play-from-go]]); ask it for the next intent; start it, or wait; a wait longer than `wait-timeout` (20 s) fails the plan with the wait reason.
3. A failed intent blacklists its target and counts an attempt; `max-attempts` (3) fails the plan.
4. No goal left means `:plan/status :done`.

## Gotchas
- Goal completion is re-derived from the world each tick, so a lost log is simply chosen again; nothing remembers "done".
- The planner never re-chooses while an intent is active; preemption (escape water) is a planner change, tracked in issue #5.

## See also
- [[dig-timeline]] - the dig intent's schedule.
- [[walk-intent]], [[collect-intent]], [[craft-intent]] - the other executors.
