---
title: Threats are data and open dispatch
description: Why what the bot does about a hostile is a per-kind facts table and a cover ladder in threats.edn, with the measures a ladder names as defmethods, and one shelter search.
type: decision
tags: [bot, decision, combat, plan]
aliases: [threats.edn, threat/measure, cover ladder, nearest-safe, shelter]
status: verified
lastUpdated: 2026-11-07
verifiedAgainst: HEAD
sourceRefs:
  - src/clojurecraft/threat.clj#defmulti measure
  - src/clojurecraft/threat.clj#defn nearest-safe
  - src/clojurecraft/threat.clj#def data
related:
  - "[[bot/decisions/_moc|Decisions]]"
  - "[[goals-and-intents]]"
---

# Threats are data and open dispatch

**The choice.** The facts about each hostile kind (the hit it deals on Normal, whether it is avoided alone, whether it teleports, whether a look provokes it, its radius) and the ladder of measures tried against a threat are data in `resources/clojurecraft/threats.edn`. A measure on the ladder is a `defmethod` of `threat/measure` in the namespace that knows how to carry it out (a new measure is a new method, not an edit of a cond). `threat/nearest-safe` is the one search over candidate cells for one no threat source reaches; the pearl hunt's burrow, the blaze fight's pocket and the fortress exit all ask it.

**Rejected.** Each stage keeping its own list of dangerous mobs and its own retreat search (shelter, pocket, cover, burrow, refuge were the same idea under five names).

**What would change this.** A measure that needs the stage's own state would argue for the ladder being per row.
