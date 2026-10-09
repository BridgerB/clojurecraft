---
title: ruststeve's dig STOP timing
description: Why a dig's FINISH (STOP) must be late: ruststeve found an early STOP aborts the break while a late one is accepted, holds 1.35x plus 200 ms, and reflects the break locally because it believed the server does not echo it (our 26.1.2 recording shows it does).
type: reference
tags: [siblings, ruststeve, dig]
aliases: [late STOP, dig abort, mine_for 1.35, no echo to the breaker]
status: verified
lastUpdated: 2026-10-09
verifiedAgainst: ruststeve bc575e3
sourceRefs:
  - "ruststeve:src/bot/mod.rs#own progress; a STOP that arrives before the server is done ABORTS the break (deepslate"
  - "ruststeve:src/bot/mod.rs#let mine_for = if time.is_zero() { Duration::ZERO } else { time.mul_f64(1.35) + Duration::from_millis(200) };"
  - "ruststeve:src/bot/mod.rs#FINISH but does NOT echo a block_update back to the breaking player, so"
related:
  - "[[siblings/_moc|Siblings]]"
  - "[[early-finish-aborts]]"
  - "[[sib-dig-hardness]]"
---

# ruststeve's dig STOP timing

`Bot::dig` in ruststeve sends START, then holds the dig for `time * 1.35 + 200 ms` (`mine_for`), re-looking at the block and swinging every two ticks, then sends status 2 (STOP/FINISH) with the next sequence number.

## Key files
- `src/bot/mod.rs`, `Bot::dig` - the comment above `mine_for`: "The server tracks its own progress; a STOP that arrives before the server is done ABORTS the break (deepslate frame cells stayed solid through two dig attempts on natural terrain), while a late STOP is accepted."
- the same function, after FINISH - claims "the server breaks the block in response to FINISH but does NOT echo a block_update back to the breaking player, so our world would stay stale and we'd re-dig the same block forever". It sets the block to air locally when the dig took under 4 s, and counts nearby item drops as proof.

## What they learned
1. The client's timer and the server's disagree by latency and tick alignment; only lateness is safe.
2. The 1.35x margin only works when `time` itself is right: with placeholder hardness it landed on the server's rejection threshold ([[sib-dig-hardness]]).

## What it means here
Our `finish-after` copies the constant and the sim encodes the server side ([[early-finish-aborts]]); our dig intent also overlays air locally after FINISH.

## Did not reproduce here
ruststeve's claim that the server does not echo a `block_update` to the breaker did not reproduce in our live 26.1.2 recording of the table run (2026-10-09): after our FINISH for a log the bot received a `block-update` setting the position to state 0, then `block-changed-ack` with the FINISH sequence. Re-check with `clojure -M:replay data/runs/table.edn`, or grep that recording (gitignored, local only) for `block-update` and `block-changed-ack` around the FINISH. Why ruststeve saw no echo is unresolved; do not build on either claim without a recording.

## Limits
ruststeve's local air overlay is conditional on `time < 4 s`; ours is unconditional. A rejected break (a `block-update` restoring the block) is handled by neither in a way we have copied.

## See also
- [[early-finish-aborts]]
