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
  - src/clojurecraft/plan.clj#defn note-churn
  - src/clojurecraft/churn_guard.clj#defn violation-with
  - test/clojurecraft/churn_guard_test.clj#a-choice-over-in-its-first-tick-is-a-violation
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

**Retry policy** (`plan/fail-intent`, `plan/recover`). `max-attempts` 3 failures in a row (or `max-unwalkable` walks with no path) no longer end the run: unless it is stalled (`plan/stalled?`: no intent that moved the run on, `plan/progress?`, finished for `stall-ms` 15 min, from `:plan/done-at` or `:plan/since`) the counts start over, the last failure is forgotten and the next walk goes to the trail's cell `regroup-distance` 8 back (`:plan/regroup`, a `:walk` for `:regroup`), planning again from a new spot. A `:retrying` wait that runs out is the same; every other wait ends the run as before. Rests, back-offs, regroup and retreat walks finish without counting as progress (`non-progress`), so a loop of them still stalls. Blaze-race-u (run 41156125534) ended at 4 health after three `:place` `:no-support` in 300 ms, and at 16 after a camp that found no blaze twice, with hours left. A recovery does nothing within `recover-cooldown-ms` 10 s of the last (`:plan/recover-at`): the last failure stays, so the identical retry waits or steps back, and the regroup walk starts under an always row too (only an escape row is spared); pearl-fight-i job 3 (run 41178660163) recovered 34 times in a second under `:defend`'s failing `:place` and `:pillar`. Tests: `three-failures-in-a-row-start-over-and-do-not-end-the-run`, `a-run-with-nothing-done-for-the-stall-ends-with-the-reason`, `a-retrying-wait-starts-over-unless-stalled`.

**Stuck watchdog** (`plan/watch`, `plan/unstick`, run each tick by `step-over`). Once a second the planner compares the chosen row's `plan/progress-key` (a multimethod keyed by `:goal/plan`; by default the items held by kind and the stages reached) and the box of the feet's places since progress was last seen (`:plan/watch`). With neither changed (the key, or a box spanning `stuck-extent` 16 blocks, for rows that count moves) for `stuck-ms` 10 min the bot is made to go elsewhere: the running intent is dropped, the row's anchors (`plan/anchors`: `:pearls/shelter`, `:pocket/site`) are forgotten, and a walk `explore-distance` 48 blocks away in a direction from the tick's `:event/rand` (`:plan/explore`, a `:walk` for `:explore`, non-progress) starts; the watch starts over, so it fires at most every 10 min. Never a fight, only a walk; an escape row is not delayed. A defence running (`plan/holds?`) is not dropped while the bot has taken damage within `quiet-ticks` 6000 (5 min; a quiet hold may be dropped, `defended?`), the blaze row gets `stuck-after` 20 min (a camp can wait 14 to 16 min for its first hurt blaze), the walk goes to the farthest cell of the trail at least `regroup-distance` away that has a route (`explore-cell`, `routable?`: `explore-tries` 4 farthest first, then 4 nearest first; the cell 48 out toward the trail only when none has one, which failed `:no-path` in four of six firings of pearl-fight-k job 2, run 41197008609), and a stuck pearl hunt also forgets `:pearls/mouth`, `:pearls/relocate`, the boxed and given-up sets and the blacklist and restarts the `:pearls/gain` clock (`anchors`, `resets`, `clocks`). It waits while a hostile (`entity/hostiles`: an enderman only once angry) is within `menace-radius` 24, up to `menace-grace-ms` 5 min past the line (a parked one is there for good), and then plans no walk into it. Every firing sets `:plan/avoid` (`avoid-ms` 5 min, not extended by a second firing inside it, `avoid-radius` 16 blocks of the feet): a row's choice of a site asks `plan/avoiding?` and passes over the one it was stuck at (blaze-race-z6, run 41201377711: 12 of 12 firings were followed within 50 ms by `:pocket` at the same site): pearl-fight-k job 2 (run 41197008609) fired it at 13:49:49, left the burrow, fought enderman 4501 half a second later and died in lava. The pearl row's key is the pearls held, the `:provoke` and `:fight` intentions ended (`memory/done-count`); the blaze row's is the rods held, the fights ended and the spawners on record; both are `fact-rows`, for which a move is no progress (blaze-race-z job 1, run 41173997255, tunnelled 106 min round a ring 67 blocks across, digging netherrack every leg). Pearl-fight-i jobs 1 and 2 (run 41178660163) stood at one block for 2 h 20 min (1631 lookouts) and blaze-race-z job 3 camped 119 min. Tests: `a-bot-at-one-block-for-stuck-ms-is-sent-elsewhere`, `progress-or-a-move-restarts-the-watch`.

**Failure history** (`plan/remember-failure`, `plan/failed-before?`). `:plan/failed` remembers the last failure and `start-intent` clears it, so a row that plans A, then B, then A again (a `:pillar` that fails `:boxed`, a `:dig` cut 50 ms in, the `:pillar` again: blaze-race-z9 job 3, run 41222838848, 166 failures in 29 s; z8 jobs 3 and 4, z7 job 2) was never seen repeating. `:plan/failures` keeps the last 8 failures of the last 30 s as `{:choice (choice-keys of the plan) :feet :at}`, across the intents that start after them. A fresh plan is refused when it failed before from these feet, or 3 times in the window from anywhere (so an intent with no target, a pillar, is refused as a target would be by the blacklist); `:intent/top` is a choice key. Refused means the existing retry path: a retreat walk back along the trail, else a `:retrying` wait, which recovers (`plan/recover`); but with a hostile within `menace-radius` or the bot on fire the wait is never taken: with no trail cell to walk to the row's plan goes ahead rather than the bot standing still. `recover` clears the history. Tests: `a-pillar-that-failed-is-not-planned-again-after-a-dig`, `a-choice-with-no-target-is-refused-by-the-choice-it-is`.

## Gotchas
- No item goal needs a method of its own: wood, kit and pickaxe are rows that differ only in data; `make_test` asserts the registered planners are `:needs`, `:escape` and `:wade` (the escapes and the shore trial, [[water-escape]]).
- Rows are retired, never rewritten: a superseded row (`:goal/superseded-by`) stays in the table and is never chosen, and a `:go` that names it is mapped to its replacement by `plan/current-id` ([[add-a-goal]]).
- Completion is re-derived from the world each tick, so a lost log is simply planned for again; nothing remembers "done".
- The planner re-chooses every active tick, even while an intent runs (`plan-tick`): no target left means `:done` and the running intent is abandoned `:goal-met`; a running intent whose `:intent/goal` is not the target chosen now (a higher-priority target regressed) is abandoned `:preempted` and the new target planned. Neither happens while the cursor holds an item (`can-abandon?`): the intent puts it down first; nor while one of the running intent's clicks is unanswered (`window/waiting`), since a click forgets the cursor as it sends and what it holds is unknown until the answer (issue #259: a `:wear` preempted between its pick-up and the answer left the helmet on the cursor, `a-helmet-and-chestplate-are-made-and-worn-against-the-model`). `abandon-intent` closes any open container, so the server is never left with one. A live pickaxe run ended with the craft abandoned `:goal-met` the tick the pickaxe arrived, and the table window closed. Escaping water is a target that preempts this way ([[water-escape]]): a running walk or dig yields to `:swim-out` the tick the trap is called. An abandon also drops `:plan/last`, so the next goal never continues a chain from it.

- CHURN (issue #615): the planner's choice and an intent's own end check reading one state differently starts an intent that ends in its first tick, chosen again every 50 ms while the bot stands still (a cut restarted, `:perch`, `:hold`, a dragon `:back-off`, a ghast cover `:walk`: six times in a day). `plan-tick` marks the intent that ended (`mark-ended`, `:plan/ended`) and counts a start of the same kind, target and `:intent/for` the tick after (`churn-count`, `note-churn`, `:plan/churn`, a `churn <kind> <target> xN` line every `churn-log-every` 20 repeats): observation only. `churn-guard/violation` is the invariant over any world: the chosen intent run one tick is neither done nor failed unless its kind is in `instantaneous-kinds`; `clojure -M -m clojurecraft.churn-guard <snapshot>...` (a probe with the worlds restored) lists every recorded state that breaks it.

## See also
- [[make-goals]] - the needs planner, the generated recipe rows and the acts.
- [[add-a-goal]] - the recipe.
