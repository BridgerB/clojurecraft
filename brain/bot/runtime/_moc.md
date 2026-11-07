---
title: Runtime
type: moc
tags: [bot, runtime]
status: verified
lastUpdated: 2026-10-09
verifiedAgainst: 5c7d6c1
related:
  - "[[bot/_moc|Bot pillar]]"
---

# Runtime

The process around the reducers: the loop, the clock, effects, and what a run prints.

- [[main-loop]] - run-loop's alts over socket, events and the 50 ms tick; apply-event!; stop, hold, exit.
- [[telemetry-watch]] - --telemetry: a watch on the atom writes what changed, never slowing the loop.
- [[result-line]] - every field of RESULT and the `--until` values.

## See also
- [[reducer-and-effects]] - what the loop feeds.
- [[harness-landing]] - the fixture process whose :go arrives on stdin.
- [[conn-framing]] - the socket threads.
