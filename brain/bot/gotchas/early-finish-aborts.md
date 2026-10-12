---
title: Early FINISH aborts
description: Symptom: the dig packets are sent, the arm swings, the block stays. Cause: a FINISH that reaches the server before its own timer is done cancels the break.
type: reference
tags: [bot, gotcha, dig]
aliases: [block does not break, finish too early, 1.15x rule, 1.35x rule]
status: verified
lastUpdated: 2026-10-11
verifiedAgainst: HEAD
sourceRefs:
  - src/clojurecraft/intent.clj#defn finish-delay
  - test/clojurecraft/props_test.clj#a-dig-never-finishes-before-its-deadline
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
`finish-delay` is the block's own break time with the held tool times 1.15, plus 100 ms ([[dig-timeline]]). The server (26.1.2 `ServerPlayerGameMode`, STOP_DESTROY) takes FINISH once the progress counted in its own ticks since START reaches 70%, so 1.15 holds while the server keeps above about 12 ticks a second. It was 1.35 and 200 ms, with 500 ms of settling before each START: a block of stone took about 1.75 s, and gym batch solid-b1 run 4 timed out tunnelling for its last iron. The sim ignores an early FINISH exactly like the server, and the property in `props_test` asserts FINISH is never earlier than that, for any block and any tool.

## See also
- [[dig-timeline]]
