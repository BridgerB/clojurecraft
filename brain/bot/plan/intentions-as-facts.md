---
title: Intentions as facts
description: How every intent's story (started, done, failed with why, abandoned) and the server's answers (acks, pickups) are appended to memory as facts, and how intention-as-of answers what the bot was trying to do at any moment of a run.
type: reference
tags: [bot, plan, memory, intents]
aliases: [answers, answer/kind, remember-answer, intention-as-of, intentions, remember-intention, intention/event, intent/id, what was I doing, as-of query]
status: verified
lastUpdated: 2026-10-10
verifiedAgainst: 5e319e2
sourceRefs:
  - src/clojurecraft/memory.clj#defn remember-intention
  - src/clojurecraft/memory.clj#defn intention-as-of
  - src/clojurecraft/memory.clj#defn intentions
  - src/clojurecraft/plan.clj#defn start-intent
  - src/clojurecraft/memory.clj#defn remember-answer
  - test/clojurecraft/sim_test.clj#the-servers-answers-are-facts
  - test/clojurecraft/sim_test.clj#what-the-bot-was-doing-is-a-query-as-of-any-moment
  - docs/hickey.md#"What was I trying to do when I died" is a query *as of* the tick before death.
related:
  - "[[bot/plan/_moc|Plan]]"
  - "[[goals-and-intents]]"
  - "[[facts-in-datascript]]"
  - "[[memory-sightings]]"
---

# Intentions as facts

The essay's information model includes "facts about its own intentions (planned, started, finished, abandoned, why)". Before this, the planner kept only the last intent (`:plan/last`); now every intent's story is in memory, beside the block sightings, and never retracted.

## How it works
1. `plan/start-intent` numbers the intent from `:plan/intents` (`:intent/id`) and appends `{:intention/id :intention/event :started :intention/kind :intention/at}` plus the target or recipe.
2. `finish-intent` appends `:done`; `fail-intent` appends `:failed` with `:intention/reason`; a new `:go` that replaces a running intent appends `:abandoned` (`plan/begin`).
3. `memory/intentions` is the whole story, oldest first. `memory/intention-as-of world t` is a Datalog query: for each intention, its latest fact at or before t; the newest one still `:started` is what the bot was doing at t, nil when nothing was.

## The server's answers
The essay's model also has "facts about the server's responses to its own actions (ack of sequence n, block broke, item picked up)". `game` appends `{:answer/kind :ack :answer/sequence n}` for every block-changed-ack and `{:answer/kind :pickup :answer/entity e :answer/count n}` when the collector is the bot itself, each with `:answer/at`; `memory/answers world kind` lists them. A broken block needs none: it is already a sighting of air. The counters `:stats/last-ack` and `:stats/pickups` stay as they were, so nothing changes meaning.

## Gotchas
- The facts carry the clock from the tick (`:time/now`), so a replay builds the same story; nothing about them is an effect, so effect verification is unchanged and older recordings still replay `:identical`.
- An intent without `:intent/id` (one put in place by hand, as some tests do) is not recorded.
- Death is not modelled yet; when it is, "what was I doing when I died" is `intention-as-of` at the tick before the death fact.

## See also
- [[goals-and-intents]] - where intents start and end.
