---
title: ruststeve's dig STOP timing
description: ruststeve found that a STOP (FINISH) arriving before the server's own break timer aborts the break while a late one is accepted, and mines for 1.35x plus 200 ms.
type: reference
tags: [siblings, ruststeve, dig]
aliases: [late STOP, dig abort, mine_for 1.35]
status: draft
lastUpdated: 2026-10-09
verifiedAgainst: ruststeve bc575e3
sourceRefs:
  - ruststeve:src/bot/mod.rs#a STOP that arrives before the server is done ABORTS the break
related:
  - "[[siblings/_moc|Siblings]]"
  - "[[early-finish-aborts]]"
---

# ruststeve's dig STOP timing

The comment in ruststeve's `Bot::dig` states the rule; its loop holds the dig for `time * 1.35 + 200 ms` while re-looking and swinging every two ticks, then sends status 2. Our `finish-after` copies that constant and the sim encodes the server side of it.

## Limits
Draft: the anchor was quoted by a research agent; opening `src/bot/mod.rs` promotes this note. The rule itself is verified on our side ([[early-finish-aborts]]).

## See also
- [[early-finish-aborts]]
