---
title: Add a goal
description: Add a goal as a row of data in goals.edn, and only when the world needs a new kind of action, a registered act and an intent kind.
type: how-to
tags: [bot, how-to, plan]
aliases: [new goal, new intent, goals.edn row, register an act]
status: verified
lastUpdated: 2026-10-10
verifiedAgainst: 3452f18
sourceRefs:
  - resources/clojurecraft/goals.edn#{:goal/id :pickaxe :goal/priority 3 :goal/target? true
  - src/clojurecraft/plan.clj#defmulti act
  - src/clojurecraft/plan.clj#defmulti done-by
  - src/clojurecraft/intent.clj#defmulti run
  - src/clojurecraft/plan.clj#defn goals-for
related:
  - "[[bot/how-to/_moc|How-to]]"
  - "[[goals-and-intents]]"
  - "[[make-goals]]"
---

# Add a goal

## Steps
1. If the goal is "hold these items", it is one row: add `{:goal/id :stone-pickaxe :goal/priority 4 :goal/target? true :goal/until "stone-pickaxe" :goal/provides {:item/stone_pickaxe 1} :goal/done? :provided?}` to `resources/clojurecraft/goals.edn`. The needs planner finds the recipe row and everything under it ([[make-goals]]).
2. If something it needs has no producer (cobblestone is mined, not crafted), add a producer row that states its needs, its provides and its `:goal/act`, e.g. `{:goal/id :mine-stone :goal/needs {:item/wooden_pickaxe 1} :goal/provides {:item/cobblestone 1} :goal/act :mine :goal/done? :provided?}`.
3. Only a new kind of action is code: `(defmethod plan/act :mine [world row] ...)` returning an intent map, and the intent itself as `(defmethod intent/run :mine [world intent event] ...)` (see `craft.clj` for a staged one). A new completion rule is `(defmethod plan/done-by :some-predicate [world goal] ...)` named by the row's `:goal/done?`.
4. Give the target row a `:goal/until` name; `--until <name>` then selects it in the bot and the fixture alike.
5. Extend `sim.clj` so `sim/run` can reach the goal; add a `sim_test` case, and a property when the goal has a rule worth generating against.
6. Run `clojure -M:test` and `clojure -M:brain`; describe the new rows in [[make-goals]] if they change how needs resolve.

## See also
- [[goals-and-intents]]
