---
title: One planner
description: Why the recipe-only graph walk (recipe/next-action) was removed once the needs planner in make covered everything it did, and what each was.
type: decision
tags: [bot, decision, plan, crafting]
aliases: [recipe/next-action removed, recipe graph, two planners, resolve-want]
status: verified
lastUpdated: 2026-10-10
verifiedAgainst: 532167d
sourceRefs:
  - src/clojurecraft/make.clj#defn resolve-need
  - test/clojurecraft/make_test.clj#the-needs-planner-walks-the-recipes
  - "docs/hickey.md#Simplicity is not a count of parts or lines"
related:
  - "[[bot/decisions/_moc|Decisions]]"
  - "[[make-goals]]"
---

# One planner

## The choice
There is one crafting planner: the needs planner in `make` (`next-intent :needs`), which walks a target's provides through hand-written producer rows and one generated row per recipe to the first row whose needs are met.

## What was rejected
Keeping `recipe/next-action` as well. It was the first planner (issue #2): a walk over recipes alone, from wanted items to `{:action :craft}`, `{:action :gather :want :logs}` or `:stuck`, with its own depth limit and its own rule for what is gathered (`raw?`, every item a log). When the goal table became data (`docs/hickey.md`'s shape), recipes became goal rows and the needs planner did everything the walk did and also placed tables; from then on the walk was called only by its own test. Two planners for one question braid the answer: a fix in one silently misses the other. Its test cases (species held, table kept, sticks needing another log, never the bamboo stick) were ported to `make_test` and pass against the needs planner.

## What would change the answer
Nothing for crafting. A planner for a different question (where to path, what to fight) is a different function, not a second answer to this one.

## See also
- [[make-goals]]
