---
title: Protocol
type: moc
tags: [bot, protocol]
status: verified
lastUpdated: 2026-10-09
verifiedAgainst: 5c7d6c1
related:
  - "[[bot/_moc|Bot pillar]]"
---

# Protocol

- [[packet-specs]] - packets as maps, specs as data, where ids come from, what an unknown packet becomes.
- [[connection-phases]] - handshake to play: which packets flip the phase and who owns that state.
- [[packet-table]] - every modelled packet: phase, direction, fields, who handles or emits it.
- [[conn-framing]] - frames, zlib, the threshold switch, the reader and writer threads.
- [[bytes-primitives]] - varint sign rules, the x/z/y position bitfield, strings, UUIDs.

## See also
- [[bot/_moc|Bot pillar]]
- [[add-a-packet]] - the recipe.
