---
title: The shield is up only while the bot stands
description: Why the shield goes up between a fight's swings and in a wait under fire, never on a walk, and why a planner tick lowers it before any other intent runs.
type: decision
tags: [bot, decision, combat]
aliases: [shield, raise, lower, guard, blocking]
status: verified
lastUpdated: 2026-11-08
verifiedAgainst: HEAD
sourceRefs:
  - src/clojurecraft/shield.clj#defn guard
  - src/clojurecraft/shield.clj#defn hold
  - src/clojurecraft/combat.clj#defn guard?
  - src/clojurecraft/sim/protocol.clj#defn shield-blocks?
related:
  - "[[bot/decisions/_moc|Decisions]]"
  - "[[threats-as-data]]"
---

# The shield is up only while the bot stands

**The choice.** A shield in the offhand blocks every melee hit and every shot from in front once it has been up five server ticks; the server decides it from the look it last had. The bot raises it (a use-item with the offhand) while a fight stands at its target, lowers it (release-use-item) in the tick it swings and raises it again the next, so between two swings it blocks for all but the five ticks. It is never carried on a walk: a vanilla client walks at a fifth of its speed with the use key held, and this bot's physics does not model that, so walking with it up would be a gimmick. `shield/guard` runs at the start of every planner tick, before any intent, and lowers a shield a fight left up, so a meal's use-item or a dig is never sent with the use key held. While the planner waits, a shooter that struck within four seconds is faced with the shield up.

**Rejected.** Lowering it where each intent ends: the planner preempts intents without asking them, and the next intent would run with the shield up.

**What would change this.** Physics that slows a walk with the use key held; then a fight's approach on a shooter could carry it.
