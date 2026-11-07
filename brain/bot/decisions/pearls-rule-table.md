---
title: The pearl hunt is a rule table
description: Why the pearl hunt's choice is rows in pearls-rules.edn read by one function over named facts, split by phase, and why its incident history lives in pearls-history.edn and problems.edn, not in the code.
type: decision
tags: [bot, decision, plan, pearls]
aliases: [pearls-rules.edn, pearls/rules, pearls/facts, hunt phases, rule table]
status: verified
lastUpdated: 2026-11-07
verifiedAgainst: HEAD
sourceRefs:
  - "src/clojurecraft/pearls/rules.clj#The hunt's intent in world: decide-in over the rows of the hunt alone."
  - src/clojurecraft/pearls/rules.clj#defn decide-all
  - src/clojurecraft/pearls/facts.clj#defn context
related:
  - "[[bot/decisions/_moc|Decisions]]"
  - "[[goals-and-intents]]"
  - "[[one-planner]]"
---

# The pearl hunt is a rule table

**The choice.** The hunt's next intent is the first row of `resources/clojurecraft/pearls-rules.edn` whose facts all hold. A row names its facts by key (`:rule/when`, each optionally `[:not k]`), its builder (`:rule/then`, a fact's value or an intent with facts filled in), its why label (`:rule/id`: the label `goals.edn` ranks and the recordings name, so it never changes) and its phase. `pearls/facts` is a table from fact key to a function of the fact getter and the world; a fact is computed at most once per decision and only when a row names it. `pearls/rules` is the one function that reads the table. The phases (`:leave-fortress`, `:reach-forest`, `:set-up`, `:provoke`, `:fight`, with `:survive` rows in every phase) say which part of the hunt a row belongs to; the current phase is the first not done.

**Rejected.** A 3,700-line namespace of nested conds, where each flip-flop added a guard to the branch that failed. A guard is now a fact and a row, in the order it is tried, and a reader sees the whole order in one file.

**Where the rest lives.** Thresholds are `pearls.edn` (`pearls/params`). The geometry and intents of the burrow are `pearls/place`, what the hunt perceives `pearls/sense`, cover `pearls/cover`, movers `pearls/move`. The story of why a function is as it is was cut from the docstrings into `pearls-history.edn` by function; the runs are `:problem/seen` in `problems.edn` and the issue.

**What would change this.** The plan being ordered strictly by urgency, so the `interrupts?` matrix of exemptions falls out of the order ([[goals-and-intents]]).
