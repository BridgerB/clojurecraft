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
  - src/clojurecraft/shield.clj#defn lowered-before-hand
  - src/clojurecraft/combat.clj#defn guard?
  - src/clojurecraft/combat.clj#defn block-intent
  - src/clojurecraft/sim/protocol.clj#defn shield-blocks?
related:
  - "[[bot/decisions/_moc|Decisions]]"
  - "[[threats-as-data]]"
---

# The shield is up only while the bot stands

**The choice.** A shield in the offhand blocks every melee hit and every shot from in front once it has been up five server ticks; the server decides it from the look it last had. The bot raises it (a use-item with the offhand) while a fight stands at its target, lowers it (release-use-item) in the tick it swings and raises it again the next, so between two swings it blocks for all but the five ticks. It is never carried on a walk: a vanilla client walks at a fifth of its speed with the use key held, and this bot's physics does not model that, so walking with it up would be a gimmick. `shield/guard` runs after every planner tick: while the bot stands still (no intent, or a lookout, a pillar, a burrow, a dig, a pocket hold, a rest, no block being broken) it raises the shield toward the threat (what struck a moment ago, else the nearest hostile within four blocks), turning the look to it only where the intent leaves the look free (a lookout, a rest, a wait) and otherwise only when the threat is already in front; in a tick whose intent sends a packet that uses the hand (a dig, a place, a use, a swing) it lets the shield go just before that packet, so nothing is sent with the use key held. It changes the look and the offhand, never where the bot goes or what it does. While the planner waits, a shooter that struck within four seconds is faced with the shield up (the damage-event names the arrow, so the shooter is the nearest mob that shoots within its range). Against a shooter in sight the cover ladder's first rung is a :block of three seconds behind the shield rather than a meal: a vanilla client cannot eat with its shield up, and a meal in a skeleton's sight takes every arrow; a meal comes between two blocks.

**Rejected.** Lowering it where each intent ends: the planner preempts intents without asking them, and the next intent would run with the shield up.

**What would change this.** Physics that slows a walk with the use key held; then a fight's approach on a shooter could carry it.
