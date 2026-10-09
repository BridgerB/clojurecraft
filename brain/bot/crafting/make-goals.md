---
title: Make goals
description: How goals with :goal/wants (:kit, :pickaxe) are satisfied by re-deriving the next recipe-graph action every tick, and how a 3x3 recipe chooses between crafting in an open table, opening, walking to, placing or making one.
type: reference
tags: [bot, crafting, plan, goals]
aliases: [:kit, :pickaxe, make.clj, goal/wants, table goal, with-table, decide]
status: verified
lastUpdated: 2026-10-09
verifiedAgainst: 5c7d6c1
sourceRefs:
  - src/clojurecraft/make.clj#defn decide
  - src/clojurecraft/make.clj#defn- with-table
  - src/clojurecraft/make.clj#defn- step-for
  - src/clojurecraft/make.clj#defmethod plan/next-intent :pickaxe
  - src/clojurecraft/plan.clj#a wooden pickaxe, placing a crafting table to make it
  - test/clojurecraft/sim_test.clj#places-a-table-and-crafts-a-wooden-pickaxe
related:
  - "[[bot/crafting/_moc|Crafting]]"
  - "[[recipe-graph]]"
  - "[[goals-and-intents]]"
  - "[[goals-in-play-from-go]]"
---

# Make goals

A goal row may carry `:goal/wants [[item-name n] ...]`. Two do: `:kit` (priority 2, `[[:crafting_table 1] [:stick 4]]`) and `:pickaxe` (priority 3, `[[:wooden_pickaxe 1]]`). `make.clj` answers both goals' multimethods with the same functions, asking [[recipe-graph]] for the next action over the current inventory with grid size **3**; nothing is stored, so a consumed or lost item is planned for again next tick.

## Key files
- `make.clj`, `action` - `(recipe/next-action counts wants 3)`; `goal-done?` is "action is nil".
- `make.clj`, `decide` - the next intent.
- `make.clj`, `step-for` - `:gather` → `wood/gather-next`; `:craft` → `{:intent/kind :craft :intent/recipe id :intent/window :inventory}`.
- `make.clj`, `with-table` - the ladder for a recipe that does not fit 2x2.

## How it works
1. A log-gathering chain in flight continues first (`wood/continue-gather`, [[gather-chain]]).
2. Then the action: nil → nothing; `:stuck` → `{:plan/wait :stuck}` (the planner fails it after 20 s); `:gather`, or a `:craft` whose recipe fits 2x2 → `step-for`.
3. A recipe that needs 3x3 goes to `with-table`, first match wins:
   - a remembered crafting table within 24 blocks (`memory/nearest`) **and** an open window of menu type 12 → `:craft` with `:intent/window :table`;
   - a remembered table within `open-reach` (4.5) of the eye → `:open-container` it;
   - a remembered table → `:walk` to it;
   - a table held → `:place` it;
   - else ask the graph for `[[:crafting_table 1]]` in size 2 and take that step (gather or craft), or wait `:stuck`.
4. In the sim, the pickaxe runs from one four-log tree through planks, sticks and a placed, opened table to the pickaxe, and the table window is closed at the end; no failed attempts and no violations.

## Gotchas
- `make.clj` must be required for the methods to register (`main.clj` and the sim test do); otherwise `goal-done?` hits `:default` (true) and the goal silently counts as done.
- The size-3 graph means "needs a table" is decided per recipe by `recipe/fits?` 2, not by the graph.
- A walk to a table is not tagged `:intent/for :log`, so the gather chain never mistakes it for a log walk.
- The table goal still reuses `:collect`, whose done test is "any log held" ([[collect-intent]]).

## See also
- [[craft-intent]], [[place-intent]], [[open-container-intent]] - the intents it chooses.
