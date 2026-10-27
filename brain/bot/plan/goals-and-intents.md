---
title: Goals and intents
description: The goal table as EDN data, the registries that give a row meaning, how the planner picks the target in play every tick, and how an intent advances, finishes, fails and is retried.
type: reference
tags: [bot, plan, goals, intents]
aliases: [planner, plan/step, intent/run, goal table, goals.edn, done-by, plan/act, blacklist, retries]
status: verified
lastUpdated: 2026-10-14
verifiedAgainst: HEAD
sourceRefs:
  - src/clojurecraft/plan.clj#def goals
  - resources/clojurecraft/goals.edn#{:goal/id :wood :goal/priority 1 :goal/target? true :goal/until "wood" :goal/doc "hold one log"
  - src/clojurecraft/plan.clj#defmulti done-by
  - src/clojurecraft/plan.clj#defn can-abandon?
  - test/clojurecraft/armour_test.clj#a-click-unanswered-is-never-abandoned
  - src/clojurecraft/plan.clj#defmulti act
  - src/clojurecraft/plan.clj#defmulti next-intent
  - src/clojurecraft/plan.clj#defn run-intent
  - src/clojurecraft/plan.clj#def wait-ticks
  - test/clojurecraft/plan_test.clj#the-wait-fails-in-ticks-not-wall-ms
  - src/clojurecraft/plan.clj#defn fail-intent
  - src/clojurecraft/plan.clj#def placing-kinds
  - test/clojurecraft/plan_test.clj#a-cell-a-place-or-a-redig-chose-is-never-blacklisted
  - test/clojurecraft/plan_test.clj#a-walk-with-no-way-on-fails-no-path
  - src/clojurecraft/plan.clj#defn choose
  - src/clojurecraft/plan.clj#defn through
  - src/clojurecraft/plan.clj#defn current-until
  - src/clojurecraft/plan.clj#defn step-over
  - test/clojurecraft/make_test.clj#the-needs-planner-plans-over-the-table-it-is-given
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

The goal table is data in `resources/clojurecraft/goals.edn`: rows of `{:goal/id :goal/priority :goal/needs :goal/provides :goal/done? :goal/act}`. Keys of needs and provides are `:item/<name> n`, `:tag/<item-tag> n` and `:block/<name> :near`; a need may also be `:fuel/ticks n`, fuel that burns n game ticks ([[make-goals]]). Rows marked `:goal/target? true` are what a run is for (`:wood`, `:kit`, `:pickaxe`), each naming the `--until` value that selects it (`:goal/until`); the others are producers (gather a log, place a table). Recipes add one generated producer row each ([[make-goals]]).

## Key files
- `goals.edn` - the hand-written table; `plan.clj`, `goals` and `targets` load it.
- `plan.clj`, `done-by` - an open registry keyed by the row's `:goal/done?`, the name of a predicate (`:provided?` is registered by `make`).
- `plan.clj`, `act` - an open registry keyed by `:goal/act`: what to do once a row's needs are met (`:gather`, `:place`, `:craft`).
- `plan.clj`, `next-intent` - keyed by `:goal/plan` (default `:needs`); `make` registers the needs planner.
- `plan.clj`, `step-over` - the planner over a goal table it is given, `(plan world goals) → intent` in the essay's words: `choose`, `begin`, `plan-tick` and `next-intent` take the table, and so does the needs planner (`make/decide`, whose producer rows are a memoized function of the table). `plan/step` is `step-over` over the shipped `goals.edn`. At the edge, `goals-for` maps one `--until` name to its target, `current-until` names the furthest stage, and `through` names the chain up to it. A test plans over a table without the gather-log row and gets `:stuck`.
- `plan.clj`, `choose`, `run-intent` - the tick loop; `intent.clj`, `run` - the executors.

## How it works
1. Each tick: choose first. With nothing left, the plan is done; if the running intent serves another target, it is preempted. Otherwise run it; if it ended, plan again in the same tick (finish or fail, then choose).
2. With no intent: `choose` takes the highest-priority target in play (`:plan/goals` from the `:go` event, or every target, plus every row marked `:goal/always?`: the two escapes) whose `done-by` is false, and `next-intent` turns it into an intent, `{:plan/wait reason}`, or nil. A wait longer than `wait-ticks` (400 planner ticks, 20 s at 20 ticks a second) fails the plan. The deadline is counted in planner ticks, not wall ms: race 38680745815 runner 5 (issue #57) starved the bot's loop to 9 ticks in 21 s under a runner keeping 25 bots and two servers, and the wall clock failed the plan while the water trap's 20 ticks and the hunt's next routable hop were still owed theirs (`the-wait-fails-in-ticks-not-wall-ms`). Before choosing, `trap/trapped-tick` re-derives `:plan/trap` ([[water-escape]]).
3. A failed intent blacklists its target (when it has one, and it is not a `:place` or a `:redig`, `placing-kinds`: their target is the cell they chose to put a block in, and a table placed there next must still count as near; race 38477665442 bot Clj_r04 lost its table that way and went for wood with its last pickaxe, issue #43, `a-cell-a-place-or-a-redig-chose-is-never-blacklisted`) and counts an attempt; a success resets the count. An intent that fails `:no-path` (any kind, not only `:walk`) counts apart, in `:plan/unwalkable`: the route was accepted and then could not be walked, and the next candidate is tried (three such walks used to end a run that still had somewhere else to go), but `max-unwalkable` (12) in a row fail the plan, so a search whose places keep changing cannot run for hours (`walks-that-never-arrive-still-end-the-plan`). When an intent fails from the feet, an instant identical repeat (same kind, target, direction from the same feet) is intercepted by the retry policy: it steps back to the previous cell in `:plan/trail`, marks the failed feet cell in `:plan/boxed-at`, and re-plans, never killing a run while time remains.
4. No target left means `:plan/status :done`.

## Gotchas
- No item goal needs a method of its own: wood, kit and pickaxe are rows that differ only in data; `make_test` asserts the registered planners are `:needs`, `:escape` and `:wade` (the escapes and the shore trial, [[water-escape]]).
- Rows are retired, never rewritten: a superseded row (`:goal/superseded-by`) stays in the table and is never chosen, and a `:go` that names it is mapped to its replacement by `plan/current-id` ([[add-a-goal]]).
- Completion is re-derived from the world each tick, so a lost log is simply planned for again; nothing remembers "done".
- The planner re-chooses every active tick, even while an intent runs (`plan-tick`): no target left means `:done` and the running intent is abandoned `:goal-met`; a running intent whose `:intent/goal` is not the target chosen now (a higher-priority target regressed) is abandoned `:preempted` and the new target planned. Neither happens while the cursor holds an item (`can-abandon?`): the intent puts it down first; nor while one of the running intent's clicks is unanswered (`window/waiting`), since a click forgets the cursor as it sends and what it holds is unknown until the answer (issue #259: a `:wear` preempted between its pick-up and the answer left the helmet on the cursor, `a-helmet-and-chestplate-are-made-and-worn-against-the-model`). `abandon-intent` closes any open container, so the server is never left with one. A live pickaxe run ended with the craft abandoned `:goal-met` the tick the pickaxe arrived, and the table window closed. Escaping water is a target that preempts this way ([[water-escape]]): a running walk or dig yields to `:swim-out` the tick the trap is called. An abandon also drops `:plan/last`, so the next goal never continues a chain from it.

## See also
- [[make-goals]] - the needs planner, the generated recipe rows and the acts.
- [[add-a-goal]] - the recipe.
