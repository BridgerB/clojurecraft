---
title: Add a goal
description: Add a goal row and its two defmethods, and a new intent kind, in a new namespace that main requires.
type: how-to
tags: [bot, how-to, plan]
aliases: [new goal, new intent, defmethod next-intent]
status: verified
lastUpdated: 2026-10-09
verifiedAgainst: 60b624e
sourceRefs:
  - src/clojurecraft/plan.clj#def goals
  - src/clojurecraft/wood.clj#defmethod plan/goal-done? :wood
  - src/clojurecraft/intent.clj#defmulti run
  - src/clojurecraft/main.clj#[clojurecraft.wood]
related:
  - "[[bot/how-to/_moc|How-to]]"
  - "[[goals-and-intents]]"
---

# Add a goal

## Steps
1. Add a row to `plan/goals`: `{:goal/id :planks :goal/priority 2 :goal/doc "..."}`; lower priority number runs first.
2. In a new namespace: `(defmethod plan/goal-done? :planks [world _] ...)` reading the world only, and `(defmethod plan/next-intent :planks [world _] ...)` returning an intent map, `{:plan/wait reason}` or nil, usually keyed on `(:plan/last world)`.
3. New behaviour is a new intent kind: `(defmethod intent/run :craft [world intent event] ...)` returning the world with `:plan/intent` advanced, `:player/controls` set and packets emitted; call `intent/done` or `intent/fail`.
4. Require the namespace from `main.clj` so the methods register.
5. Extend `sim.clj` so `sim/run` can reach the goal; add a `plan_test`/`sim_test` case and, if the goal has a deadline, a property.
6. Write the brain note for the goal and link it from [[bot/plan/_moc|Plan]].

## See also
- [[goals-and-intents]]
