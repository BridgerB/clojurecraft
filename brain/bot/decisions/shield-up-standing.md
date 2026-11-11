---
title: The shield is up only while the bot stands
description: Why the shield goes up between a fight's swings, behind a :block from a shooter and in a wait under fire, never on a walk, and is let go before any packet that uses the hand.
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

**The choice.** A shield in the offhand blocks every melee hit and every shot from in front once it has been up five server ticks; the server decides it from the look it last had. The bot raises it (a use-item with the offhand) while a fight stands at its target, lowers it (release-use-item) in the tick it swings and raises it again the next, so between two swings it blocks for all but the five ticks. It is never carried on a walk: a vanilla client walks at a fifth of its speed with the use key held, and this bot's physics does not model that, so walking with it up would be a gimmick. `shield/guard` runs after every planner tick and, in a tick whose intent sends a packet that uses the hand (a dig, a place, a use, a swing), lets a raised shield go just before that packet, so nothing is sent with the use key held. While the planner waits, a shooter that struck within four seconds is faced with the shield up (the damage-event names the arrow, so the shooter is the nearest mob that shoots within its range). Against a shooter in sight the cover ladder's first rung is a :block of three seconds behind the shield rather than a meal: a vanilla client cannot eat with its shield up, and a meal in a skeleton's sight takes every arrow; a meal comes between two blocks.

**Rejected.** Lowering it where each intent ends: the planner preempts intents without asking them, and the next intent would run with the shield up. Holding it up through every still intent (a lookout, a burrow, a dig between breaks) and turning a standoff at the shield into a fight: tried, and the forest start lost pearls and passes to standoffs that stood whole runs.

**What would change this.** Physics that slows a walk with the use key held; then a fight's approach on a shooter could carry it.
