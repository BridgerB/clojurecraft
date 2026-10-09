---
title: Early FINISH aborts
description: Symptom: the dig packets are sent, the arm swings, the block stays. Cause: a FINISH that reaches the server before its own timer is done cancels the break.
type: reference
tags: [bot, gotcha, dig]
aliases: [block does not break, finish too early, 1.35x rule]
status: verified
lastUpdated: 2026-10-09
verifiedAgainst: 60b624e
sourceRefs:
  - src/clojurecraft/intent.clj#def finish-after
  - src/clojurecraft/sim.clj#defmethod on-packet [:play :player-action]
  - test/clojurecraft/sim_test.clj#an-early-finish-does-not-break-the-block
related:
  - "[[bot/gotchas/_moc|Gotchas]]"
  - "[[dig-timeline]]"
  - "[[sib-dig-stop-timing]]"
---

# Early FINISH aborts

Symptom: START and swings go out, no error comes back, the log is still there, and the inventory never changes.

## Root cause
The server runs its own break timer from START. A FINISH (status 2) that arrives before that timer completes is treated as an abort; a late FINISH is accepted. Our local timer and the server's disagree by latency and tick alignment, so FINISH must be late on purpose. ruststeve established the rule ([[sib-dig-stop-timing]]); we verified it live three times and modelled it in the sim.

## Fix
`finish-after` is `dig-ms * 1.35 + 200`. The sim ignores an early FINISH exactly like the server, and the property in `plan_test` asserts FINISH is never earlier than that.

## See also
- [[dig-timeline]]
