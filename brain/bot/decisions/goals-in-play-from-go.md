---
title: Goals in play chosen by :go
description: Why the set of goals a run pursues arrives on the :go event (:go/goals) and is stored as :plan/goals, instead of being a flag, a config or the whole goal table.
type: decision
tags: [bot, decision, plan]
aliases: [go/goals, plan/goals, --until, goals-for, goal/until, which goals run]
status: verified
lastUpdated: 2026-10-14
verifiedAgainst: HEAD
sourceRefs:
  - src/clojurecraft/plan.clj#defn choose
  - src/clojurecraft/plan.clj#defn begin
  - src/clojurecraft/plan.clj#defn goals-for
  - src/clojurecraft/plan.clj#defn through
  - resources/clojurecraft/goals.edn#:goal/until "wood"
  - src/clojurecraft/spec.clj#(s/def :go/goals (s/coll-of keyword?))
  - test/clojurecraft/plan_test.clj#the-current-stage-is-the-furthest-target
  - src/clojurecraft/plan.clj#defn reach
  - test/clojurecraft/plan_test.clj#a-chained-run-does-not-go-back-for-a-step-it-reached
  - resources/clojurecraft/goals.edn#{:goal/id :portal-kit
  - test/clojurecraft/make_test.clj#the-portal-kit-is-reached-only-beside-the-water-bucket
related:
  - "[[bot/decisions/_moc|Decisions]]"
  - "[[goals-and-intents]]"
  - "[[result-line]]"
---

# Goals in play chosen by :go

## The choice
Each target row in `goals.edn` names the `--until` value that selects it (`:goal/until`: `"wood"` → `:wood`, `"table"` → `:kit`, `"pickaxe"` → `:pickaxe`), and `plan/goals-for` reads it; the fixture or the loop puts the ids on the `:go` event as `:go/goals`; `plan/begin` stores them as the set `:plan/goals`; `plan/choose` only considers goals in that set (every goal when it is absent). The goal table itself stays whole.

## What was rejected
- Running the whole table always: the `:wood` gym would go on to craft the kit, so its judge (one log held) and its timing would depend on unrelated goals.
- A map from `--until` to goal ids in `main` (the first version): a second place to edit for every new target, and the fixture process needs the same mapping, so it lives in the table both read.
- A CLI flag read inside the planner or a dynamic var: a hidden input that a recording would not contain. On the `:go` event the choice is in the recording, so `clojure -M:replay` re-runs the same goals ([[record-replay]]).

## What would change the answer
Nothing for the mechanism. A fresh run sends the chain `plan/through` names: every live target up to the current stage, the shore trial left out (it is the gym's water aside). Escapes stay in play on any `:go`, because `choose` always considers `:goal/always?`. A gym trial ([[gym-on-runners]]) still sends the one id `goals-for` returns, so a portal row does not start by chopping wood.

A fresh run's `:go` also says `:go/chain? true`, and its steps are milestones (`plan/reach`): a target in play that holds joins `:plan/reached` and `choose` skips it for the rest of the run. Without that, a later step that spends what an earlier one made sends the planner back: the first chained run crafted the pickaxe, which spent the kit's sticks, so `:kit` was undone and preempted it, and the bot opened the table, crafted and crafted a stick twice a second for five minutes (`a-chained-run-does-not-go-back-for-a-step-it-reached`). A tool that breaks is still made again, by the needs of the step that uses it. A gym stage is not chained and keeps re-deriving every step. The chain also holds `:portal-kit`, what the cast spends that the iron kit does not hold (an empty bucket for lava, 192 cobblestone): the gym's portal rows give these, a run from spawn has to make them. Because a reached step is never asked again, the row counts the filled water bucket beside the empty one: race 38477665442 (issue #49, 28 real bots) reached `:portal-kit` with its only bucket still empty and the cobblestone in hand, filled that bucket at the pond, and the cast then found no empty bucket for the lava (`the-portal-kit-is-reached-only-beside-the-water-bucket`).

## See also
- [[randomness-on-the-tick]] - the same rule for another input.
