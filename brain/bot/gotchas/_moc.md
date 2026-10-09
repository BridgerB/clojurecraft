---
title: Gotchas
type: moc
tags: [bot, gotchas]
status: verified
lastUpdated: 2026-10-09
verifiedAgainst: 60b624e
related:
  - "[[bot/_moc|Bot pillar]]"
---

# Gotchas

- [[grass-type-is-grass]] - the ground is passable if you copy ruststeve's non-solid list.
- [[resting-vertical-velocity]] - a standing player's vy is never zero, so stillness is horizontal-only.
- [[packet-name-collision]] - a wire field called `name` collides with the packet's name.
- [[early-finish-aborts]] - FINISH before the server is done cancels the break silently.
- [[drop-under-the-trunk]] - digging the bottom log from above drops the item where the bot cannot stand.

## See also
- [[bot/_moc|Bot pillar]]
