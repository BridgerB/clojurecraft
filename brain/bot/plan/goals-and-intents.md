---
title: Goals and intents
description: The goal table as EDN data, the registries that give a row meaning, how the planner picks the target in play every tick, and how an intent advances, finishes, fails and is retried.
type: reference
tags: [bot, plan, goals, intents]
aliases: [planner, plan/step, intent/run, goal table, goals.edn, done-by, plan/act, blacklist, retries]
status: verified
lastUpdated: 2026-10-10
verifiedAgainst: 3452f18
sourceRefs:
  - src/clojurecraft/plan.clj#def goals
  - resources/clojurecraft/goals.edn#{:goal/id :wood :goal/priority 1 :goal/target? true :goal/until "wood" :goal/doc "hold one log"
  - src/clojurecraft/plan.clj#defmulti done-by
  - src/clojurecraft/plan.clj#defmulti act
  - src/clojurecraft/plan.clj#defmulti next-intent
  - src/clojurecraft/plan.clj#defn run-intent
  - src/clojurecraft/plan.clj#defn choose
  - src/clojurecraft/plan.clj#defn abandon-intent
  - test/clojurecraft/plan_test.clj#done-is-re-derived-every-tick-even-mid-intent
  - test/clojurecraft/plan_test.clj#a-regressed-target-preempts-the-running-intent
  - src/clojurecraft/intent.clj#defmulti run
related:
  - "[[bot/plan/_moc|Plan]]"
  - "[[make-goals]]"
  - "[[dig-timeline]]"
  - "[[add-a-goal]]"
---

# Goals and intents

The goal table is data in `resources/clojurecraft/goals.edn`: rows of `{:goal/id :goal/priority :goal/needs :goal/provides :goal/done? :goal/act}`. Keys of needs and provides are `:item/<name> n`, `:tag/<item-tag> n` and `:block/<name> :near`. Rows marked `:goal/target? true` are what a run is for (`:wood`, `:kit`, `:pickaxe`), each naming the `--until` value that selects it (`:goal/until`); the others are producers (gather a log, place a table). Recipes add one generated producer row each ([[make-goals]]).

## Key files
- `goals.edn` - the hand-written table; `plan.clj`, `goals` and `targets` load it.
- `plan.clj`, `done-by` - an open registry keyed by the row's `:goal/done?`, the name of a predicate (`:provided?` is registered by `make`).
- `plan.clj`, `act` - an open registry keyed by `:goal/act`: what to do once a row's needs are met (`:gather`, `:place`, `:craft`).
- `plan.clj`, `next-intent` - keyed by `:goal/plan` (default `:needs`); `make` registers the needs planner.
- `plan.clj`, `choose`, `run-intent` - the tick loop; `intent.clj`, `run` - the executors.

## How it works
1. Each tick: choose first. With nothing left, the plan is done; if the running intent serves another target, it is preempted. Otherwise run it; if it ended, plan again in the same tick (finish or fail, then choose).
2. With no intent: `choose` takes the highest-priority target in play (`:plan/goals` from the `:go` event, or every target) whose `done-by` is false, and `next-intent` turns it into an intent, `{:plan/wait reason}`, or nil. A wait longer than `wait-timeout` (20 s) fails the plan.
3. A failed intent blacklists its target (when it has one) and counts an attempt; a success resets the count, so `max-attempts` (3) means three failures in a row.
4. No target left means `:plan/status :done`.

## Gotchas
- No goal needs a method of its own: wood, kit and pickaxe are rows that differ only in data, and `make_test` asserts `:needs` is the only `next-intent` registered.
- Rows are retired, never rewritten: a superseded row (`:goal/superseded-by`) stays in the table and is never chosen, and a `:go` that names it is mapped to its replacement by `plan/current-id` ([[add-a-goal]]).
- Completion is re-derived from the world each tick, so a lost log is simply planned for again; nothing remembers "done".
- The planner re-chooses every active tick, even while an intent runs (`plan-tick`): no target left means `:done` and the running intent is abandoned `:goal-met`; a running intent whose `:intent/goal` is not the target chosen now (a higher-priority target regressed) is abandoned `:preempted` and the new target planned. Neither happens while the cursor holds an item (`can-abandon?`): the intent puts it down first. `abandon-intent` closes any open container, so the server is never left with one. A live pickaxe run ended with the craft abandoned `:goal-met` the tick the pickaxe arrived, and the table window closed. Escaping water (issue #5) will be a target that preempts this way.

## See also
- [[make-goals]] - the needs planner, the generated recipe rows and the acts.
- [[add-a-goal]] - the recipe.
